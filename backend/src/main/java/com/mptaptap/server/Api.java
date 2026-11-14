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

    Api(Store store, OpenAi ai, RecommendationCatalogue catalogue) {
        this.store = store; this.ai = ai; this.catalogue=catalogue;
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
            plan=ai.plan(body.prompt(),body.history(),body.previousConditions(),likedTags);
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 요청이 중단됐어요. 같은 요청으로 다시 시도해 주세요.");
        } catch(Exception error) {
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
        for(Track track:verified)candidates.put(track.id,track);
        for(List<String> ids:List.of(body.keptTrackIds()==null?List.<String>of():body.keptTrackIds(),body.excludedTrackIds()==null?List.<String>of():body.excludedTrackIds()))
            for(String id:ids)candidates.putIfAbsent(id,catalogue.track(id));
        Recommender.Result result=Conversation.recommend(new ArrayList<>(candidates.values()),plan.conditions(),feedback,"openai",body.keptTrackIds(),body.excludedTrackIds());
        var verifiedIds=verified.stream().map(track->track.id).collect(java.util.stream.Collectors.toSet());
        int proposed=plan.candidates().size();
        // Reserve one recording lookup; its retries must fit the same overall deadline.
        long remaining=deadline-System.nanoTime()-java.time.Duration.ofSeconds(6).toNanos();
        if(result.tracks.size()<7&&verified.size()<proposed&&proposed>=7&&remaining>java.time.Duration.ofSeconds(2).toNanos()&&store.reserveAi(user)) {
            try {
                OpenAi.Plan extra=ai.supplement(body.prompt(),body.history(),plan.conditions(),likedTags,plan.candidates(),
                    java.time.Duration.ofNanos(Math.min(java.time.Duration.ofSeconds(15).toNanos(),remaining)));
                var previous=plan.candidates().stream().map(song->song.title()+"|"+song.artist()).collect(java.util.stream.Collectors.toSet());
                var fresh=extra.candidates().stream().filter(song->!previous.contains(song.title()+"|"+song.artist())).toList();
                proposed+=fresh.size();
                for(Track track:catalogue.resolve(fresh,java.time.Duration.ofNanos(Math.max(1,deadline-System.nanoTime())),plan.conditions())){candidates.putIfAbsent(track.id,track);verifiedIds.add(track.id);}
                result=Conversation.recommend(new ArrayList<>(candidates.values()),plan.conditions(),feedback,"openai",body.keptTrackIds(),body.excludedTrackIds());
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 요청이 중단됐어요. 같은 요청으로 다시 시도해 주세요.");}
            catch(Exception error){org.slf4j.LoggerFactory.getLogger(Api.class).warn("Supplementary recommendation failed: {}",error.getClass().getSimpleName());}
        }
        String notice=result.notice;
        int shown=result.tracks.size();
        if(shown<7&&(body.keptTrackIds()==null||body.keptTrackIds().isEmpty())) {
            if(plan.candidates().isEmpty())notice="AI가 확실한 곡 후보를 제안하지 못했어요. 같은 요청으로 다시 시도해 주세요.";
            else if(verifiedIds.size()<proposed)notice="AI 후보 "+proposed+"곡 중 실제 곡 정보를 확인한 곡은 "+verifiedIds.size()+"곡이고, 요청과 반응을 반영한 추천은 "+shown+"곡이에요. 같은 요청으로 다른 후보를 찾아볼 수 있어요.";
        }
        org.slf4j.LoggerFactory.getLogger(Api.class).info("Recommendation counts: proposed={}, verified={}, shown={}",proposed,verifiedIds.size(),shown);
        return new Recommender.Result(result.tracks,result.conditions,"openai",notice);
    }

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
