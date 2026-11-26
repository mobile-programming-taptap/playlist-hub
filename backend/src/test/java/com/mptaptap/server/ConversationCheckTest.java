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
        AtomicReference<String> modelConditions=new AtomicReference<>("{\"tags\":[\"energetic\"],\"excludedTags\":[],\"language\":\"ko\",\"languageSpecified\":true}");
        HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/responses",exchange->{
            sent.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body=json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of(
                "type","output_text","text",ApiCheckTest.modelPlan(json,modelConditions.get())))))));
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
            assertFalse(result.path("conditions").has("languageSpecified"),"The internal AI field must not reach the public conditions.");
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
                    Map.<String,Object>of("tags",List.of("pop"),"excludedTags",List.of(),"language","not_a_language"))) {
                var rejected=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","팝", "previousConditions",bad)))).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(400,rejected.statusCode());assertEquals(2,sent.size(),"Unvalidated values must not reach AI");
            }
            modelConditions.set("{\"tags\":[\"energetic\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false}");
            var initial=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","신나는 음악을 추천해줘")))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,initial.statusCode(),initial.body());JsonNode first=json.readTree(initial.body());assertEquals("openai",first.path("mode").asString());
            assertTrue(first.path("tracks").size()>1);String retained=first.path("tracks").get(0).path("track").path("id").asString(),excluded=first.path("tracks").get(1).path("track").path("id").asString();
            // Valid model JSON can still omit the explicit genre, as the live Responses call did twice.
            modelConditions.set("{\"tags\":[\"energetic\"],\"excludedTags\":[\"instrumental\"],\"language\":\"en\",\"languageSpecified\":true}");
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
            modelConditions.set("{\"tags\":[\"warm\"],\"excludedTags\":[],\"language\":\"ko\",\"languageSpecified\":true}");
            var semanticReset=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","처음부터 영어 팝으로, 커피 한 잔과 어울리게",
                    "previousConditions",first.path("conditions"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,semanticReset.statusCode(),semanticReset.body());JsonNode resetGuard=json.readTree(semanticReset.body());
            assertEquals("openai",resetGuard.path("mode").asString());assertEquals("en",resetGuard.path("conditions").path("language").asString());
            assertEquals("[\"warm\",\"pop\"]",resetGuard.path("conditions").path("tags").toString(),"Reset must preserve the model's new semantic mood while enforcing the explicit genre");
            modelConditions.set("{\"tags\":[\"instrumental\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":false}");
            var explicitVocals=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","연주곡 제외","previousConditions",
                    Map.of("tags",List.of("instrumental"),"excludedTags",List.of(),"language","none"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,explicitVocals.statusCode(),explicitVocals.body());JsonNode guardedVocals=json.readTree(explicitVocals.body());
            assertEquals("openai",guardedVocals.path("mode").asString());assertEquals("",guardedVocals.path("conditions").path("language").asString());
            assertEquals("[]",guardedVocals.path("conditions").path("tags").toString());assertEquals("[\"instrumental\"]",guardedVocals.path("conditions").path("excludedTags").toString());
            // 참고 곡에서 추론한 발라드는 선호로만 남고, 명시한 잔잔한 제외와 겹치는 추론은 버린다.
            modelConditions.set("{\"tags\":[],\"excludedTags\":[\"calm\"],\"language\":\"\",\"languageSpecified\":false,\"inferredTags\":[\"ballad\",\"calm\"]}");
            var reference=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","밤편지 같은 느낌으로 바꿔줘","previousConditions",
                    Map.of("tags",List.of(),"excludedTags",List.of("calm"),"language",""))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,reference.statusCode(),reference.body());JsonNode referenced=json.readTree(reference.body()).path("conditions");
            assertTrue(sent.get(sent.size()-1).path("instructions").asString().contains("style reference"));
            assertEquals("[\"ballad\"]",referenced.path("tags").toString(),"A song name must not add night");
            assertEquals("[\"ballad\"]",referenced.path("inferredTags").toString());assertEquals("[]",referenced.path("hardTags").toString());
            assertEquals("[\"calm\"]",referenced.path("excludedTags").toString());
            // 다음 턴에 AI가 추론 값을 tags 로 옮겨도 사용자가 직접 말하지 않았으면 필수가 되지 않는다.
            modelConditions.set("{\"tags\":[\"ballad\",\"warm\"],\"excludedTags\":[\"calm\"],\"language\":\"\",\"languageSpecified\":false,\"inferredTags\":[]}");
            var followUp=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","커피 한 잔과 어울리게","previousConditions",referenced)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,followUp.statusCode(),followUp.body());JsonNode followed=json.readTree(followUp.body()).path("conditions");
            assertEquals("[\"ballad\"]",followed.path("inferredTags").toString());assertEquals("[]",followed.path("hardTags").toString());
            // 지역은 거르지 않고 안내만 붙인다. AI 응답에 지역이 없어도 사전 규칙으로 표시하고 이어받는다.
            String regionNotice="지역 조건(한국)은 반영되지 않았어요.";
            modelConditions.set("{\"tags\":[\"ballad\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false}");
            var regional=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","한국 발라드")))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,regional.statusCode(),regional.body());JsonNode regionalResult=json.readTree(regional.body());
            assertEquals("KR",regionalResult.path("conditions").path("origin").asString());assertEquals("",regionalResult.path("conditions").path("language").asString());
            assertTrue(regionalResult.path("notice").asString().endsWith(regionNotice),regionalResult.path("notice").asString());
            assertFalse(sent.get(sent.size()-1).path("text").path("format").path("schema").path("properties").has("origin"),"Region is not an AI field");
            // 후보가 없어 7곡 미만 안내로 덮어써도 지역 문구는 남는다.
            modelConditions.set("{\"tags\":[],\"excludedTags\":[],\"language\":\"fr\",\"languageSpecified\":true}");
            var overwritten=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","한국 프랑스어 노래")))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,overwritten.statusCode(),overwritten.body());String overwrittenNotice=json.readTree(overwritten.body()).path("notice").asString();
            assertTrue(overwrittenNotice.startsWith("AI가 확실한 곡 후보를")&&overwrittenNotice.endsWith(regionNotice),overwrittenNotice);
            // 사전이 못 읽는 다음 요청(AI 경로)에서도 이전 지역 표시를 이어받는다.
            modelConditions.set("{\"tags\":[\"ballad\",\"warm\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false}");
            var carried=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","커피 한 잔과 어울리게","previousConditions",regionalResult.path("conditions"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,carried.statusCode(),carried.body());JsonNode carriedResult=json.readTree(carried.body());
            assertEquals("KR",carriedResult.path("conditions").path("origin").asString());assertTrue(carriedResult.path("notice").asString().endsWith(regionNotice));
            var released=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","지역 상관없이","previousConditions",carriedResult.path("conditions"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,released.statusCode(),released.body());JsonNode releasedResult=json.readTree(released.body());
            assertEquals("",releasedResult.path("conditions").path("origin").asString());assertFalse(releasedResult.path("notice").asString().contains("지역 조건("));
            for(String invalidLanguageFlag:List.of("{\"tags\":[\"calm\"],\"excludedTags\":[],\"language\":\"\"}",
                    "{\"tags\":[\"calm\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":\"false\"}",
                    "{\"tags\":[\"calm\"],\"excludedTags\":[],\"language\":\"not_a_language\",\"languageSpecified\":false}")) {
                modelConditions.set(invalidLanguageFlag);
                var malformed=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","잔잔한 음악")))).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,malformed.statusCode(),malformed.body());JsonNode rejected=json.readTree(malformed.body());
                assertFalse(rejected.has("tracks"));assertTrue(rejected.path("message").asString().contains("AI 연결"));
            }
            unavailable.set(true);
            var fallback=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","비 오는 날", "history",java.util.Collections.nCopies(8,"밤에도"),
                    "previousConditions",previous)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(503,fallback.statusCode(),fallback.body());assertFalse(json.readTree(fallback.body()).has("tracks"));
            var reset=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","처음부터 한국어 록", "previousConditions",previous)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(503,reset.statusCode(),reset.body());assertFalse(json.readTree(reset.body()).has("tracks"));
            var vocals=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prompt","연주곡 제외", "previousConditions",
                    Map.of("tags",List.of("instrumental"),"excludedTags",List.of(),"language","none"))))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(503,vocals.statusCode(),vocals.body());assertFalse(json.readTree(vocals.body()).has("tracks"));
        } finally {stub.stop(0);}
    }

    /** 참고 곡에서 추론한 사전 장르는 AI가 tags 로 내도 선호로 내리고, 직접 말한 장르는 필수로 남긴다. */
    @Test void referenceGenresStayPreferencesUnlessDirect() throws Exception {
        ObjectMapper json=new ObjectMapper();
        AtomicReference<String> modelConditions=new AtomicReference<>();
        HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/responses",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] body=json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of(
                "type","output_text","text",ApiCheckTest.modelPlan(json,modelConditions.get())))))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });stub.start();
        try(var app=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:reference"+UUID.randomUUID()+";MODE=PostgreSQL",
                "--taptap.openai.key=check-only","--taptap.openai.endpoint=http://127.0.0.1:"+stub.getAddress().getPort()+"/responses",
                "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();HttpClient client=HttpClient.newHttpClient();
            String first="한국어 가사 노래로 찾아줘 잔잔한 건 싫어";
            Map<String,Object> afterFirst=Map.of("tags",List.of(),"excludedTags",List.of("calm"),"language","ko");
            java.util.function.BiFunction<String,Map<String,Object>,JsonNode> ask=(ai,body)->{
                try {
                    modelConditions.set(ai);
                    var session=client.send(HttpRequest.newBuilder(URI.create(base+"/sessions")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                    var response=client.send(HttpRequest.newBuilder(URI.create(base+"/recommendations")).header("Authorization","Bearer "+json.readTree(session.body()).path("token").asString())
                            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(200,response.statusCode(),response.body());
                    return json.readTree(response.body()).path("conditions");
                } catch(Exception e){throw new RuntimeException(e);}
            };
            java.util.function.Function<JsonNode,List<String>> sorted=node->{List<String> values=new java.util.ArrayList<>();node.forEach(value->values.add(value.asString()));java.util.Collections.sort(values);return values;};

            // 1: 실측 1회차. 참고 곡 턴에서 AI가 추론 장르를 tags 에만 넣어도 선호가 된다.
            JsonNode reference=ask.apply("{\"tags\":[\"ballad\"],\"excludedTags\":[\"calm\"],\"language\":\"ko\",\"languageSpecified\":true,\"inferredTags\":[]}",
                    Map.of("prompt","밤편지 같은 느낌으로 바꿔줘","history",List.of(first),"previousConditions",afterFirst));
            assertEquals("[]",reference.path("hardTags").toString());assertEquals("[\"ballad\"]",reference.path("softTags").toString());
            assertEquals("[\"ballad\"]",reference.path("inferredTags").toString());
            // 2: 직접 말한 장르는 필수로 남는다.
            JsonNode direct=ask.apply("{\"tags\":[\"ballad\"],\"excludedTags\":[\"calm\"],\"language\":\"ko\",\"languageSpecified\":true,\"inferredTags\":[]}",
                    Map.of("prompt","발라드로 바꿔줘","history",List.of(first),"previousConditions",afterFirst));
            assertEquals("[\"ballad\"]",direct.path("hardTags").toString());
            // 3: 참고 곡과 함께 직접 말한 장르도 필수다.
            JsonNode mixed=ask.apply("{\"tags\":[\"ballad\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false,\"inferredTags\":[]}",
                    Map.of("prompt","밤편지 같은 발라드"));
            assertEquals("[\"ballad\"]",mixed.path("hardTags").toString());
            // 3′: AI가 직접 말한 장르를 inferredTags 에도 넣으면 추론에서 빠진다(Conversation 의 직접 태그 규칙).
            JsonNode both=ask.apply("{\"tags\":[\"ballad\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false,\"inferredTags\":[\"ballad\"]}",
                    Map.of("prompt","밤편지 같은 발라드"));
            assertEquals("[\"ballad\"]",both.path("hardTags").toString());assertEquals("[]",both.path("inferredTags").toString());
            // 4: 참고 곡이 없는 문장은 후처리가 건드리지 않는다.
            JsonNode noReference=ask.apply("{\"tags\":[\"pop\"],\"excludedTags\":[\"calm\"],\"language\":\"ko\",\"languageSpecified\":true,\"inferredTags\":[]}",
                    Map.of("prompt",first));
            assertEquals("[\"pop\"]",noReference.path("hardTags").toString());assertEquals("[]",noReference.path("inferredTags").toString());
            // 5: 직접 말한 연주곡·피아노는 그대로 필수다.
            JsonNode study=ask.apply("{\"tags\":[\"study\",\"piano\",\"instrumental\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":true,\"inferredTags\":[]}",
                    Map.of("prompt","공부할 때 들을 거 가사 없는 피아노곡"));
            assertEquals(List.of("instrumental","piano"),sorted.apply(study.path("hardTags")));assertEquals("none",study.path("language").asString());
            // 6: 동적 장르는 사전이 읽지 못해도 옮기지 않는다.
            JsonNode dynamic=ask.apply("{\"tags\":[\"genre:indie_folk\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false,\"inferredTags\":[]}",
                    Map.of("prompt","밤편지 같은 인디 포크"));
            assertEquals("[\"genre:indie_folk\"]",dynamic.path("hardTags").toString());
            // 7: 이전 턴에 직접 말한 장르를 AI가 이번 추론에 넣어도 필수로 남는다.
            JsonNode earlier=ask.apply("{\"tags\":[\"ballad\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false,\"inferredTags\":[\"ballad\"]}",
                    Map.of("prompt","밤편지 같은 느낌으로 바꿔줘","history",List.of("발라드로 찾아줘"),"previousConditions",Map.of("tags",List.of("ballad"),"excludedTags",List.of(),"language","")));
            assertEquals("[\"ballad\"]",earlier.path("hardTags").toString());assertEquals("[]",earlier.path("inferredTags").toString());
        } finally {stub.stop(0);}
    }
}
