package com.mptaptap.server;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommunityApi {
    private final Store store;
    CommunityApi(Store store) { this.store=store; }
    public record Favourite(Boolean favourite) {}
    public record Copy(String title,String id) {}
    public record Edit(String title,String artist,Integer seconds) {}
    public record Acceptance(String version,Boolean accepted) {}
    public record Report(String target,String reason,String details) {}

    @GetMapping("/community/rules")
    public Map<String,Object> rules(){return Map.of("version",Store.COMMUNITY_RULES_VERSION,"rules",List.of(
        "공개할 권리가 있는 목록·곡 정보만 게시하고 타인의 저작권과 개인정보를 존중해 주세요.",
        "스팸·사기, 혐오·차별, 괴롭힘·신상 공개, 성적 착취·아동 유해 콘텐츠, 폭력·위협과 불법 콘텐츠를 게시하지 마세요.",
        "문제 있는 목록이나 작성자는 앱에서 신고할 수 있어요. 신고는 운영 검토를 위해 저장되며 자동 삭제나 즉시 처리 완료를 의미하지 않아요.",
        "작성자 차단은 로그인한 내 앱의 발견·검색·순위·즐겨찾기·상세·복사에 적용돼요. 이미 복사한 목록이나 비로그인 공개 웹 링크를 전부 삭제하는 기능은 아니에요.",
        "운영 검토로 숨겨진 목록은 공개 링크에서도 열리지 않으며 같은 목록을 다시 공개할 수 없어요."),"reportReasons",Store.COMMUNITY_REPORT_REASONS,"maxReportDetails",500,"reportDailyLimit",10);}

    @GetMapping("/community/rules/acceptance")
    public Map<String,Object> acceptance(@RequestHeader(value="Authorization",required=false)String auth){return store.communityAcceptance(store.user(auth));}

    @PutMapping("/community/rules/acceptance")
    public Map<String,Object> accept(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Acceptance body){return withOwner(auth,user->store.acceptCommunityRules(user,body.version(),body.accepted()));}

    @PostMapping("/public/playlists/{shareId}/reports")
    public ResponseEntity<Map<String,Object>> report(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String shareId,@RequestBody Report body){
        var result=withOwner(auth,user->store.reportPublic(user,shareId,body.target(),body.reason(),body.details()));
        return ResponseEntity.status(Boolean.TRUE.equals(result.get("duplicate"))?200:201).body(result);
    }

    @PostMapping("/public/playlists/{shareId}/block-author")
    public Map<String,Object> block(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String shareId){return withOwner(auth,user->store.blockPublicAuthor(user,shareId));}

    @GetMapping("/community/blocks")
    public List<Map<String,Object>> blocks(@RequestHeader(value="Authorization",required=false)String auth){return store.communityBlocks(store.user(auth));}

    @DeleteMapping("/community/blocks/{blockId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String blockId){withOwner(auth,user->{store.unblockCommunityAuthor(user,blockId);return true;});}

    @GetMapping("/playlists/{id}/publication")
    public Map<String,Object> publication(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id) {
        return store.publication(store.user(auth),id);
    }

    @PutMapping("/playlists/{id}/publication")
    public Map<String,Object> publish(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody Map<String,Object> body) {
        if (!(body.get("public") instanceof Boolean visible)) throw new IllegalArgumentException("공개 여부를 선택해 주세요.");
        return withOwner(auth,user->store.publish(user,id,visible));
    }

    @PutMapping("/playlists/{id}/items/{item}")
    public Map<String,Object> edit(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@PathVariable String item,@RequestBody Edit body) {
        return store.editExternal(store.user(auth),id,item,body.title(),body.artist(),body.seconds());
    }

    @GetMapping("/public/playlists")
    public List<Map<String,Object>> publicPlaylists(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="popular") String sort,@RequestParam(defaultValue="false") boolean favourites) {
        return store.publicPlaylists(optionalUser(auth),q,sort,favourites);
    }

    @GetMapping("/public/playlists/{shareId}")
    public Map<String,Object> publicPlaylist(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String shareId) {
        return store.publicPlaylist(shareId,optionalUser(auth));
    }

    @PutMapping("/public/playlists/{shareId}/favourite")
    public Map<String,Object> favourite(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String shareId,@RequestBody Favourite body) {
        if (body.favourite()==null) throw new IllegalArgumentException("즐겨찾기 여부를 선택해 주세요.");
        return store.favourite(store.user(auth),shareId,body.favourite());
    }

    @PostMapping("/public/playlists/{shareId}/copy") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> copy(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String shareId,@RequestBody(required=false) Copy body) {
        return store.copyPublic(store.user(auth),shareId,body==null?null:body.title(),body==null?null:body.id());
    }

    @GetMapping(value="/share/{shareId}",produces=MediaType.TEXT_HTML_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> share(@PathVariable String shareId) {
        Map<String,Object> playlist=store.publicPlaylist(shareId,null);
        String title=escape(playlist.get("title"));
        StringBuilder page=new StringBuilder("<!doctype html><html lang=ko><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>").append(title)
            .append(" · TapTap</title><style>body{font-family:system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 24px;background:#f8faf9;color:#14221e}li{padding:12px 0}a{color:#17634c}small{display:block;color:#52655e}</style><h1>").append(title)
            .append("</h1><p>").append(escape(playlist.get("author"))).append(" · ").append(playlist.get("size")).append("곡</p><ol>");
        for(Map<String,Object> item:(List<Map<String,Object>>)playlist.get("items")) {
            Map<String,Object> track=(Map<String,Object>)item.get("track");
            String url=link(track), label=escape(track.getOrDefault("title","원본 정보 다시 확인 필요"));
            page.append("<li>");
            if(!url.isEmpty()) page.append("<a rel=\"noopener noreferrer\" href=\"").append(escape(url)).append("\">").append(label).append("</a>");
            else page.append(label);
            page.append("<small>").append(escape(track.getOrDefault("artist",""))).append("</small></li>");
        }
        page.append("</ol><p><small>TapTap 공개 목록 · 곡 정보의 출처는 각 원본 링크에서 확인할 수 있어요. 즐겨찾기 순위는 TapTap 안의 반응을 기준으로 해요. <a href=\"/privacy\">개인정보 처리 안내</a></small></p></html>");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(page.toString());
    }

    private <T> T withOwner(String auth,java.util.function.Function<String,T> action){String user=store.user(auth);synchronized(store.ownerLock(user)){store.user(auth);return action.apply(user);}}
    private String optionalUser(String auth) { return auth==null?null:store.user(auth); }
    private static String escape(Object value) {
        return String.valueOf(value==null?"":value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    private static String link(Map<String,Object> track) {
        String url=String.valueOf(track.getOrDefault("sourceUrl",""));
        if(Boolean.TRUE.equals(track.get("external"))) return Store.safeExternalUrl(String.valueOf(track.get("sourceProvider")),url)?url:"";
        String video=String.valueOf(track.getOrDefault("youtubeVideoId",""));
        if(video.matches("[A-Za-z0-9_-]{11}")) return "https://music.youtube.com/watch?v="+video;
        try { URI uri=URI.create(url); return "https".equals(uri.getScheme()) && "musicbrainz.org".equals(uri.getHost()) && uri.getUserInfo()==null?url:""; }
        catch(IllegalArgumentException invalid) { return ""; }
    }
}
