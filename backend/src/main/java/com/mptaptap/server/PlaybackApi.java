package com.mptaptap.server;

import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;
import com.mptaptap.core.Track;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Query current official video status before any embedded-player request. */
@RestController
public class PlaybackApi {
    private final Store users;
    private final PlatformStore connections;
    private final PlatformClient remote;
    private final RecommendationCatalogue catalogue;
    private record VideoLink(String videoId,Instant expiresAt) {}
    // shortcut: bounded public-video references last one day in this process; use shared storage for multiple replicas.
    private final Map<String,VideoLink> links=new LinkedHashMap<>();
    PlaybackApi(Store users,PlatformStore connections,PlatformClient remote,RecommendationCatalogue catalogue){this.users=users;this.connections=connections;this.remote=remote;this.catalogue=catalogue;}

    @GetMapping("/playback/youtube/{id}")
    public ResponseEntity<PlatformClient.Playback> check(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id){
        String owner=users.user(auth);
        synchronized(users.ownerLock(owner)){
            users.user(auth);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(remote.playback(id,access(owner)));
        }
    }
    ResponseEntity<PlatformClient.Playback> resolve(String auth,String id){return resolve(auth,id,false);}
    @GetMapping("/playback/tracks/{id}")
    public ResponseEntity<PlatformClient.Playback> resolve(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestParam(defaultValue="false") boolean includeCandidates){
        String owner=users.user(auth);
        synchronized(users.ownerLock(owner)){
            users.user(auth);Track track=catalogue.track(id);String access=access(owner);VideoLink cached;
            if(!track.youtubeVideoId.isEmpty())return ResponseEntity.ok().header("Cache-Control","no-store").body(remote.playback(track.youtubeVideoId,access));
            synchronized(links){cached=links.get(id);}
            if(cached!=null&&cached.expiresAt().isAfter(Instant.now())){
                try{var checked=remote.playback(cached.videoId(),access);if(checked.playbackAllowed())return ResponseEntity.ok().header("Cache-Control","no-store").body(checked);}
                catch(PlatformClient.Failure error){if(error.status!=404)throw error;}
                synchronized(links){links.remove(id,cached);}
            }
            PlatformClient.Playback result=remote.findPlayback(track,access,includeCandidates);
            if(result==null||result.requiresConfirmation()&&!includeCandidates)throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"곡명·아티스트가 일치하는 공식 채널 영상을 찾지 못했어요. 외부에서 직접 검색할 수 있어요.");
            if(!result.requiresConfirmation()&&result.playbackAllowed())synchronized(links){
                links.entrySet().removeIf(entry->!entry.getValue().expiresAt().isAfter(Instant.now()));
                if(links.size()>=256)links.remove(links.keySet().iterator().next());
                links.put(id,new VideoLink(result.videoId(),Instant.now().plusSeconds(86400)));
            }
            return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
        }
    }
    private String access(String owner){
        if(!remote.setting("YOUTUBE_API_KEY").isBlank())return null;
        try{
            PlatformClient.Tokens token=connections.tokens(owner,"youtube");
            if(!token.expiresAt().isAfter(Instant.now().plusSeconds(30))){token=remote.refresh("youtube",token);connections.connection(owner,"youtube",token);}
            return token.accessToken();
        }catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=409)throw missing;return null;}
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","영상 연결 정보를 확인해 주세요."));}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",Store.errorCode(error,"PLAYBACK_UNAVAILABLE"),"message",error.getReason()==null?"영상을 확인하지 못했어요. YouTube에서 직접 열 수 있어요.":error.getReason()));}
    @ExceptionHandler(PlatformClient.Failure.class)
    public ResponseEntity<Map<String,String>> unavailable(PlatformClient.Failure error){return ResponseEntity.status(error.status==401?409:error.status==429?429:502).body(Map.of("code","PLAYBACK_UNAVAILABLE","message",error.getMessage()));}
}
