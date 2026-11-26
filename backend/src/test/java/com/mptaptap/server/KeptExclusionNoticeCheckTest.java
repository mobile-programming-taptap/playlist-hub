package com.mptaptap.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** AI가 원한 태그를 앞선 제외 조건 때문에 뺐다면, 모순 조합을 안내 없이 만들지 않고 제외를 지켰다고 알린다. */
class KeptExclusionNoticeCheckTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();
    private String base;

    @Test void keptExclusionSurvivesTheCandidateCountNoticeAndPrecedesTheRegionNotice() throws Exception {
        // 세 곡을 제안하고 두 곡만 확인되어 "AI 후보 3곡 중…" 안내가 기존 안내를 덮어쓴다.
        List<Map<String,Object>> songs=List.of(song("Night letter 0"),song("Night letter 1"),song("Night letter 2"));
        Map<String,Object> plan=new LinkedHashMap<>(Map.of("tags",List.of("kpop","calm"),"excludedTags",List.of("calm"),"inferredTags",List.of("calm"),
            "language","ko","languageSpecified",true,"excludedArtists",List.of()));
        plan.put("candidates",songs);
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{exchange.getRequestBody().readAllBytes();respond(exchange,200,envelope(plan));});
        mb.createContext("/recording/",exchange->respond(exchange,200,Map.of("recordings",songs.subList(0,2).stream().map(KeptExclusionNoticeCheckTest::recording).toList())));
        ai.start();mb.start();
        ConfigurableApplicationContext app=null;
        try {
            app=start("http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            String token=value("POST","/sessions",null,null,201).path("token").asString();
            String first="한국 노래로 찾아줘 잔잔한 건 싫어";
            Map<String,Object> previous=Map.of("tags",List.of("kpop"),"excludedTags",List.of("calm"),"language","ko","origin","KR");
            JsonNode result=value("POST","/recommendations",token,Map.of("prompt","밤편지 같은 느낌으로 바꿔줘","history",List.of(first),"previousConditions",previous),200);
            assertTrue(result.path("conditions").path("excludedTags").toString().contains("calm"));
            assertFalse(result.path("conditions").path("tags").toString().contains("calm"));
            String notice=result.path("notice").asString();
            assertTrue(notice.startsWith("AI 후보 3곡 중"),notice);
            assertTrue(notice.endsWith(" 잔잔한 제외는 유지했어요. 지역 조건(한국)은 반영되지 않았어요."),notice);
            assertEquals(notice.indexOf("제외는 유지했어요."),notice.lastIndexOf("제외는 유지했어요."),notice);
        }finally{if(app!=null)app.close();ai.stop(0);mb.stop(0);}
    }

    private ConfigurableApplicationContext start(String ai,String mb) {
        var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url=jdbc:h2:mem:kept-exclusion-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","--spring.datasource.username=sa","--spring.datasource.password=",
            "--taptap.openai.key=check-only","--taptap.openai.model=check-model","--taptap.openai.endpoint="+ai,"--taptap.catalogue.endpoint="+mb,
            "--taptap.ai.daily-limit=100","--taptap.ai.user-hourly-limit=100","--logging.level.root=WARN","--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();return context;
    }
    private JsonNode value(String method,String path,String token,Object body,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(40)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),response.body());
        return response.body().isEmpty()?json.createObjectNode():json.readTree(response.body());
    }
    private static Map<String,Object> song(String title){return Map.of("title",title,"artist","Letter artist","tags",List.of("kpop"),"language","ko");}
    private static Map<String,Object> recording(Map<String,Object> song) {
        String artist=(String)song.get("artist");
        return Map.of("id",UUID.nameUUIDFromBytes(((String)song.get("title")).getBytes()).toString(),"title",song.get("title"),"video",false,"disambiguation","",
            "artist-credit",List.of(Map.of("name",artist,"artist",Map.of("name",artist),"joinphrase","")),"releases",List.of(Map.of("id",UUID.randomUUID().toString())));
    }
    private Map<String,Object> envelope(Object payload) {return Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(payload))))));}
    private void respond(HttpExchange exchange,int status,Object body) throws java.io.IOException {
        byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
}
