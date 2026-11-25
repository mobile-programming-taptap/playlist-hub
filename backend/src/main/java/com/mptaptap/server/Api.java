package com.mptaptap.server;

import com.mptaptap.core.Recommender;
import com.mptaptap.core.Conversation;
import com.mptaptap.core.Track;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class Api {
    private final Store store;
    private final OpenAi ai;
    private final RecommendationCatalogue catalogue;
    private final YoutubeLinks videos;

    Api(Store store, OpenAi ai, RecommendationCatalogue catalogue, YoutubeLinks videos) {
        this.store = store; this.ai = ai; this.catalogue=catalogue; this.videos=videos;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "ok", "catalogueSize", catalogue.all().size(), "aiConfigured", ai.available()); }

    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> session() { return store.register(); }

    @GetMapping("/catalogue")
    public List<Track> catalogue(@RequestHeader(value="Authorization", required=false) String auth, @RequestParam(defaultValue="") String q) {
        store.user(auth); checkQuery(q);
        return catalogue.all().stream().filter(t -> Track.matches(t.title + " " + t.artist, q)).toList();
    }

    public record Prompt(String prompt, List<String> history, List<String> keptTrackIds, List<String> excludedTrackIds,
            Recommender.Conditions previousConditions) {
        public Prompt(String prompt,List<String> history,List<String> keptTrackIds,List<String> excludedTrackIds){this(prompt,history,keptTrackIds,excludedTrackIds,null);}
    }
    public record Reaction(Integer reaction) {}
    public record PlaylistTitle(String title) {}
    public record PlaylistCreate(String title, String id) {}
    public record Item(String trackId) {}
    public record Items(List<String> trackIds) {}
    public record Order(List<String> itemIds) {}
    public record PlaylistOrder(List<String> playlistIds) {}
    public record ItemEdit(String title, String artist, Integer seconds) {}

    /** 외부 서비스 오류를 사용자가 읽을 수 있는 안내로 바꾼다. 조건 부족과 구분해서 쓴다. */
    private static String catalogueNotice(String reason) {
        if (reason == null || reason.isEmpty()) return "음악 정보를 확인하는 중 문제가 생겼어요. 같은 요청으로 다시 시도해 주세요.";
        if (reason.equals("CATALOGUE_BUSY"))
            return "지금 곡 확인 요청이 많아요. 잠시 뒤 같은 요청으로 다시 시도해 주세요.";
        if (reason.startsWith("CATALOGUE_HTTP_5") || reason.equals("CATALOGUE_UNAVAILABLE"))
            return "실제 곡 정보를 확인하는 서비스가 응답하지 않아요. 잠시 뒤 같은 요청으로 다시 시도해 주세요.";
        if (reason.equals("CATALOGUE_INVALID"))
            return "곡 정보 형식을 확인하지 못했어요. 같은 요청으로 다시 시도해 주세요.";
        return "실제 곡 정보를 확인하는 서비스에 연결하지 못했어요. 같은 요청으로 다시 시도해 주세요.";
    }

    @PostMapping("/recommendations")
    public Recommender.Result recommendations(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Prompt body) {
        String user = store.user(auth);
        List<Track> known=catalogue.all();
        Map<String,Integer> feedback=store.feedback(user);
        Conversation.requests(body.history(),body.prompt());
        if(body.previousConditions()!=null)Conversation.copyConditions(body.previousConditions());
        Conversation.validateSelection(known,feedback,body.keptTrackIds(),body.excludedTrackIds());
        if(!ai.available())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 추천이 연결되지 않았어요. 서버의 AI 연결 상태를 확인한 뒤 다시 시도해 주세요.");
        if(!store.reserveAi(user))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"AI 추천 요청 한도에 도달했어요. 잠시 후 같은 요청으로 다시 시도해 주세요.");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        OpenAi.Plan plan;
        Map<String,Integer> likedTags=new LinkedHashMap<>();
        try {
            for(Track track:known)if(feedback.getOrDefault(track.id,0)==1)
                for(String tag:track.tags)likedTags.merge(tag,1,Integer::sum);
            plan=firstPlan(body,likedTags,user,deadline);
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 요청이 중단됐어요. 같은 요청으로 다시 시도해 주세요.");
        } catch(Exception error) {
            // 화면 문구는 하나지만 키 만료·AI 응답 오류·네트워크 문제는 로그에서 구분한다.
            org.slf4j.LoggerFactory.getLogger(Api.class).warn("AI plan failed: {}: {}",error.getClass().getSimpleName(),error.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 연결이 원활하지 않아요. 같은 요청으로 다시 시도해 주세요.");
        }
        List<Track> verified;
        try {verified=catalogue.resolve(plan.candidates(),java.time.Duration.ofNanos(Math.max(1,deadline-System.nanoTime())),plan.conditions());}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"곡 확인 요청이 중단됐어요. 같은 요청으로 다시 시도해 주세요.");}
        catch(Exception error){
            String reason=error.getMessage();
            if(reason==null||!reason.matches("(?:CATALOGUE_[A-Z0-9_]+|RESPONSE_[A-Z_]+)"))reason=error.getClass().getSimpleName();
            org.slf4j.LoggerFactory.getLogger(Api.class).warn("Recording verification failed: {}",reason);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,catalogueNotice(reason));
        }
        Map<String,Track> candidates=new LinkedHashMap<>();
        java.time.Instant now=java.time.Instant.now();
        // 영상 후보조차 없다고 최근 확인한 곡은 카드로 보여 주지 않는다.
        for(Track track:verified)if(!videos.fresh(track.id,now).map(YoutubeLinks.Link::none).orElse(false))candidates.put(track.id,track);
        for(List<String> ids:List.of(body.keptTrackIds()==null?List.<String>of():body.keptTrackIds(),body.excludedTrackIds()==null?List.<String>of():body.excludedTrackIds()))
            for(String id:ids)candidates.putIfAbsent(id,catalogue.track(id));
        // 연대 집계는 마지막 추천 결과의 값만 남겨 1회차와 보충 회차가 같은 곡을 두 번 세지 않게 한다.
        Recommender.Stats stats=new Recommender.Stats();
        Recommender.Result result=Conversation.recommend(new ArrayList<>(candidates.values()),plan.conditions(),feedback,"openai",body.keptTrackIds(),body.excludedTrackIds(),stats);
        var verifiedIds=verified.stream().map(track->track.id).collect(java.util.stream.Collectors.toSet());
        int proposed=plan.candidates().size();
        // Reserve one recording lookup; its retries must fit the same overall deadline.
        long remaining=deadline-System.nanoTime()-java.time.Duration.ofSeconds(6).toNanos();
        // Half a full list (6 of 12) is still worth replenishing; fewer than 5 is left as the model's own uncertainty.
        if(result.tracks.size()<7&&verified.size()<proposed&&proposed>=5&&remaining>java.time.Duration.ofSeconds(2).toNanos()&&store.reserveAi(user)) {
            try {
                OpenAi.Plan extra=ai.supplement(body.prompt(),body.history(),plan.conditions(),likedTags,plan.candidates(),
                    java.time.Duration.ofNanos(Math.min(java.time.Duration.ofSeconds(15).toNanos(),remaining)));
                var previous=plan.candidates().stream().map(song->song.title()+"|"+song.artist()).collect(java.util.stream.Collectors.toSet());
                var fresh=extra.candidates().stream().filter(song->!previous.contains(song.title()+"|"+song.artist())).toList();
                proposed+=fresh.size();
                for(Track track:catalogue.resolve(fresh,java.time.Duration.ofNanos(Math.max(1,deadline-System.nanoTime())),plan.conditions())){verifiedIds.add(track.id);if(!videos.fresh(track.id,now).map(YoutubeLinks.Link::none).orElse(false))candidates.putIfAbsent(track.id,track);}
                Recommender.Stats retried=new Recommender.Stats();
                result=Conversation.recommend(new ArrayList<>(candidates.values()),plan.conditions(),feedback,"openai",body.keptTrackIds(),body.excludedTrackIds(),retried);
                stats=retried;
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 요청이 중단됐어요. 같은 요청으로 다시 시도해 주세요.");}
            // The AI call and the recording lookup share this catch, so keep the message and stack trace to tell them apart.
            catch(Exception error){org.slf4j.LoggerFactory.getLogger(Api.class).warn("Supplementary recommendation failed: {}: {}",error.getClass().getSimpleName(),error.getMessage(),error);}
        }
        String notice=result.notice;
        int shown=result.tracks.size();
        if(shown<7&&(body.keptTrackIds()==null||body.keptTrackIds().isEmpty())) {
            if(plan.candidates().isEmpty())notice="AI가 확실한 곡 후보를 제안하지 못했어요. 같은 요청으로 다시 시도해 주세요.";
            else if(verifiedIds.size()<proposed)notice="AI 후보 "+proposed+"곡 중 실제 곡 정보를 확인한 곡은 "+verifiedIds.size()+"곡이고, 요청과 반응을 반영한 추천은 "+shown+"곡이에요. 같은 요청으로 다른 후보를 찾아볼 수 있어요.";
        }
        // 연대 때문에 0곡이 된 안내는 7곡 미만 안내로 덮어써도 남긴다.
        List<Integer> decades=result.conditions.decades;
        if(!decades.isEmpty()&&!notice.equals(result.notice)&&result.notice.contains(Recommender.decadeNotice(decades)))notice=notice+" "+Recommender.decadeNotice(decades);
        org.slf4j.LoggerFactory.getLogger(Api.class).info("Recommendation counts: proposed={}, verified={}, shown={}, decades={}, undatedExcluded={}, outsideDecadeExcluded={}",
            proposed,verifiedIds.size(),shown,decades.isEmpty()?"-":decades.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|")),
            stats.undatedExcluded,stats.outsideDecadeExcluded);
        // 이미 찾은 공식 영상은 응답 카드에만 실어 바로 재생하게 한다. 곡 레코드에는 저장하지 않는다.
        for(Recommender.Pick pick:result.tracks)if(pick.track.youtubeVideoId.isEmpty())pick.track.youtubeVideoId=videos.fresh(pick.track.id,now).filter(YoutubeLinks.Link::verified).map(YoutubeLinks.Link::videoId).orElse("");
        // 7곡 미만 안내로 덮어써도 유지한 제외 안내와 지역 미반영 안내는 남긴다. 지역 안내가 맨 끝이다.
        return new Recommender.Result(result.tracks,result.conditions,"openai",Recommender.withKeptExclusionNotice(notice,plan.keptExclusions(),result.conditions));
    }

    private static final java.time.Duration FIRST_PLAN_TIMEOUT=java.time.Duration.ofSeconds(15),VERIFY_RESERVE=java.time.Duration.ofSeconds(6),MIN_RETRY=java.time.Duration.ofSeconds(5);

    /**
     * 첫 AI 요청이 시간 초과되면 한 번만 다시 묻는다. 첫 요청에는 유지할 결과가 없어 보충 요청처럼 넘어갈 수 없고,
     * 제한 시간을 늘리면 곡 확인 시간이 줄어든다. 재요청은 같은 30초 안에서 곡 확인 시간을 남긴 만큼만 기다린다.
     * 후보를 0곡 제안한 경우에도 한 번 더 묻는다. 어느 쪽이든 첫 단계의 재요청은 한 번뿐이다.
     */
    private OpenAi.Plan firstPlan(Prompt body,Map<String,Integer> likedTags,String user,long deadline) throws Exception {
        var log=org.slf4j.LoggerFactory.getLogger(Api.class);
        OpenAi.Plan plan;
        try{plan=ai.plan(body.prompt(),body.history(),body.previousConditions(),likedTags,FIRST_PLAN_TIMEOUT);}
        catch(java.io.IOException error){
            // 헤더가 늦으면 HttpTimeoutException, 본문이 늦으면 BoundedHttp의 RESPONSE_TIMEOUT 이다.
            if(!(error instanceof java.net.http.HttpTimeoutException)&&!"RESPONSE_TIMEOUT".equals(error.getMessage()))throw error;
            long wait=retryWait(deadline);
            if(wait<MIN_RETRY.toNanos()||!store.reserveAi(user))throw error;
            log.warn("AI plan timed out ({}); retrying once within {} ms",error.getClass().getSimpleName(),wait/1_000_000);
            return ai.plan(body.prompt(),body.history(),body.previousConditions(),likedTags,java.time.Duration.ofNanos(wait));
        }
        if(!plan.candidates().isEmpty())return plan;
        long wait=retryWait(deadline);
        if(wait<MIN_RETRY.toNanos()||!store.reserveAi(user))return plan;
        log.info("AI proposed no songs; asking once more within {} ms",wait/1_000_000);
        // 재요청이 실패하면 첫 결과의 조건과 "후보를 제안하지 못했어요" 안내를 그대로 쓴다.
        try{return ai.plan(body.prompt(),body.history(),body.previousConditions(),likedTags,java.time.Duration.ofNanos(wait));}
        catch(InterruptedException interrupted){throw interrupted;}
        catch(Exception error){log.warn("Retry after no AI proposals failed: {}: {}",error.getClass().getSimpleName(),error.getMessage());return plan;}
    }
    private static long retryWait(long deadline){return Math.min(FIRST_PLAN_TIMEOUT.toNanos(),deadline-System.nanoTime()-VERIFY_RESERVE.toNanos());}

    @GetMapping("/me/feedback")
    public Map<String, Integer> feedback(@RequestHeader(value="Authorization", required=false) String auth) { return store.feedback(store.user(auth)); }

    @GetMapping("/me/saved-tracks")
    public List<String> saved(@RequestHeader(value="Authorization", required=false) String auth) { return store.savedTracks(store.user(auth)); }

    @PutMapping("/me/feedback/{id}")
    public Map<String, Integer> react(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @RequestBody Reaction body) {
        String user = store.user(auth); track(id);
        if (body.reaction() == null || !List.of(-1, 1).contains(body.reaction())) throw new IllegalArgumentException("반응은 좋아요 또는 싫어요로 선택해 주세요.");
        store.feedback(user, id, body.reaction()); return Map.of("reaction", body.reaction());
    }

    @DeleteMapping("/me/feedback/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undo(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        String user = store.user(auth); track(id); store.feedback(user, id, 0);
    }

    @DeleteMapping("/me/feedback") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearFeedback(@RequestHeader(value="Authorization", required=false) String auth) { store.clearFeedback(store.user(auth)); }

    @GetMapping("/tracks/{id}/links")
    public List<Map<String, String>> links(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        store.user(auth); Track t = track(id);
        String query = URLEncoder.encode(t.title + " " + t.artist, StandardCharsets.UTF_8).replace("+", "%20");
        return List.of(Map.of("provider", "spotify", "kind", "search", "url", "https://open.spotify.com/search/" + query),
            Map.of("provider", "youtube_music", "kind", t.youtubeVideoId.isEmpty() ? "search" : "direct", "url", t.youtubeVideoId.isEmpty() ? "https://music.youtube.com/search?q=" + query : "https://music.youtube.com/watch?v=" + t.youtubeVideoId));
    }

    @GetMapping("/playlists")
    public List<Map<String, Object>> playlists(@RequestHeader(value="Authorization", required=false) String auth, @RequestParam(defaultValue="") String q) {
        String user = store.user(auth); checkQuery(q);
        return store.playlists(user).stream().filter(p -> Track.matches((String)p.get("title"), q)).toList();
    }

    @PostMapping("/playlists") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody PlaylistCreate body) {
        String user = store.user(auth);
        return store.createPlaylist(user, title(body.title()), body.id());
    }

    @PutMapping("/playlists/order") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderPlaylists(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody PlaylistOrder body) {
        store.reorderPlaylists(store.user(auth), body.playlistIds());
    }

    @PutMapping("/playlists/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @RequestBody PlaylistTitle body) {
        String user = store.user(auth); store.rename(user, id, title(body.title()));
    }

    @DeleteMapping("/playlists/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) { store.delete(store.user(auth), id); }

    @PutMapping("/playlists/{id}/items/order") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @RequestBody Order body) {
        store.reorder(store.user(auth), id, body.itemIds());
    }

    @GetMapping("/playlists/{id}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> playlist(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id) {
        Map<String, Object> result = store.playlist(store.user(auth), id);
        for (Map<String, Object> item : (List<Map<String, Object>>)result.get("items"))
            if (!item.containsKey("track") && item.get("trackId") != null) item.put("track", track((String)item.get("trackId")));
        return result;
    }

    @PostMapping("/playlists/{id}/items")
    public Map<String, Object> save(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @RequestBody Item body) {
        String user = store.user(auth); return store.add(user, id, track(body.trackId()));
    }

    @PostMapping("/playlists/{id}/items/batch")
    public List<Map<String, Object>> saveAll(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @RequestBody Items body) {
        String user = store.user(auth); List<String> ids = body.trackIds();
        if (ids == null || ids.isEmpty() || ids.size() > 10 || new java.util.HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("한 번에 서로 다른 1~10곡을 저장할 수 있어요.");
        return store.addAll(user, id, ids.stream().map(this::track).toList());
    }

    @DeleteMapping("/playlists/{id}/items/{item}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @PathVariable String item) { store.remove(store.user(auth), id, item); }

    @PutMapping("/playlists/{id}/items/{item}")
    public Map<String, Object> edit(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @PathVariable String item, @RequestBody ItemEdit body) {
        return store.editExternal(store.user(auth), id, item, body.title(), body.artist(), body.seconds());
    }

    private Track track(String id) {
        return catalogue.track(id);
    }

    private static String title(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 80) throw new IllegalArgumentException("목록 이름을 1~80자로 입력해 주세요.");
        return value.strip();
    }

    private static void checkQuery(String value) {
        if (value.length() > 100) throw new IllegalArgumentException("검색어는 100자 이하로 입력해 주세요.");
    }

    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("code", "INVALID_INPUT", "message", error.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> malformed() { return Map.of("code", "INVALID_JSON", "message", "요청 형식을 확인해 주세요."); }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> failure(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("code", Store.errorCode(error,"REQUEST_FAILED"), "message", error.getReason() == null ? "다시 시도해 주세요." : error.getReason()));
    }
}
