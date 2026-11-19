package com.mptaptap.server;

import java.util.Map;
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
    private final YoutubeLinks links;
    PlaybackApi(Store users,PlatformStore connections,PlatformClient remote,RecommendationCatalogue catalogue,YoutubeLinks links){this.users=users;this.connections=connections;this.remote=remote;this.catalogue=catalogue;this.links=links;}

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
            users.user(auth);Track track=catalogue.track(id);String access=access(owner);
            if(!track.youtubeVideoId.isEmpty())return ResponseEntity.ok().header("Cache-Control","no-store").body(remote.playback(track.youtubeVideoId,access));
            YoutubeLinks.Link known=links.fresh(id,Instant.now()).orElse(null);
            if(known!=null&&known.verified()){
                try{var checked=remote.playback(known.videoId(),access);if(checked.playbackAllowed())return ResponseEntity.ok().header("Cache-Control","no-store").body(checked);}
                catch(PlatformClient.Failure error){if(error.status!=404)throw error;}
                links.forget(id);
            }
            // A recent "nothing found" check spares the search quota; pressing play still looks for candidates.
            if(known!=null&&known.none()&&!includeCandidates)throw noOfficialVideo();
            PlatformClient.Playback result=remember(id,remote.findPlayback(track,access,includeCandidates));
            if(result==null||result.requiresConfirmation()&&!includeCandidates)throw noOfficialVideo();
            return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
        }
    }
    /**
     * Background check for a recommendation card: "verified" (official video, with its id), "candidate" (a matching video
     * the user confirms when playing) or 404 when nothing matches. It searches only within the daily background budget.
     */
    @GetMapping("/playback/tracks/{id}/check")
    public ResponseEntity<Map<String,String>> checkTrack(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id){
        String owner=users.user(auth);
        synchronized(users.ownerLock(owner)){
            users.user(auth);Track track=catalogue.track(id);
            if(!track.youtubeVideoId.isEmpty())return checked("verified",track.youtubeVideoId);
            YoutubeLinks.Link known=links.fresh(id,Instant.now()).orElse(null);
            if(known==null){
                if(!links.reserveCheck(Instant.now()))throw new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"오늘 미리 확인할 수 있는 영상 수를 다 썼어요. 남은 곡은 재생할 때 찾아요.");
                PlatformClient.Playback result=remember(id,remote.findPlayback(track,access(owner),true));
                org.slf4j.LoggerFactory.getLogger(PlaybackApi.class).info("Video check: state={}, channel={}",
                    result==null?"none":result.requiresConfirmation()?"candidate":"verified",result==null||!result.requiresConfirmation()?"":result.candidateChannel());
                known=result==null?new YoutubeLinks.Link("",false):new YoutubeLinks.Link(result.videoId(),!result.requiresConfirmation());
            }
            if(known.none())throw noOfficialVideo();
            return known.verified()?checked("verified",known.videoId()):checked("candidate","");
        }
    }
    private static ResponseEntity<Map<String,String>> checked(String state,String videoId){return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("state",state,"videoId",videoId));}
    /** Stores what a search found: an official video, an unconfirmed candidate, or nothing. */
    private PlatformClient.Playback remember(String id,PlatformClient.Playback result){
        if(result==null)links.remember(id,"",Instant.now());
        else if(result.requiresConfirmation())links.rememberCandidate(id,result.videoId(),Instant.now());
        else if(result.playbackAllowed())links.remember(id,result.videoId(),Instant.now());
        return result;
    }
    private static ResponseStatusException noOfficialVideo(){return new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"곡명·아티스트가 일치하는 공식 채널 영상을 찾지 못했어요. 외부에서 직접 검색할 수 있어요.");}
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
