package com.mptaptap.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Optional operator review; credentials never appear in URLs or public responses. */
@RestController
public class ModerationApi {
    private final Store store;
    private final byte[] secret;
    ModerationApi(Store store,@Value("${MODERATION_ADMIN_SECRET:}")String configured){
        this.store=store;secret=configured!=null&&configured.matches("[A-Za-z0-9_-]{32,256}")?configured.getBytes(StandardCharsets.UTF_8):null;
    }
    private void authorize(String supplied){
        if(secret==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"운영 검토 인증이 설정되지 않았습니다.");
        if(supplied==null||supplied.length()>256||!MessageDigest.isEqual(secret,supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"운영 검토 인증을 확인해 주세요.");
    }
    @GetMapping("/moderation/reports")
    public ResponseEntity<List<Map<String,Object>>> reports(@RequestHeader(value="X-TapTap-Moderation-Key",required=false)String key,@RequestParam(defaultValue="OPEN")String status){authorize(key);return ResponseEntity.ok().header("Cache-Control","no-store").body(store.moderationReports(status));}
    public record Decision(String action){}
    @PostMapping("/moderation/reports/{id}/decision")
    public ResponseEntity<Map<String,Object>> decide(@RequestHeader(value="X-TapTap-Moderation-Key",required=false)String key,@PathVariable String id,@RequestBody Decision body){authorize(key);return ResponseEntity.ok().header("Cache-Control","no-store").body(store.moderateReport(id,body.action()));}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).header("Cache-Control","no-store").body(Map.of("code",Store.errorCode(error,"MODERATION_UNAVAILABLE"),"message",error.getReason()==null?"신고 처리를 확인해 주세요.":error.getReason()));}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("code","INVALID_INPUT","message","신고 처리 내용을 확인해 주세요."));}
}
