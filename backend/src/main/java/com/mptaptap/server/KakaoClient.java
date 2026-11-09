package com.mptaptap.server;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Kakao SDK tokens are verified remotely and are never persisted. */
@Component
public class KakaoClient {
    public record Identity(String subject,String name,String email) {}
    private final ObjectMapper json;
    private final long appId;
    private final String base;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final java.util.concurrent.ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"kakao-body-deadline");thread.setDaemon(true);return thread;});

    @Autowired KakaoClient(Environment env,ObjectMapper json) { this(json,env.getProperty("taptap.kakao.app-id",""),"https://kapi.kakao.com"); }
    // Test-only endpoint injection cannot be enabled through server configuration.
    KakaoClient(ObjectMapper json,String appId,String base) {
        this.json=json;this.base=base;long parsed=0;
        try { if(appId!=null&&appId.matches("[1-9][0-9]{0,18}"))parsed=Long.parseLong(appId); }catch(NumberFormatException ignored){}
        this.appId=parsed;
    }
    @PreDestroy void close() { http.shutdownNow();deadlines.shutdownNow(); }
    boolean configured() { return appId>0; }
    String setupMessage() { return configured()?"":"서버의 KAKAO_APP_ID에 카카오 개발자 앱의 숫자 앱 ID를 설정해 주세요."; }
    void requireConfigured() { if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,setupMessage()); }
    static void validateToken(String token) {
        if(token==null||token.isEmpty()||token.length()>4096||!token.matches("[A-Za-z0-9._~+/=-]+"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"카카오 로그인을 다시 진행해 주세요.");
    }

    Identity verify(String token) {
        requireConfigured();validateToken(token);
        JsonNode info=get("/v1/user/access_token_info",token);
        long subject=positive(info,"id"),issuedApp=positive(info,"app_id");
        positive(info,"expires_in");
        if(issuedApp!=appId)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"이 앱에서 발급한 카카오 로그인으로 다시 연결해 주세요.");
        JsonNode me=get("/v2/user/me",token);
        if(positive(me,"id")!=subject)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"카카오 계정 확인 정보가 일치하지 않아요. 다시 로그인해 주세요.");
        JsonNode account=me.path("kakao_account");
        String name=account.path("profile").path("nickname").asString("").strip();
        if(name.isEmpty())name=me.path("properties").path("nickname").asString("").strip();
        if(name.isEmpty())name="카카오 사용자";
        if(name.length()>80)name=name.substring(0,Character.isHighSurrogate(name.charAt(79))?79:80);
        String email="";
        if(account.path("is_email_valid").asBoolean()&&account.path("is_email_verified").asBoolean()&&!account.path("email_needs_agreement").asBoolean()) {
            String provided=account.path("email").asString("").strip().toLowerCase(java.util.Locale.ROOT);
            if(provided.length()<=254&&provided.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))email=provided;
        }
        return new Identity(Long.toString(subject),name,email);
    }
    private static long positive(JsonNode node,String field) {
        JsonNode value=node.path(field);
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<=0)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"카카오 로그인이 만료되었거나 계정 정보를 확인할 수 없어요. 다시 로그인해 주세요.");
        return value.asLong();
    }
    private JsonNode get(String path,String token) {
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10))
            .header("Accept","application/json").header("Authorization","Bearer "+token).GET().build();
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try {
            HttpResponse<java.io.InputStream> response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            AtomicBoolean expired=new AtomicBoolean();
            var timer=deadlines.schedule(()->{expired.set(true);try{response.body().close();}catch(IOException ignored){}},Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            try(var input=response.body()) {
                int status=response.statusCode();
                if(status==401)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"카카오 로그인이 만료되었어요. 다시 로그인해 주세요.");
                if(status==403)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"카카오 로그인 권한을 확인한 뒤 다시 로그인해 주세요.");
                if(status==429)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"카카오 로그인 요청이 많아요. 잠시 후 다시 시도해 주세요.");
                if(status!=200)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"카카오 계정 확인 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
                byte[] body=input.readNBytes(65537);
                if(expired.get())throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"카카오 계정 확인이 늦어지고 있어요. 다시 시도해 주세요.");
                if(body.length>65536)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"카카오 계정 응답을 확인할 수 없어요.");
                JsonNode value=json.readTree(body);
                if(value==null||!value.isObject())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"카카오 계정 응답을 확인할 수 없어요.");
                return value;
            }finally{timer.cancel(false);}
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"카카오 계정 확인이 중단되었어요. 다시 시도해 주세요.");}
        catch(IOException|tools.jackson.core.JacksonException error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"카카오 계정 확인 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");}
    }
}
