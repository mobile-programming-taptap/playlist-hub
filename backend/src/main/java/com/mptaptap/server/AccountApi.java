package com.mptaptap.server;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AccountApi {
    private final Store store;
    private final KakaoClient kakao;
    AccountApi(Store store,KakaoClient kakao) { this.store=store;this.kakao=kakao; }
    public record Registration(String email,String password,String name) {}
    public record Login(String email,String password) {}
    public record Password(String password,String kakaoAccessToken) {}
    public record KakaoLogin(String accessToken) {}

    @GetMapping("/accounts/kakao/config")
    public Map<String,Object> kakaoConfig() { return Map.of("configured",kakao.configured(),"setupMessage",kakao.setupMessage()); }

    @PostMapping("/accounts/kakao")
    public Map<String,String> kakaoLogin(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody KakaoLogin body,HttpServletRequest request) {
        kakao.requireConfigured();store.beginKakaoAttempt(body.accessToken(),request.getRemoteAddr());
        return store.kakaoLogin(auth,kakao.verify(body.accessToken()),body.accessToken());
    }

    @PostMapping("/accounts/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,String> register(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody Registration body,HttpServletRequest request) {
        return store.registerAccount(auth,body.email(),body.password(),body.name(),request.getRemoteAddr());
    }

    @PostMapping("/accounts/login")
    public Map<String,String> login(@RequestBody Login body,HttpServletRequest request) {
        return store.login(body.email(),body.password(),request.getRemoteAddr());
    }

    @GetMapping("/accounts/me")
    public Map<String,String> profile(@RequestHeader(value="Authorization",required=false) String auth) {
        return store.account(store.user(auth));
    }

    @DeleteMapping("/accounts/session") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value="Authorization",required=false) String auth) { synchronized(store.ownerLock(store.user(auth))){store.logout(auth);} }

    @DeleteMapping("/accounts/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody(required=false) Password body,HttpServletRequest request) {
        String owner=store.user(auth);synchronized(store.ownerLock(owner)){Map<String,String> profile;
        try{profile=store.account(owner);}
        catch(ResponseStatusException error){if(error.getStatusCode().value()!=404)throw error;store.deleteAnonymous(auth);return;}
        if(body==null)throw new IllegalArgumentException("계정 삭제를 위해 본인 확인을 완료해 주세요.");
        if(profile.get("authProvider").equals("kakao")) {
            kakao.requireConfigured();store.beginKakaoAttempt(body.kakaoAccessToken(),request.getRemoteAddr());
            store.deleteKakaoAccount(auth,kakao.verify(body.kakaoAccessToken()));
        } else store.deleteAccount(auth,body.password(),request.getRemoteAddr());}
    }

    @RestControllerAdvice(assignableTypes={AccountApi.class,CommunityApi.class})
    static class Errors {
        @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String,String> invalid(IllegalArgumentException error) {
            return Map.of("code","INVALID_INPUT","message",error.getMessage()==null?"요청 내용을 확인해 주세요.":error.getMessage());
        }
        @ExceptionHandler(HttpMessageNotReadableException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String,String> malformed() { return Map.of("code","INVALID_JSON","message","요청 형식을 확인해 주세요."); }
        @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<Map<String,String>> failure(ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",Store.errorCode(error,"REQUEST_FAILED"),"message",error.getReason()==null?"다시 시도해 주세요.":error.getReason()));
        }
    }
}
