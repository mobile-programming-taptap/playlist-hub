package com.mptaptap.server;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ConversationCheckTest {
    @Test void historyKeepsExclusionsAndAiBoundary() throws Exception {
        ObjectMapper json=new ObjectMapper();List<JsonNode> sent=new CopyOnWriteArrayList<>();AtomicBoolean unavailable=new AtomicBoolean();
        AtomicReference<String> modelConditions=new AtomicReference<>("{\"tags\":[\"energetic\"],\"excludedTags\":[],\"language\":\"ko\"}");
        HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/responses",exchange->{
            sent.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body=json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of(
                "type","output_text","text",modelConditions.get()))))));
            exchange.sendResponseHeaders(unavailable.get()?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });stub.start();
        try(var app=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:conversation"+UUID.randomUUID()+";MODE=PostgreSQL",
                "--taptap.openai.key=check-only","--taptap.openai.endpoint=http://127.0.0.1:"+stub.getAddress().getPort()+"/responses",
                "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();HttpClient client=HttpClient.newHttpClient();
            var session=client.send(HttpRequest.newBuilder(URI.create(base+"/sessions")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            String token=json.readTree(session.body()).path("token").asString();
            var catalogue=client.send(HttpRequest.newBuilder(URI.create(base+"/catalogue")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            JsonNode tracks=json.readTree(catalogue.body());String keep=tracks.get(0).path("id").asString(),skip=tracks.get(1).path("id").asString();
            var request=HttpRequest.newBuilder(URI.create(base+"/recommendations")).header("Authorization","Bearer "+token).header("Content-Type","application/json");
            var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","더 신나게", "history",List.of("한국어 잔잔한 음악"),
                    "keptTrackIds",List.of(keep),"excludedTrackIds",List.of(skip))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),response.body());JsonNode result=json.readTree(response.body());
            assertEquals("openai",result.path("mode").asString());assertEquals(keep,result.path("tracks").get(0).path("track").path("id").asString());
            for(JsonNode pick:result.path("tracks"))assertNotEquals(skip,pick.path("track").path("id").asString());
            assertEquals(1,sent.size());JsonNode body=sent.get(0);assertFalse(body.path("store").asBoolean());assertEquals(2,body.path("input").size());
            assertEquals("한국어 잔잔한 음악",body.path("input").get(0).path("content").asString());assertEquals("더 신나게",body.path("input").get(1).path("content").asString());
            assertFalse(body.toString().contains(keep));assertFalse(body.toString().contains(skip));
            var invalid=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","팝","history",java.util.Collections.nCopies(9,"록"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,invalid.statusCode());assertEquals(1,sent.size());
            Map<String,Object> previous=Map.of("tags",List.of("pop","calm"),"excludedTags",List.of("rock"),"language","en");
            var continued=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","밤에도", "history",java.util.Collections.nCopies(8,"비 오는 날"),
                    "previousConditions",previous,"keptTrackIds",List.of(keep),"excludedTrackIds",List.of(skip))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,continued.statusCode(),continued.body());assertEquals(2,sent.size());
            body=sent.get(1);String instructions=body.path("instructions").asString();
            assertTrue(instructions.contains("\"language\":\"en\""));assertTrue(instructions.contains("\"tags\":[\"pop\",\"calm\"]"));
            assertTrue(instructions.contains("apply only the latest request"));assertEquals(9,body.path("input").size());
            assertEquals("밤에도",body.path("input").get(8).path("content").asString());
            assertFalse(body.toString().contains(keep));assertFalse(body.toString().contains(skip));
            for(Map<String,Object> bad:List.of(Map.<String,Object>of("tags",List.of("Spotify track title"),"excludedTags",List.of(),"language","en"),
                    Map.<String,Object>of("tags",List.of("pop"),"excludedTags",List.of(),"language","fr"))) {
                var rejected=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","팝", "previousConditions",bad)))).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(400,rejected.statusCode());assertEquals(2,sent.size(),"Unvalidated values must not reach AI");
            }
            modelConditions.set("{\"tags\":[\"energetic\"],\"excludedTags\":[],\"language\":\"\"}");
            var initial=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","신나는 음악을 추천해줘")))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,initial.statusCode(),initial.body());JsonNode first=json.readTree(initial.body());assertEquals("openai",first.path("mode").asString());
            assertTrue(first.path("tracks").size()>1);String retained=first.path("tracks").get(0).path("track").path("id").asString(),excluded=first.path("tracks").get(1).path("track").path("id").asString();
            // Valid model JSON can still omit the explicit genre, as the live Responses call did twice.
            modelConditions.set("{\"tags\":[\"energetic\"],\"excludedTags\":[\"instrumental\"],\"language\":\"en\"}");
            String refineBody=json.writeValueAsString(Map.of("prompt","영어 팝으로 바꿔줘, 연주곡은 제외","history",List.of("신나는 음악을 추천해줘"),
                    "previousConditions",first.path("conditions"),"keptTrackIds",List.of(retained),"excludedTrackIds",List.of(excluded)));
            for(int attempt=0;attempt<2;attempt++) {
                var refined=client.send(request.POST(HttpRequest.BodyPublishers.ofString(refineBody)).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,refined.statusCode(),refined.body());JsonNode guarded=json.readTree(refined.body());
                assertEquals("openai",guarded.path("mode").asString());assertEquals("en",guarded.path("conditions").path("language").asString());
                assertTrue(guarded.path("conditions").path("tags").toString().contains("pop"));assertTrue(guarded.path("conditions").path("tags").toString().contains("energetic"));
                assertEquals("[\"instrumental\"]",guarded.path("conditions").path("excludedTags").toString());
                assertEquals(retained,guarded.path("tracks").get(0).path("track").path("id").asString());
                for(JsonNode pick:guarded.path("tracks"))assertNotEquals(excluded,pick.path("track").path("id").asString());
            }
            modelConditions.set("{\"tags\":[\"warm\"],\"excludedTags\":[],\"language\":\"ko\"}");
            var semanticReset=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","처음부터 영어 팝으로, 커피 한 잔과 어울리게",
                    "previousConditions",first.path("conditions"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,semanticReset.statusCode(),semanticReset.body());JsonNode resetGuard=json.readTree(semanticReset.body());
            assertEquals("openai",resetGuard.path("mode").asString());assertEquals("en",resetGuard.path("conditions").path("language").asString());
            assertEquals("[\"warm\",\"pop\"]",resetGuard.path("conditions").path("tags").toString(),"Reset must preserve the model's new semantic mood while enforcing the explicit genre");
            modelConditions.set("{\"tags\":[\"instrumental\"],\"excludedTags\":[],\"language\":\"none\"}");
            var explicitVocals=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","연주곡 제외","previousConditions",
                    Map.of("tags",List.of("instrumental"),"excludedTags",List.of(),"language","none"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,explicitVocals.statusCode(),explicitVocals.body());JsonNode guardedVocals=json.readTree(explicitVocals.body());
            assertEquals("openai",guardedVocals.path("mode").asString());assertEquals("",guardedVocals.path("conditions").path("language").asString());
            assertEquals("[]",guardedVocals.path("conditions").path("tags").toString());assertEquals("[\"instrumental\"]",guardedVocals.path("conditions").path("excludedTags").toString());
            unavailable.set(true);
            var fallback=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","비 오는 날", "history",java.util.Collections.nCopies(8,"밤에도"),
                    "previousConditions",previous)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,fallback.statusCode(),fallback.body());JsonNode fallbackResult=json.readTree(fallback.body());
            assertEquals("basic",fallbackResult.path("mode").asString());assertEquals("en",fallbackResult.path("conditions").path("language").asString());
            assertTrue(fallbackResult.path("conditions").path("tags").toString().contains("pop"));assertTrue(fallbackResult.path("conditions").path("tags").toString().contains("calm"));
            var reset=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","처음부터 한국어 록", "previousConditions",previous)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,reset.statusCode(),reset.body());JsonNode resetConditions=json.readTree(reset.body()).path("conditions");
            assertEquals("ko",resetConditions.path("language").asString());assertEquals("[\"rock\"]",resetConditions.path("tags").toString());assertEquals("[]",resetConditions.path("excludedTags").toString());
            var vocals=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","연주곡 제외", "previousConditions",
                    Map.of("tags",List.of("instrumental"),"excludedTags",List.of(),"language","none"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,vocals.statusCode(),vocals.body());JsonNode vocalResult=json.readTree(vocals.body());
            assertEquals("",vocalResult.path("conditions").path("language").asString());assertTrue(vocalResult.path("tracks").size()>0);
            for(JsonNode pick:vocalResult.path("tracks"))assertNotEquals("none",pick.path("track").path("language").asString());
        } finally {stub.stop(0);}
    }
}
