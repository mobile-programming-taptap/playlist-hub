package com.mptaptap.server;

import com.sun.net.httpserver.HttpServer;
import com.mptaptap.core.Conversation;
import com.mptaptap.core.Recommender;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ApiCheckTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if (token != null) request.header("Authorization","Bearer "+token);
        request.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode value(String method,String path,String token,Object body,int status) throws Exception {
        HttpResponse<String> response=request(method,path,token,body);
        assertEquals(status,response.statusCode(),response.body());
        return response.body().isEmpty()?json.createObjectNode():json.readTree(response.body());
    }

    static String modelPlan(ObjectMapper json,String conditions) throws java.io.IOException {
        var plan=(tools.jackson.databind.node.ObjectNode)json.readTree(conditions);
        if (!plan.has("excludedArtists")) plan.putArray("excludedArtists");
        var candidates=plan.putArray("candidates");
        try(var input=new org.springframework.core.io.ClassPathResource("tracks.json").getInputStream()) {
            for(JsonNode track:json.readTree(input)) {
                if(candidates.size()==12)break;
                String language=plan.path("language").asString();
                if(!language.isEmpty()&&!language.equals(track.path("language").asString()))continue;
                boolean matches=true;
                for(JsonNode tag:plan.path("tags"))if(Recommender.GENRES.contains(tag.asString())&&!track.path("tags").toString().contains("\""+tag.asString()+"\""))matches=false;
                for(JsonNode tag:plan.path("excludedTags"))if(track.path("tags").toString().contains("\""+tag.asString()+"\""))matches=false;
                if(!matches)continue;
                var candidate=candidates.addObject();
                for(String field:List.of("title","artist","tags","language"))candidate.set(field,track.path(field));
            }
        }
        return json.writeValueAsString(plan);
    }

    @Test void missingAiNeverReturnsFixedTracks() throws Exception {
        try(var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:noai"+UUID.randomUUID()+";MODE=PostgreSQL","--taptap.openai.key=",
                "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
            String token=value("POST","/sessions",null,null,201).path("token").asString();
            assertEquals(40,value("GET","/catalogue",token,null,200).size());
            JsonNode rejected=value("POST","/recommendations",token,Map.of("prompt","즐거운 제이팝"),503);
            assertFalse(rejected.has("tracks"));assertTrue(rejected.path("message").asString().contains("AI 추천이 연결되지"));
        }
    }

    @Test void aiLimitsRespectPreciseInstants() {
        try(ConfigurableApplicationContext context=start("jdbc:h2:mem:clock"+UUID.randomUUID()+";MODE=PostgreSQL","http://127.0.0.1:1/v1/responses")) {
            var db=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
            Store capped=new Store(db,manager,json,2,1);
            String first=capped.register().get("userId"), second=capped.register().get("userId"), third=capped.register().get("userId");
            db.update("INSERT INTO ai_usage VALUES (?,?,?)",UUID.randomUUID().toString(),first,"2026-10-03T00:00:00.001Z");
            Instant justAfterMidnight=Instant.parse("2026-10-03T00:00:01Z");
            assertFalse(capped.reserveAi(first,justAfterMidnight),"An existing call counts toward the user cap.");
            assertTrue(capped.reserveAi(second,justAfterMidnight));
            assertFalse(capped.reserveAi(third,justAfterMidnight),"A fractional first-second call counts toward the UTC daily cap.");

            Store hourly=new Store(db,manager,json,10,1);
            db.update("DELETE FROM ai_usage");
            db.update("INSERT INTO ai_usage VALUES (?,?,?)",UUID.randomUUID().toString(),first,"2026-10-03T09:00:00.123456Z");
            assertFalse(hourly.reserveAi(first,Instant.parse("2026-10-03T10:00:00.123Z")),"A later instant stays inside the hour even with more decimal places.");
            db.update("DELETE FROM ai_usage");
            db.update("INSERT INTO ai_usage VALUES (?,?,?)",UUID.randomUUID().toString(),first,"2026-10-03T09:00:00.123Z");
            assertTrue(hourly.reserveAi(first,Instant.parse("2026-10-03T10:00:00.123456Z")),"An earlier instant expires even with fewer decimal places.");
        }
    }

    @Test void recommendationOwnershipStorageAndOpenAiContract() throws Exception {
        List<JsonNode> aiRequests=new CopyOnWriteArrayList<>();
        String mixedUnknown="Gregorian chant only, no Dhrupad";
        String genreComplement=mixedUnknown+".",explicitPiano=genreComplement+" no piano",resetComplement="처음부터 "+explicitPiano;
        List<String> explicitNegativeComplements=List.of("exclude","avoid","without").stream().map(cue->genreComplement+" "+cue+" piano").toList();
        List<String> supportedGenres=Recommender.GENRES.stream().filter(tag->!tag.equals("unsupported_genre")).toList();
        String explicitGenres=mixedUnknown+"; "+supportedGenres.stream().map(tag->"no "+Recommender.LABELS.get(tag)).collect(java.util.stream.Collectors.joining(", "));
        List<String> actualComplement=Recommender.LABELS.keySet().stream().filter(tag->!List.of("unsupported_genre","calm").contains(tag)).toList();
        HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/v1/responses",exchange->{
            JsonNode incoming=json.readTree(exchange.getRequestBody().readAllBytes());aiRequests.add(incoming);
            JsonNode input=incoming.path("input");
            String latest=input.isArray()?input.get(input.size()-1).path("content").asString():input.asString();
            String conditions="{\"tags\":[\"calm\",\"piano\",\"instrumental\",\"study\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":true}";
            if(latest.equals("잘못된 AI 응답 검사"))conditions="{\"tags\":[\"invented\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":true}";
            if(latest.equals("팝 음악과 Gregorian chant"))conditions="{\"tags\":[\"pop\",\"unsupported_genre\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":false}";
            if(latest.equals("Gregorian chant 제외하고 피아노"))conditions="{\"tags\":[\"piano\"],\"excludedTags\":[\"unsupported_genre\"],\"language\":\"\",\"languageSpecified\":false}";
            if(List.of("팝","팝으로 바꿔줘","처음부터 팝").contains(latest))conditions="{\"tags\":[\"pop\",\"unsupported_genre\"],\"excludedTags\":[],\"language\":\"ko\",\"languageSpecified\":false}";
            if(latest.equals("신나는 음악을 추천해줘"))conditions="{\"tags\":[\"energetic\"],\"excludedTags\":[],\"language\":\"ko\",\"languageSpecified\":false}";
            if(latest.equals("잔잔한 곡은 제외"))conditions="{\"tags\":[],\"excludedTags\":[\"calm\"],\"language\":\"\",\"languageSpecified\":false}";
            if(latest.equals("미국 팝"))conditions="{\"tags\":[\"pop\"],\"excludedTags\":[],\"language\":\"en\",\"languageSpecified\":true}";
            if(List.of("Gregorian chant","처음부터 Gregorian chant").contains(latest))conditions="{\"tags\":[\"unsupported_genre\",\"instrumental\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":false}";
            if(latest.equals("Dhrupad"))conditions="{\"tags\":[\"unsupported_genre\"],\"excludedTags\":[],\"language\":\"ko\",\"languageSpecified\":false}";
            if(List.of("Gregorian chant only","Dhrupad only","처음부터 Gregorian chant only","Gregorian chant no vocals","가사 없는 Gregorian chant").contains(latest))
                conditions="{\"tags\":[\"instrumental\"],\"excludedTags\":[\"unsupported_genre\"],\"language\":\"none\",\"languageSpecified\":false}";
            if(List.of("no Gregorian chant, piano","not Gregorian chant, piano","piano without Gregorian chant","exclude Gregorian chant, piano","avoid Gregorian chant, piano","piano except Gregorian chant","I dislike Gregorian chant, piano","I hate Gregorian chant, piano","don't recommend Gregorian chant, piano").contains(latest))
                conditions="{\"tags\":[\"piano\"],\"excludedTags\":[\"unsupported_genre\"],\"language\":\"\",\"languageSpecified\":false}";
            if(latest.equals("Gregorian chant only; no piano"))conditions="{\"tags\":[\"unsupported_genre\"],\"excludedTags\":[\"piano\"],\"language\":\"\",\"languageSpecified\":false}";
            if(latest.equals(mixedUnknown))conditions="{\"tags\":[\"unsupported_genre\"],\"excludedTags\":[\"unsupported_genre\"],\"language\":\"\",\"languageSpecified\":false}";
            if(latest.equals(mixedUnknown+" invalid language"))conditions="{\"tags\":[\"unsupported_genre\"],\"excludedTags\":[\"unsupported_genre\"],\"language\":\"not_a_language\",\"languageSpecified\":false}";
            if(latest.equals(mixedUnknown+" invalid tag"))conditions="{\"tags\":[\"unsupported_genre\",\"invented\"],\"excludedTags\":[\"unsupported_genre\"],\"language\":\"\",\"languageSpecified\":false}";
            if(List.of(genreComplement,explicitPiano,resetComplement,explicitGenres,genreComplement+" invalid language",genreComplement+" invalid tag").contains(latest)
                    ||explicitNegativeComplements.contains(latest)||explicitNegativeComplements.stream().anyMatch(p->latest.equals("처음부터 "+p)))
                conditions=json.writeValueAsString(Map.of("tags",latest.endsWith(" invalid tag")?List.of("unsupported_genre","invented"):List.of("unsupported_genre"),"excludedTags",actualComplement,"language",latest.endsWith(" invalid language")?"not_a_language":"","languageSpecified",false));
            if(latest.equals("영어 팝"))conditions="{\"tags\":[\"pop\"],\"excludedTags\":[],\"language\":\"ko\",\"languageSpecified\":false}";
            if(latest.equals("가사 없는 재즈"))conditions="{\"tags\":[\"jazz\",\"instrumental\"],\"excludedTags\":[],\"language\":\"none\",\"languageSpecified\":false}";
            if(input.isArray()&&latest.equals("팝으로 바꿔줘")) {
                String first=input.get(0).path("content").asString();
                if(first.equals("영어 피아노"))conditions="{\"tags\":[\"pop\",\"unsupported_genre\"],\"excludedTags\":[],\"language\":\"en\",\"languageSpecified\":true}";
                if(first.equals("미국 음악"))conditions="{\"tags\":[\"pop\"],\"excludedTags\":[],\"language\":\"en\",\"languageSpecified\":true}";
                if(first.equals("cozy music"))conditions="{\"tags\":[\"warm\",\"pop\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false}";
                if(first.equals("영어 음악")&&input.get(input.size()-2).path("content").asString().contains("Mandarin"))
                    conditions="{\"tags\":[\"pop\"],\"excludedTags\":[],\"language\":\"zh\",\"languageSpecified\":true}";
                if(first.equals(genreComplement)||explicitNegativeComplements.contains(first))conditions=json.writeValueAsString(Map.of("tags",List.of("pop","unsupported_genre"),"excludedTags",actualComplement,"language","","languageSpecified",false));
            }
            if(input.isArray()&&latest.equals("신나는 음악을 추천해줘")&&input.get(0).path("content").asString().equals("cozy music"))
                conditions="{\"tags\":[\"warm\",\"pop\",\"energetic\"],\"excludedTags\":[],\"language\":\"\",\"languageSpecified\":false}";
            if(incoming.path("text").path("format").path("schema").path("properties").has("candidates"))conditions=modelPlan(json,conditions);
            else {
                var conditionOnly = (tools.jackson.databind.node.ObjectNode) json.readTree(conditions);
                conditionOnly.putArray("excludedArtists");
                conditions = json.writeValueAsString(conditionOnly);
            }
            byte[] reply=json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",conditions))))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });stub.start();
        String database=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_URL","jdbc:h2:mem:check"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String endpoint="http://127.0.0.1:"+stub.getAddress().getPort()+"/v1/responses";
        String token;
        String playlist;
        String saved;
        Map<String,String> creation;
        try {
            try(ConfigurableApplicationContext context=start(database,endpoint)) {
                base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
                assertEquals(0,context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM users",Integer.class),"Use an empty, dedicated test database.");
                assertEquals(40,value("GET","/health",null,null,200).path("catalogueSize").asInt());
                value("GET","/playlists",null,null,401);
                token=value("POST","/sessions",null,null,201).path("token").asString();
                String other=value("POST","/sessions",null,null,201).path("token").asString();
                JsonNode found=value("GET","/catalogue?q=blackpink",token,null,200);
                assertTrue(found.size()>0); for(JsonNode t:found)assertTrue(t.path("artist").asString().toLowerCase().contains("blackpink"));
                assertEquals(0,value("GET","/catalogue?q=no-such-track",token,null,200).size());
                value("GET","/catalogue?q="+"x".repeat(101),token,null,400);
                value("POST","/recommendations",token,Map.of("prompt"," "),400);
                JsonNode recovered=value("POST","/recommendations",token,Map.of("prompt","잘못된 AI 응답 검사"),503);
                assertFalse(recovered.has("tracks"));
                assertTrue(recovered.path("message").asString().contains("AI 연결"));
                String prompt="공부할 때 가사 없는 잔잔한 피아노 음악";
                JsonNode result=value("POST","/recommendations",token,Map.of("prompt",prompt),200);
                assertEquals("openai",result.path("mode").asString());assertEquals(8,result.path("tracks").size());
                assertFalse(result.path("conditions").has("languageSpecified"));
                for(JsonNode pick:result.path("tracks"))assertEquals("none",pick.path("track").path("language").asString());
                String dislike=result.path("tracks").get(0).path("track").path("id").asString();
                saved=result.path("tracks").get(1).path("track").path("id").asString();
                value("PUT","/me/feedback/"+dislike,token,Map.of("reaction",-1),200);
                value("PUT","/me/feedback/"+dislike,token,Map.of("reaction",-1),200);
                assertEquals(1,value("GET","/me/feedback",token,null,200).size());
                assertEquals(0,value("GET","/me/feedback",other,null,200).size());
                result=value("POST","/recommendations",token,Map.of("prompt",prompt),200);
                assertEquals("openai",result.path("mode").asString());
                for(JsonNode pick:result.path("tracks"))assertNotEquals(dislike,pick.path("track").path("id").asString());
                assertFalse(value("POST","/recommendations",token,Map.of("prompt",prompt),429).has("tracks"));
                assertEquals(3,aiRequests.size(),"The daily cap must include failed calls and prevent extra calls.");
                for(JsonNode body:aiRequests){
                    assertTrue(List.of(prompt,"잘못된 AI 응답 검사").contains(body.path("input").asString()));assertFalse(body.path("store").asBoolean());
                    assertEquals("json_schema",body.path("text").path("format").path("type").asString());
                    assertTrue(body.path("text").path("format").path("strict").asBoolean());
                    assertFalse(body.toString().contains(saved),"Catalogue and external metadata must not enter the AI request.");
                    JsonNode tags=body.path("text").path("format").path("schema").path("properties").path("tags").path("items").path("pattern");
                    assertTrue(tags.asString().contains("jazz"));assertFalse(tags.asString().contains("unsupported_genre"));
                    assertTrue(body.path("instructions").asString().contains("never replace it with a different genre"));
                    assertTrue(body.path("text").path("format").path("schema").path("properties").path("language").path("description").asString().contains("Never infer"));
                    assertEquals("boolean",body.path("text").path("format").path("schema").path("properties").path("languageSpecified").path("type").asString());
                    assertTrue(body.path("text").path("format").path("schema").path("required").toString().contains("languageSpecified"));
                }
                JsonNode limited=value("POST","/recommendations",token,Map.of("prompt","가사 없는 재즈"),429);
                assertFalse(limited.has("tracks"));assertTrue(limited.path("message").asString().contains("한도"));assertEquals(3,aiRequests.size());
                OpenAi ai=context.getBean(OpenAi.class);
                var catalogue=context.getBean(Api.class).catalogue("Bearer "+token,"");
                Recommender.Conditions jazz=ai.extract("재즈만 추천해줘");
                assertTrue(jazz.tags.contains("jazz"));assertFalse(jazz.tags.contains("piano"),"An omitted explicit genre must override the model's different genre.");
                assertEquals(0,Recommender.recommend(catalogue,jazz,Map.of(),"openai").tracks.size());
                Recommender.Conditions unlisted=ai.extract("팝 음악과 Gregorian chant");
                assertTrue(unlisted.tags.containsAll(List.of("pop","unsupported_genre")));
                assertTrue(unlisted.language.isEmpty());
                assertEquals(0,Recommender.recommend(catalogue,unlisted,Map.of(),"openai").tracks.size());
                Recommender.Conditions chant=ai.extract("Gregorian chant");
                assertEquals(List.of("unsupported_genre"),chant.tags);assertTrue(chant.language.isEmpty());
                assertEquals(0,Recommender.recommend(catalogue,chant,Map.of(),"openai").tracks.size());
                assertFalse(json.writeValueAsString(chant).contains("languageSpecified"));
                Recommender.Conditions dhrupad=ai.extract("Dhrupad");
                assertEquals(List.of("unsupported_genre"),dhrupad.tags);assertTrue(dhrupad.language.isEmpty());
                Recommender.Conditions explicitNoVocals=ai.extract("가사 없는 재즈");
                assertEquals("none",explicitNoVocals.language);assertTrue(explicitNoVocals.tags.containsAll(List.of("jazz","instrumental")));
                Recommender.Conditions explicitEnglish=ai.extract("영어 팝");
                assertEquals("en",explicitEnglish.language);assertEquals(List.of("pop"),explicitEnglish.tags);
                Recommender.Conditions excluded=ai.extract("Gregorian chant 제외하고 피아노");
                assertTrue(excluded.excludedTags.contains("unsupported_genre"));assertEquals(8,Recommender.recommend(catalogue,excluded,Map.of(),"openai").tracks.size());
                for(String positive:List.of("Gregorian chant only","Dhrupad only")) {
                    Recommender.Conditions corrected=ai.extract(positive);
                    assertEquals(List.of("unsupported_genre"),corrected.tags);assertTrue(corrected.excludedTags.isEmpty()&&corrected.language.isEmpty());
                    Recommender.Result zero=Recommender.recommend(catalogue,corrected,Map.of(),"openai");
                    assertEquals(0,zero.tracks.size());assertTrue(zero.notice.contains("장르"));
                }
                for(String noVocals:List.of("Gregorian chant no vocals","가사 없는 Gregorian chant")) {
                    Recommender.Conditions corrected=ai.extract(noVocals);
                    assertTrue(corrected.tags.containsAll(List.of("unsupported_genre","instrumental"))&&corrected.excludedTags.isEmpty());
                    assertEquals("none",corrected.language);assertEquals(0,Recommender.recommend(catalogue,corrected,Map.of(),"openai").tracks.size());
                }
                for(String negative:List.of("no Gregorian chant, piano","not Gregorian chant, piano","piano without Gregorian chant","exclude Gregorian chant, piano","avoid Gregorian chant, piano","piano except Gregorian chant","I dislike Gregorian chant, piano","I hate Gregorian chant, piano","don't recommend Gregorian chant, piano")) {
                    Recommender.Conditions keptExclusion=ai.extract(negative);
                    assertEquals(List.of("unsupported_genre"),keptExclusion.excludedTags);assertFalse(keptExclusion.tags.contains("unsupported_genre"));
                    assertEquals(8,Recommender.recommend(catalogue,keptExclusion,Map.of(),"openai").tracks.size());
                }
                Recommender.Conditions inheritedExclusion=ai.extract("Gregorian chant only",List.of(),excluded);
                assertEquals(List.of("unsupported_genre"),inheritedExclusion.excludedTags);assertFalse(inheritedExclusion.tags.contains("unsupported_genre"));
                Recommender.Conditions resetExclusion=ai.extract("처음부터 Gregorian chant only",List.of(),excluded);
                assertEquals(List.of("unsupported_genre"),resetExclusion.tags);assertTrue(resetExclusion.excludedTags.isEmpty());
                Recommender.Conditions historyExclusion=ai.extract("Gregorian chant only",List.of("Gregorian chant 제외하고 피아노"));
                assertEquals(List.of("unsupported_genre"),historyExclusion.excludedTags);assertFalse(historyExclusion.tags.contains("unsupported_genre"));
                Recommender.Conditions resetHistoryExclusion=ai.extract("처음부터 Gregorian chant only",List.of("Gregorian chant 제외하고 피아노"));
                assertEquals(List.of("unsupported_genre"),resetHistoryExclusion.tags);assertTrue(resetHistoryExclusion.excludedTags.isEmpty());
                Recommender.Conditions afterHistoryReset=ai.extract("Gregorian chant only",List.of("Gregorian chant 제외하고 피아노","처음부터 팝"));
                assertEquals(List.of("unsupported_genre"),afterHistoryReset.tags);assertTrue(afterHistoryReset.excludedTags.isEmpty());
                Recommender.Conditions currentSnapshot=ai.extract("Gregorian chant only",List.of("Gregorian chant 제외하고 피아노"),unlisted);
                assertEquals(List.of("unsupported_genre"),currentSnapshot.tags);assertTrue(currentSnapshot.excludedTags.isEmpty(),"Historical exclusion cues must not override the current snapshot.");
                Recommender.Conditions positiveWithOtherExclusion=ai.extract("Gregorian chant only; no piano");
                assertEquals(List.of("unsupported_genre"),positiveWithOtherExclusion.tags);assertEquals(List.of("piano"),positiveWithOtherExclusion.excludedTags);
                assertEquals(0,Recommender.recommend(catalogue,positiveWithOtherExclusion,Map.of(),"openai").tracks.size());
                Recommender.Conditions mixed=ai.extract(mixedUnknown);
                assertEquals(List.of("unsupported_genre"),mixed.tags);assertTrue(mixed.excludedTags.isEmpty()&&mixed.language.isEmpty());
                Recommender.Result mixedResult=Recommender.recommend(catalogue,mixed,Map.of(),"openai");
                assertEquals(0,mixedResult.tracks.size());assertTrue(mixedResult.notice.contains("장르"));
                String keptId=catalogue.get(0).id;
                Recommender.Result mixedKept=Conversation.recommend(catalogue,mixed,Map.of(),"openai",List.of(keptId),List.of());
                assertEquals(1,mixedKept.tracks.size());assertEquals(keptId,mixedKept.tracks.get(0).track.id);
                for(String invalid:List.of(" invalid language"," invalid tag"))
                    assertThrows(IllegalArgumentException.class,()->ai.extract(mixedUnknown+invalid),"Marker recovery must not bypass raw validation.");
                for(Recommender.Conditions recoveredMixed:List.of(ai.extract("팝으로 바꿔줘",List.of(),mixed),ai.extract("팝으로 바꿔줘",List.of(mixedUnknown)))) {
                    assertEquals(List.of("pop"),recoveredMixed.tags);assertTrue(recoveredMixed.language.isEmpty());
                    assertFalse(Recommender.recommend(catalogue,recoveredMixed,Map.of(),"openai").tracks.isEmpty());
                }
                Recommender.Conditions compressed=ai.extract(genreComplement);
                assertEquals(List.of("unsupported_genre"),compressed.tags);assertTrue(compressed.excludedTags.isEmpty()&&compressed.language.isEmpty(),"An unknown genre's only constraint must not exclude unrelated genres and moods.");
                assertEquals(0,Recommender.recommend(catalogue,compressed,Map.of(),"openai").tracks.size());
                for(Recommender.Conditions recoveredComplement:List.of(ai.extract("팝으로 바꿔줘",List.of(),compressed),ai.extract("팝으로 바꿔줘",List.of(genreComplement)))) {
                    assertEquals(List.of("pop"),recoveredComplement.tags);assertTrue(recoveredComplement.excludedTags.isEmpty()&&recoveredComplement.language.isEmpty());
                    assertFalse(Recommender.recommend(catalogue,recoveredComplement,Map.of(),"openai").tracks.isEmpty());
                }
                Recommender.Conditions semanticSnapshot=new Recommender.Conditions();semanticSnapshot.excludedTags.add("warm");
                Recommender.Conditions explicitComplement=ai.extract(explicitPiano,List.of(),semanticSnapshot);
                assertEquals(List.of("unsupported_genre"),explicitComplement.tags);assertEquals(List.of("warm","piano"),explicitComplement.excludedTags);
                assertEquals(List.of("warm","piano"),ai.extract(explicitPiano,List.of("따뜻한 음악 제외")).excludedTags);
                assertEquals(List.of("piano"),ai.extract(resetComplement,List.of(),semanticSnapshot).excludedTags);
                assertEquals(supportedGenres,ai.extract(explicitGenres).excludedTags,"Actual explicit exclusions of all supported genres remain intact.");
                for(String request:explicitNegativeComplements) {
                    Recommender.Conditions negative=ai.extract(request,List.of(),semanticSnapshot);
                    assertEquals(List.of("unsupported_genre"),negative.tags,request);assertEquals(List.of("warm","piano"),negative.excludedTags,request);
                    Recommender.Conditions nextPop=ai.extract("팝으로 바꿔줘",List.of(),negative);
                    assertEquals(List.of("pop"),nextPop.tags);assertEquals(List.of("warm","piano"),nextPop.excludedTags);
                    Recommender.Result filteredPop=Recommender.recommend(catalogue,nextPop,Map.of(),"openai");
                    assertFalse(filteredPop.tracks.isEmpty());assertTrue(filteredPop.tracks.stream().noneMatch(p->p.track.tags.contains("piano")||p.track.tags.contains("warm")));
                    assertEquals(List.of("piano"),ai.extract("팝으로 바꿔줘",List.of(request)).excludedTags,"History-only refinement preserves the direct exclusion.");
                    assertEquals(List.of("piano"),ai.extract("처음부터 "+request,List.of(),semanticSnapshot).excludedTags,"Reset removes the prior snapshot, not the latest explicit exclusion.");
                }
                for(String invalid:List.of(" invalid language"," invalid tag"))
                    assertThrows(IllegalArgumentException.class,()->ai.extract(genreComplement+invalid),"Complement recovery must follow raw validation.");
                Recommender.Conditions replaced=ai.extract("팝으로 바꿔줘",List.of(),unlisted);
                assertEquals(List.of("pop"),replaced.tags);assertTrue(replaced.language.isEmpty());
                assertFalse(Recommender.recommend(catalogue,replaced,Map.of(),"openai").tracks.isEmpty());
                Recommender.Conditions initialPop=ai.extract("팝");assertEquals(List.of("pop"),initialPop.tags);assertTrue(initialPop.language.isEmpty());
                Recommender.Conditions initialMood=ai.extract("신나는 음악을 추천해줘");
                assertEquals(List.of("energetic"),initialMood.tags);assertTrue(initialMood.language.isEmpty());
                Recommender.Conditions quietExcluded=ai.extract("잔잔한 곡은 제외");
                assertTrue(quietExcluded.tags.isEmpty()&&quietExcluded.language.isEmpty());assertEquals(List.of("calm"),quietExcluded.excludedTags);
                assertEquals(8,Recommender.recommend(catalogue,initialMood,Map.of(),"openai").tracks.size());
                Recommender.Conditions englishPop=ai.extract("팝으로 바꿔줘",List.of("영어 피아노"));
                assertEquals("en",englishPop.language);assertEquals(List.of("pop"),englishPop.tags);
                assertTrue(aiRequests.get(aiRequests.size()-1).path("input").isArray(),"The stub must read the latest request from conversation input.");
                Recommender.Conditions stillEnglish=ai.extract("팝으로 바꿔줘",List.of(),englishPop);
                assertEquals("en",stillEnglish.language);assertEquals(List.of("pop"),stillEnglish.tags);
                Recommender.Conditions inheritedEnglish=ai.extract("Gregorian chant",List.of(),englishPop);
                assertEquals("en",inheritedEnglish.language);assertEquals(List.of("unsupported_genre"),inheritedEnglish.tags);
                Recommender.Conditions resetUnknown=ai.extract("처음부터 Gregorian chant",List.of(),englishPop);
                assertTrue(resetUnknown.language.isEmpty());assertEquals(List.of("unsupported_genre"),resetUnknown.tags);
                Recommender.Conditions englishMood=ai.extract("신나는 음악을 추천해줘",List.of(),englishPop);
                assertEquals("en",englishMood.language);assertEquals(List.of("pop","energetic"),englishMood.tags);
                Recommender.Conditions resetPop=ai.extract("처음부터 팝",List.of(),englishPop);
                assertEquals(List.of("pop"),resetPop.tags);assertTrue(resetPop.language.isEmpty());
                Recommender.Conditions americanPop=ai.extract("미국 팝");
                assertEquals("en",americanPop.language);assertEquals(List.of("pop"),americanPop.tags);
                Recommender.Conditions americanHistory=ai.extract("팝으로 바꿔줘",List.of("미국 음악"));
                assertEquals("en",americanHistory.language);assertEquals(List.of("pop"),americanHistory.tags);
                Recommender.Conditions cozyHistory=ai.extract("팝으로 바꿔줘",List.of("cozy music"));
                assertTrue(cozyHistory.language.isEmpty());assertEquals(List.of("warm","pop"),cozyHistory.tags);
                Recommender.Conditions cozyMood=ai.extract("신나는 음악을 추천해줘",List.of("cozy music"));
                assertTrue(cozyMood.language.isEmpty());assertEquals(List.of("pop","energetic"),cozyMood.tags);
                Recommender.Conditions mandarinHistory=ai.extract("팝으로 바꿔줘",List.of("영어 음악","이제 Mandarin 보컬로 바꿔줘"));
                assertEquals("zh",mandarinHistory.language);
                assertEquals(0,Recommender.recommend(catalogue,mandarinHistory,Map.of(),"openai").tracks.size());
                Recommender.Conditions historyReset=ai.extract("처음부터 팝",List.of("cozy music"));
                assertTrue(historyReset.language.isEmpty());assertEquals(List.of("pop"),historyReset.tags);
                value("PUT","/me/feedback/"+dislike,token,Map.of("reaction",2),400);
                value("DELETE","/me/feedback/"+dislike,token,null,204);
                assertEquals(0,value("GET","/me/feedback",token,null,200).size());
                value("POST","/playlists",token,Map.of("title"," "),400);
                creation=Map.of("title","공부 음악","id",UUID.randomUUID().toString());
                JsonNode created=value("POST","/playlists",token,creation,201);
                playlist=created.path("id").asString();assertEquals(creation.get("id"),playlist);
                assertEquals(created,value("POST","/playlists",token,creation,201));
                assertEquals(1,value("GET","/playlists",token,null,200).size());
                assertEquals("목록을 찾을 수 없어요.",value("POST","/playlists",other,creation,404).path("message").asString());
                assertEquals("목록이 이미 만들어졌어요. 원래 이름으로 다시 시도해 주세요.",
                    value("POST","/playlists",token,Map.of("id",playlist,"title","다른 이름"),409).path("message").asString());
                for(String invalid:List.of("","not-a-uuid","1-1-1-1-1","AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))
                    value("POST","/playlists",token,Map.of("title","공부 음악","id",invalid),400);
                String distinct=value("POST","/playlists",token,Map.of("title","공부 음악","id",UUID.randomUUID().toString()),201).path("id").asString();
                assertNotEquals(playlist,distinct);assertEquals(2,value("GET","/playlists",token,null,200).size());
                value("DELETE","/playlists/"+distinct,token,null,204);
                value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId","invented"),404);
                JsonNode first=value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId",saved),200);
                assertEquals(first,value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId",saved),200));
                assertEquals(1,value("GET","/playlists/"+playlist,token,null,200).path("items").size());
                org.springframework.jdbc.core.JdbcTemplate db=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                JsonNode replay=value("POST","/playlists",token,creation,201);
                assertEquals(playlist,replay.path("id").asString());assertEquals(1,replay.path("size").asInt());
                assertEquals(created.path("createdAt"),replay.path("createdAt"));
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM playlists WHERE id=?",Integer.class,playlist));
                String snapshot=db.queryForObject("SELECT track_json FROM playlist_items WHERE id=?",String.class,first.path("id").asString());
                assertEquals("MusicBrainz",json.readTree(snapshot).path("source").asString());
                assertEquals("https://musicbrainz.org/recording/"+saved,json.readTree(snapshot).path("sourceUrl").asString());
                value("GET","/playlists/"+playlist,other,null,404);
                value("POST","/playlists/"+playlist+"/items",other,Map.of("trackId",saved),404);
                value("DELETE","/playlists/"+playlist+"/items/"+first.path("id").asString(),other,null,404);
                assertEquals(1,value("GET","/me/saved-tracks",token,null,200).size());
                assertEquals("search",value("GET","/tracks/"+saved+"/links",token,null,200).get(0).path("kind").asString());
                String bulk=value("POST","/playlists",token,Map.of("title","Batch check"),201).path("id").asString();
                assertEquals(bulk,UUID.fromString(bulk).toString());assertNotEquals(playlist,bulk);
                String bulkPath="/playlists/"+bulk;
                value("POST",bulkPath+"/items/batch",token,Map.of("trackIds",List.of(saved,"invented")),404);
                value("POST",bulkPath+"/items/batch",token,Map.of("trackIds",List.of()),400);
                value("POST",bulkPath+"/items/batch",token,Map.of("trackIds",List.of(saved,saved)),400);
                value("POST",bulkPath+"/items/batch",token,Map.of("trackIds",java.util.Collections.nCopies(11,saved)),400);
                assertEquals(0,value("GET",bulkPath,token,null,200).path("items").size());
                value("POST",bulkPath+"/items/batch",other,Map.of("trackIds",List.of(saved,dislike)),404);
                JsonNode batch=value("POST",bulkPath+"/items/batch",token,Map.of("trackIds",List.of(saved,dislike)),200);
                assertEquals(batch,value("POST",bulkPath+"/items/batch",token,Map.of("trackIds",List.of(saved,dislike)),200));
                assertEquals(2,value("GET",bulkPath,token,null,200).path("items").size());
                value("DELETE",bulkPath,token,null,204);
                String managed=value("POST","/playlists",token,Map.of("title","Manage check"),201).path("id").asString();
                String path="/playlists/"+managed;
                JsonNode a=value("POST",path+"/items",token,Map.of("trackId",saved),200);
                JsonNode b=value("POST",path+"/items",token,Map.of("trackId",dislike),200);
                String aId=a.path("id").asString(), bId=b.path("id").asString();
                value("PUT",path,other,Map.of("title","stolen"),404);
                value("DELETE",path,other,null,404);
                value("PUT",path+"/items/order",other,Map.of("itemIds",List.of(bId,aId)),404);
                value("PUT",path,token,Map.of("title"," "),400);
                value("PUT",path,token,Map.of("title","Renamed check"),204);
                assertEquals("Renamed check",value("GET",path,token,null,200).path("title").asString());
                assertEquals(1,value("GET","/playlists?q=RENAMED",token,null,200).size());
                assertEquals(0,value("GET","/playlists?q=RENAMED",other,null,200).size());
                value("PUT",path+"/items/order",token,Map.of("itemIds",List.of(aId,aId)),400);
                value("PUT",path+"/items/order",token,Map.of("itemIds",List.of(aId)),400);
                value("PUT",path+"/items/order",token,Map.of("itemIds",List.of(aId,first.path("id").asString())),400);
                assertEquals(aId,value("GET",path,token,null,200).path("items").get(0).path("id").asString());
                value("PUT",path+"/items/order",token,Map.of("itemIds",List.of(bId,aId)),204);
                JsonNode reordered=value("GET",path,token,null,200).path("items");
                assertEquals(bId,reordered.get(0).path("id").asString()); assertEquals(aId,reordered.get(1).path("id").asString());
                assertEquals(saved,reordered.get(1).path("track").path("id").asString());
                value("DELETE",path,token,null,204); value("GET",path,token,null,404);
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?",Integer.class,managed));
                assertEquals(1,value("GET","/me/saved-tracks",token,null,200).size());
                assertEquals(0,value("GET","/me/feedback",token,null,200).size());
                List<String> catalogueIds=new ArrayList<>();
                for(JsonNode track:value("GET","/catalogue",token,null,200))if(catalogueIds.size()<10)catalogueIds.add(track.path("id").asString());
                coherentReadsDuringDeletion(token,catalogueIds);
                String cappedToken=value("POST","/sessions",null,null,201).path("token").asString();
                String cappedUser=context.getBean(Store.class).user("Bearer "+cappedToken);
                Map<String,String> cappedCreation=Map.of("title","한도 재시도 검사","id",UUID.randomUUID().toString());
                JsonNode capped=value("POST","/playlists",cappedToken,cappedCreation,201);
                for(int i=0;i<99;i++)db.update("INSERT INTO playlists VALUES (?,?,?,?)",UUID.randomUUID().toString(),cappedUser,"한도 검사 "+i,Instant.now().toString());
                assertEquals(capped,value("POST","/playlists",cappedToken,cappedCreation,201));
                value("POST","/playlists",cappedToken,Map.of("title","추가 목록","id",UUID.randomUUID().toString()),409);
                value("POST","/playlists",cappedToken,Map.of("title","기존 생성 방식"),409);
                assertEquals(100,value("GET","/playlists",cappedToken,null,200).size());
            }
            try(ConfigurableApplicationContext context=start(database,endpoint)) {
                base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
                JsonNode replay=value("POST","/playlists",token,creation,201);
                assertEquals(playlist,replay.path("id").asString());assertEquals(1,replay.path("size").asInt());
                assertEquals(1,value("GET","/playlists",token,null,200).size());
                JsonNode item=value("GET","/playlists/"+playlist,token,null,200).path("items").get(0);
                assertEquals(saved,item.path("track").path("id").asString());
                assertEquals("https://musicbrainz.org/recording/"+saved,item.path("track").path("sourceUrl").asString());
                value("DELETE","/playlists/"+playlist+"/items/"+item.path("id").asString(),token,null,204);
                assertEquals(0,value("GET","/playlists/"+playlist,token,null,200).path("items").size());
            }
        } finally { stub.stop(0); }
    }

    private void coherentReadsDuringDeletion(String token,List<String> trackIds) throws Exception {
        try(var workers=Executors.newFixedThreadPool(9)) {
            // Exercise simultaneous snapshots without turning this data-consistency check into an abuse-limit check.
            for(int round=0;round<8;round++) {
                String id=value("POST","/playlists",token,Map.of("title","Concurrent detail check"),201).path("id").asString();
                String path="/playlists/"+id;
                value("POST",path+"/items/batch",token,Map.of("trackIds",trackIds),200);
                assertEquals(trackIds.size(),value("GET",path,token,null,200).path("items").size());
                CountDownLatch ready=new CountDownLatch(9),start=new CountDownLatch(1);
                List<Future<HttpResponse<String>>> reads=new ArrayList<>();
                for(int reader=0;reader<8;reader++)reads.add(workers.submit(()->{
                    ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return request("GET",path,token,null);
                }));
                Future<HttpResponse<String>> deletion=workers.submit(()->{
                    ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return request("DELETE",path,token,null);
                });
                assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
                assertEquals(204,deletion.get(10,TimeUnit.SECONDS).statusCode());
                for(Future<HttpResponse<String>> read:reads) {
                    HttpResponse<String> response=read.get(10,TimeUnit.SECONDS);
                    assertTrue(response.statusCode()==200||response.statusCode()==404,response.body());
                    if(response.statusCode()==200) {
                        JsonNode detail=json.readTree(response.body()),items=detail.path("items");
                        assertEquals("Concurrent detail check",detail.path("title").asString());
                        assertEquals(trackIds.size(),items.size(),"A successful detail must contain its complete pre-deletion snapshot");
                        for(int i=0;i<items.size();i++) {
                            assertEquals(i,items.get(i).path("position").asInt());
                            assertEquals(trackIds.get(i),items.get(i).path("track").path("id").asString());
                            assertEquals("https://musicbrainz.org/recording/"+trackIds.get(i),items.get(i).path("track").path("sourceUrl").asString());
                        }
                    }
                }
                value("GET",path,token,null,404);
            }
        }
    }

    private ConfigurableApplicationContext start(String database,String endpoint) {
        return new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url="+database,"--spring.datasource.username="+System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"),
            "--spring.datasource.password="+System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD",""),
            "--taptap.openai.key=check-only","--taptap.openai.endpoint="+endpoint,
            "--taptap.ai.daily-limit=3","--logging.level.root=WARN","--spring.main.banner-mode=off");
    }
}
