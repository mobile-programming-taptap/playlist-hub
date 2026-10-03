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

    @GetMapping("/playlists/{id}/publication")
    public Map<String,Object> publication(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id) {
        return store.publication(store.user(auth),id);
    }

    @PutMapping("/playlists/{id}/publication")
    public Map<String,Object> publish(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestBody Map<String,Object> body) {
        if (!(body.get("public") instanceof Boolean visible)) throw new IllegalArgumentException("공개 여부를 선택해 주세요.");
        return store.publish(store.user(auth),id,visible);
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
        page.append("</ol><p><small>TapTap 공개 목록 · 곡 정보의 출처는 각 원본 링크에서 확인할 수 있어요. 즐겨찾기 순위는 TapTap 안의 반응을 기준으로 해요.</small></p></html>");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(page.toString());
    }

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
