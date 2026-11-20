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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** A short first AI round of 5 or more proposals gets exactly one supplement; fewer does not. */
class SupplementThresholdCheckTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();
    private String base;

    @Test void halfAListOrMoreGetsOneSupplementAndFewerGetsNone() throws Exception {
        var aiCalls=new AtomicInteger();
        // Each AI call takes the next planned answer; a second supplement would get the last one again.
        Queue<List<Map<String,Object>>> answers=new ConcurrentLinkedQueue<>();
        List<Map<String,Object>> lastAnswer=new CopyOnWriteArrayList<>();
        List<Map<String,Object>> rows=new CopyOnWriteArrayList<>();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{
            exchange.getRequestBody().readAllBytes();aiCalls.incrementAndGet();
            List<Map<String,Object>> next=answers.poll();if(next!=null){lastAnswer.clear();lastAnswer.addAll(next);}
            respond(exchange,200,envelope(plan(new ArrayList<>(lastAnswer))));
        });
        mb.createContext("/recording/",exchange->respond(exchange,200,Map.of("recordings",rows)));
        ai.start();mb.start();
        ConfigurableApplicationContext app=null;
        try {
            app=start("http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            String token=value("POST","/sessions",null,null,201).path("token").asString();
            for(int proposed:List.of(4,5,6)) {
                // One first-round song and one supplement song are verified, so the result stays short and a second supplement would be tempting.
                List<Map<String,Object>> first=songs("First "+proposed,proposed),extra=songs("Extra "+proposed,3);
                rows.add(recording(first.get(0)));rows.add(recording(extra.get(0)));
                answers.clear();answers.add(first);answers.add(extra);
                int before=aiCalls.get();
                JsonNode result=value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),200);
                boolean supplemented=proposed>=5;
                assertEquals(supplemented?2:1,aiCalls.get()-before,proposed+" proposals: "+(supplemented?"exactly one supplement":"no supplement"));
                assertEquals(supplemented?2:1,result.path("tracks").size(),proposed+" proposals: a supplement adds its verified song, otherwise only the first round shows");
                assertEquals("[\"jazz\"]",result.path("conditions").path("tags").toString());
            }
        }finally{if(app!=null)app.close();ai.stop(0);mb.stop(0);}
    }

    private ConfigurableApplicationContext start(String ai,String mb) {
        var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url=jdbc:h2:mem:threshold-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","--spring.datasource.username=sa","--spring.datasource.password=",
            "--taptap.openai.key=check-only","--taptap.openai.model=check-model","--taptap.openai.endpoint="+ai,"--taptap.catalogue.endpoint="+mb,
            "--taptap.ai.daily-limit=100","--taptap.ai.user-hourly-limit=100","--logging.level.root=WARN","--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();return context;
    }
    private JsonNode value(String method,String path,String token,Object body,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
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
        return Map.of("id",UUID.randomUUID().toString(),"title",song.get("title"),"video",false,"disambiguation","",
            "artist-credit",List.of(Map.of("name",artist,"artist",Map.of("name",artist),"joinphrase","")),"releases",List.of(Map.of("id",UUID.randomUUID().toString())));
    }
    private Map<String,Object> envelope(Object payload) {return Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(payload))))));}
    private void respond(HttpExchange exchange,int status,Object body) throws java.io.IOException {
        byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
}
