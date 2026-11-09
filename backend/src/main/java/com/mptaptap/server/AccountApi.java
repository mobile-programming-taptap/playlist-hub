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
    AccountApi(Store store) { this.store=store; }
    public record Registration(String email,String password,String name) {}
    public record Login(String email,String password) {}
    public record Password(String password) {}

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
    public void logout(@RequestHeader(value="Authorization",required=false) String auth) { store.logout(auth); }

    @DeleteMapping("/accounts/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Password body,HttpServletRequest request) {
        store.deleteAccount(auth,body.password(),request.getRemoteAddr());
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
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("code","REQUEST_FAILED","message",error.getReason()==null?"다시 시도해 주세요.":error.getReason()));
        }
    }
}
