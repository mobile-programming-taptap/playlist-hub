package com.mptaptap.server;

import com.mptaptap.core.Recommender;
import com.mptaptap.core.Conversation;
import com.mptaptap.core.Track;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class Api {
    private final Store store;
    private final OpenAi ai;
    private final List<Track> catalogue;
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    Api(Store store, OpenAi ai, ObjectMapper json) throws Exception {
        this.store = store; this.ai = ai;
        try (var input = new ClassPathResource("tracks.json").getInputStream()) {
            catalogue = Arrays.asList(json.readerFor(Track[].class).without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(input));
        }
        for (Track track : catalogue) {
            if (!track.usable() || tracks.put(track.id, track) != null) throw new IllegalStateException("Invalid or duplicate catalogue entry");
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "ok", "catalogueSize", catalogue.size(), "aiConfigured", ai.available()); }

    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> session() { return store.register(); }

    @GetMapping("/catalogue")
    public List<Track> catalogue(@RequestHeader(value="Authorization", required=false) String auth, @RequestParam(defaultValue="") String q) {
        store.user(auth); checkQuery(q);
        return catalogue.stream().filter(t -> Track.matches(t.title + " " + t.artist, q)).toList();
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

    @PostMapping("/recommendations")
    public Recommender.Result recommendations(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Prompt body) {
        String user = store.user(auth);
        Recommender.Conditions c = Conversation.parse(body.previousConditions(),body.history(),body.prompt());
        Conversation.recommend(catalogue,c,store.feedback(user),"basic",body.keptTrackIds(),body.excludedTrackIds());
        String mode = "basic"; String fallback = "";
        if (ai.available()) {
            if (store.reserveAi(user)) {
                try { c = ai.extract(body.prompt(),body.history(),body.previousConditions()); mode = "openai"; }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); fallback = "AI 요청이 중단되어 기본 추천으로 이어갔어요."; }
                catch (Exception error) { fallback = "AI 연결이 원활하지 않아 기본 추천으로 이어갔어요."; }
            } else fallback = "AI 요청 한도에 도달해 기본 추천으로 이어갔어요.";
        } else fallback = "현재는 기본 추천이에요. 서버에 OpenAI를 연결하면 자연어 해석을 더 넓게 지원해요.";
        Recommender.Result result = Conversation.recommend(catalogue,c,store.feedback(user),mode,body.keptTrackIds(),body.excludedTrackIds());
        return new Recommender.Result(result.tracks, c, mode, String.join(" ", List.of(fallback, result.notice)).trim());
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
        Track t = tracks.get(id);
        if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "곡을 찾을 수 없어요.");
        return t;
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
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("code", "REQUEST_FAILED", "message", error.getReason() == null ? "다시 시도해 주세요." : error.getReason()));
    }
}
