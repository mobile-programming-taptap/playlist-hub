package com.mptaptap.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** The first AI round recovers once from a timeout before the request becomes an error screen. */
class AiPlanRecoveryCheckTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();
    private String base;

    @Test void timedOutFirstPlanIsRetriedOnceWithinTheRequestDeadline() throws Exception {
        var aiCalls=new AtomicInteger();
        // Calls in this set stall past the 15 second first-plan limit.
        Set<Integer> stalled=new CopyOnWriteArraySet<>(List.of(1));
        List<Map<String,Object>> songs=songs("Retry",2);
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        // A stalled answer must not hold back the retry, so each AI call gets its own thread.
        ai.setExecutor(Executors.newCachedThreadPool(task->{Thread thread=new Thread(task);thread.setDaemon(true);return thread;}));
        ai.createContext("/responses",exchange->{
            exchange.getRequestBody().readAllBytes();
            if(stalled.contains(aiCalls.incrementAndGet()))try{Thread.sleep(16_000);}catch(InterruptedException error){Thread.currentThread().interrupt();}
            try{respond(exchange,200,envelope(plan(songs)));}catch(java.io.IOException closed){exchange.close();}
        });
        mb.createContext("/recording/",exchange->respond(exchange,200,Map.of("recordings",songs.stream().map(AiPlanRecoveryCheckTest::recording).toList())));
        ai.start();mb.start();
        ConfigurableApplicationContext app=null;
        try {
            app=start("http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            String token=value("POST","/sessions",null,null,201).path("token").asString();
            long started=System.nanoTime();
            JsonNode recovered=value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),200);
            assertEquals(2,aiCalls.get(),"A timed-out first plan is asked once more");
            assertEquals(2,recovered.path("tracks").size(),"The retried plan is verified like a first plan");
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(30))<0,"The retry stays inside the app deadline");

            stalled.addAll(List.of(3,4));
            value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),503);
            assertEquals(4,aiCalls.get(),"A second timeout fails without a third AI call");
        }finally{if(app!=null)app.close();ai.stop(0);mb.stop(0);}
    }

    @Test void moreThanTwelveProposalsAreCutToTheFirstTwelveInsteadOfFailing() throws Exception {
        List<Map<String,Object>> songs=songs("Many",13);
        List<String> queries=new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{exchange.getRequestBody().readAllBytes();respond(exchange,200,envelope(plan(songs)));});
        mb.createContext("/recording/",exchange->{
            queries.add(java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),java.nio.charset.StandardCharsets.UTF_8));
            respond(exchange,200,Map.of("recordings",songs.stream().map(AiPlanRecoveryCheckTest::recording).toList()));
        });
        ai.start();mb.start();
        ConfigurableApplicationContext app=null;
        try {
            app=start("http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            String token=value("POST","/sessions",null,null,201).path("token").asString();
            JsonNode result=value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),200);
            assertEquals(8,result.path("tracks").size());
            assertEquals(1,queries.size());
            assertTrue(queries.get(0).contains("Many song 11"),"The twelfth proposal is still looked up");
            assertFalse(queries.get(0).contains("Many song 12"),"The thirteenth proposal is left out");
        }finally{if(app!=null)app.close();ai.stop(0);mb.stop(0);}
    }

    private ConfigurableApplicationContext start(String ai,String mb) {
        var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url=jdbc:h2:mem:plan-recovery-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","--spring.datasource.username=sa","--spring.datasource.password=",
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
    private static List<Map<String,Object>> songs(String prefix,int count) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(int i=0;i<count;i++)result.add(Map.of("title",prefix+" song "+i,"artist",prefix+" artist "+i,"tags",List.of("jazz"),"language","en"));
        return result;
    }
    private static Map<String,Object> plan(List<Map<String,Object>> songs) {return Map.of("tags",List.of("jazz"),"excludedTags",List.of(),"language","","languageSpecified",false,"excludedArtists",List.of(),"candidates",songs);}
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
