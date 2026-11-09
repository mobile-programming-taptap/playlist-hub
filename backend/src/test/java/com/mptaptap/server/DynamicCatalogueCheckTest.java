package com.mptaptap.server;

import com.mptaptap.core.Recommender;
import com.mptaptap.core.Track;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class DynamicCatalogueCheckTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();
    private String base;

    @Test void verifiedNewSongsSurviveFeedbackStorageRefinementAndRestart() throws Exception {
        String firstId=UUID.randomUUID().toString(),secondId=UUID.randomUUID().toString();
        String wrongId=UUID.randomUUID().toString(),liveId=UUID.randomUUID().toString(),releaseId=UUID.randomUUID().toString();
        List<Map<String,Object>> initial=List.of(song("Take Five","The Dave Brubeck Quartet",List.of("jazz","instrumental"),"none"),
            song("So What","Miles Davis",List.of("jazz","instrumental"),"none"),
            song("Imaginary Test Song","Imaginary Test Artist",List.of("jazz"),""),
            song("Blue in Green","Miles Davis",List.of("jazz","instrumental"),"none"));
        AtomicReference<Map<String,Object>> model=new AtomicReference<>(plan("jazz",initial));
        AtomicInteger catalogueStatus=new AtomicInteger(200);
        List<JsonNode> aiRequests=new CopyOnWriteArrayList<>();List<String> queries=new CopyOnWriteArrayList<>();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{aiRequests.add(json.readTree(exchange.getRequestBody().readAllBytes()));respond(exchange,200,envelope(model.get()));});
        mb.createContext("/recording/",exchange->{
            queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));
            respond(exchange,catalogueStatus.get(),Map.of("recordings",List.of(
                recording(firstId,"Take Five","The Dave Brubeck Quartet","",releaseId),
                recording(secondId,"Clocks","Coldplay","",releaseId),
                recording(wrongId,"So What","A Different Artist","",releaseId),
                recording(liveId,"Blue in Green","Miles Davis","live at a concert",releaseId))));
        });
        ai.start();mb.start();
        String database=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_URL","jdbc:h2:mem:dynamic"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String username=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"),password=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD","");
        String aiEndpoint="http://127.0.0.1:"+ai.getAddress().getPort()+"/responses",mbEndpoint="http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/";
        ConfigurableApplicationContext app=null;String owner=null;
        try {
            app=start(database,username,password,aiEndpoint,mbEndpoint);
            JsonNode session=value("POST","/sessions",null,null,201);String token=session.path("token").asString();owner=session.path("userId").asString();
            JsonNode original=value("GET","/catalogue",token,null,200);int before=original.size();
            for(JsonNode row:original) {
                assertFalse(row.path("title").asString().equals("Take Five")&&row.path("artist").asString().equals("The Dave Brubeck Quartet"));
                assertFalse(row.path("title").asString().equals("Clocks")&&row.path("artist").asString().equals("Coldplay"),"This journey must register a new recording, not reuse a seed.");
            }
            String imported=value("POST","/playlists",token,Map.of("title","External boundary"),201).path("id").asString();
            app.getBean(Store.class).addExternal(owner,imported,List.of(Map.of("platform","spotify","externalId","0000000000000000000001",
                "title","PRIVATE_SPOTIFY_IMPORT_TITLE","artist","PRIVATE_SPOTIFY_IMPORT_ARTIST","sourceRow","PRIVATE_SPOTIFY_IMPORT_ROW",
                "sourceUrl","https://open.spotify.com/track/0000000000000000000001","sourceIndex",0)),UUID.randomUUID().toString());

            JsonNode first=value("POST","/recommendations",token,Map.of("prompt","재즈만 추천해줘"),200);
            assertEquals("openai",first.path("mode").asString());assertEquals(List.of(firstId),ids(first.path("tracks")));
            JsonNode track=first.path("tracks").get(0).path("track");
            assertEquals("Take Five",track.path("title").asString());assertEquals("The Dave Brubeck Quartet",track.path("artist").asString());
            assertEquals("MusicBrainz",track.path("source").asString());assertEquals("CC0-1.0",track.path("license").asString());
            assertEquals("https://musicbrainz.org/recording/"+firstId,track.path("sourceUrl").asString());assertTrue(track.path("aiUseAllowed").asBoolean());
            assertEquals("https://coverartarchive.org/release/"+releaseId+"/front-500",track.path("artworkUrl").asString());
            assertEquals("",track.path("youtubeVideoId").asString());assertTrue(first.path("notice").asString().contains("1곡"));
            assertEquals(1,aiRequests.size());assertEquals(1,queries.size(),"All suggestions use one MusicBrainz bulk request.");
            assertTrue(queries.get(0).contains("Take Five")&&queries.get(0).contains("Imaginary Test Song"));
            JsonNode expanded=value("GET","/catalogue",token,null,200);assertEquals(before+1,expanded.size());
            assertFalse(expanded.toString().contains(wrongId));assertFalse(expanded.toString().contains(liveId));
            assertEquals(1,value("GET","/catalogue?q=Take%20Five",token,null,200).size());

            value("PUT","/me/feedback/"+firstId,token,Map.of("reaction",1),200);
            String playlist=value("POST","/playlists",token,Map.of("title","Verified new songs"),201).path("id").asString();
            String path="/playlists/"+playlist;
            JsonNode item=value("POST",path+"/items",token,Map.of("trackId",firstId),200);
            assertEquals(firstId,item.path("trackId").asString());
            value("POST",path+"/items",token,Map.of("trackId",firstId),200);
            assertEquals(1,value("GET",path,token,null,200).path("items").size());
            JsonNode links=value("GET","/tracks/"+firstId+"/links",token,null,200);
            assertEquals("search",links.get(0).path("kind").asString());assertEquals("search",links.get(1).path("kind").asString());
            assertTrue(links.get(0).path("url").asString().contains("Take%20Five"));

            model.set(plan("pop",List.of(song("Clocks","Coldplay",List.of("pop","rock","warm"),"en"))));
            JsonNode refined=value("POST","/recommendations",token,Map.of("prompt","팝으로 바꿔줘","previousConditions",first.path("conditions"),
                "keptTrackIds",List.of(firstId)),200);
            assertEquals(List.of(firstId,secondId),ids(refined.path("tracks")));assertEquals("[\"pop\"]",refined.path("conditions").path("tags").toString());
            assertEquals(2,aiRequests.size());assertEquals(2,queries.size());
            JsonNode excluded=value("POST","/recommendations",token,Map.of("prompt","팝","previousConditions",refined.path("conditions"),
                "excludedTrackIds",List.of(firstId)),200);
            assertEquals(List.of(secondId),ids(excluded.path("tracks")));assertEquals(3,aiRequests.size());
            assertEquals(2,queries.size(),"A previously verified exact recording is reused without another lookup.");
            for(JsonNode sent:aiRequests) {
                assertEquals(2200,sent.path("max_output_tokens").asInt());assertFalse(sent.path("store").asBoolean());
                assertTrue(sent.path("text").path("format").path("strict").asBoolean());
                assertTrue(sent.path("text").path("format").path("schema").path("required").toString().contains("candidates"));
                assertEquals(12,sent.path("text").path("format").path("schema").path("properties").path("candidates").path("maxItems").asInt());
                assertFalse(sent.toString().contains("PRIVATE_SPOTIFY_IMPORT"));assertFalse(sent.toString().contains(firstId));
                assertFalse(sent.toString().contains("Take Five"));
            }
            assertTrue(aiRequests.get(1).path("instructions").asString().contains("\"jazz\":1"));
            assertTrue(aiRequests.get(1).path("instructions").asString().contains("\"instrumental\":1"));

            model.set(plan("jazz",List.of(song("Autumn Leaves","Cannonball Adderley",List.of("jazz","instrumental"),"none"))));catalogueStatus.set(500);
            JsonNode failed=value("POST","/recommendations",token,Map.of("prompt","재즈만 추천해줘"),503);
            assertFalse(failed.has("tracks"));
            assertTrue(failed.path("message").asString().contains("실제 곡 정보"));assertEquals(4,aiRequests.size());assertEquals(3,queries.size());
            assertEquals(before+2,value("GET","/catalogue",token,null,200).size());
            model.set(plan("jazz",List.of()));
            JsonNode empty=value("POST","/recommendations",token,Map.of("prompt","즐거운 제이팝"),200);
            assertEquals("openai",empty.path("mode").asString());assertEquals(0,empty.path("tracks").size());
            assertTrue(empty.path("conditions").path("tags").toString().contains("jpop"));
            assertTrue(empty.path("notice").asString().contains("제안하지 못"));
            catalogueStatus.set(200);model.set(plan("jazz",List.of(song("unverified "+UUID.randomUUID(),"Unknown performer",List.of("jazz"),"en"))));
            JsonNode unverified=value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),200);
            assertEquals(0,unverified.path("tracks").size(),"Cached catalogue songs cannot replace unverified AI suggestions");
            assertTrue(unverified.path("notice").asString().contains("실제 곡 정보를 확인한 곡은 0곡"));
            assertFalse(unverified.path("notice").asString().contains("조건을 줄이면"));
            assertEquals(6,aiRequests.size());assertEquals(4,queries.size());
            value("PUT","/community/rules/acceptance",token,Map.of("version",Store.COMMUNITY_RULES_VERSION,"accepted",true),200);
            String share=value("PUT",path+"/publication",token,Map.of("public",true),200).path("shareId").asString();
            JsonNode saved=value("GET",path,token,null,200),published=value("GET","/public/playlists/"+share,null,null,200);
            assertEquals(track,saved.path("items").get(0).path("track"));assertEquals(track,published.path("items").get(0).path("track"));
            app.close();app=null;app=start(database,username,password,aiEndpoint,mbEndpoint);
            assertEquals(before+2,value("GET","/catalogue",token,null,200).size());
            assertEquals(1,value("GET","/me/feedback",token,null,200).path(firstId).asInt());
            assertEquals(List.of(firstId),ids(value("GET","/me/saved-tracks",token,null,200)));
            assertEquals(saved,value("GET",path,token,null,200));assertEquals(published,value("GET","/public/playlists/"+share,null,null,200));
            HttpResponse<String> html=request("GET","/share/"+share,null,null);
            assertEquals(200,html.statusCode());assertTrue(html.body().contains("Take Five"));assertTrue(html.body().contains("https://musicbrainz.org/recording/"+firstId));
            assertEquals(6,aiRequests.size(),"Reading durable catalogue, feedback and saved/shared snapshots makes no AI call.");
        } finally {
            if(app!=null)app.close();ai.stop(0);mb.stop(0);
            JdbcTemplate cleanup=new JdbcTemplate(new DriverManagerDataSource(database,username,password));
            if(owner!=null)cleanup.update("DELETE FROM users WHERE id=?",owner);
            for(String id:List.of(firstId,secondId,wrongId,liveId))cleanup.update("DELETE FROM recommendation_tracks WHERE id=?",id);
        }
    }

    @Test void newGenreGivesEightVerifiedPicksAndRefinementPreservesExplicitExclusion() throws Exception {
        String[][] credits={{"Chega de Saudade","João Gilberto"},{"Desafinado","João Gilberto"},
            {"Corcovado","Antônio Carlos Jobim"},{"Wave","Antônio Carlos Jobim"},
            {"Águas de Março","Elis Regina & Antônio Carlos Jobim"},{"Samba de Uma Nota Só","João Gilberto"},
            {"Meditação","Antônio Carlos Jobim"},{"O Barquinho","João Gilberto"},
            {"Mas Que Nada","Sérgio Mendes feat. The Black Eyed Peas"},{"Remember Summer Days","Anri"}};
        List<String> recordingIds=new ArrayList<>();List<Map<String,Object>> recordings=new ArrayList<>(),songs=new ArrayList<>();
        String release=UUID.randomUUID().toString();
        for(int i=0;i<credits.length;i++) {
            String id=UUID.randomUUID().toString();recordingIds.add(id);recordings.add(recording(id,credits[i][0],credits[i][1],"",release));
            if(i<9)songs.add(song(credits[i][0],credits[i][1],i==8?List.of("bossa_nova","hiphop"):List.of("bossa_nova"),""));
        }
        Map<String,Object> initial=new LinkedHashMap<>(plan("bossa_nova",songs));initial.put("excludedTags",List.of("hiphop"));
        AtomicReference<Map<String,Object>> model=new AtomicReference<>(initial);List<JsonNode> sent=new CopyOnWriteArrayList<>();AtomicInteger searches=new AtomicInteger();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{sent.add(json.readTree(exchange.getRequestBody().readAllBytes()));respond(exchange,200,envelope(model.get()));});
        mb.createContext("/recording/",exchange->{searches.incrementAndGet();respond(exchange,200,Map.of("recordings",recordings));});ai.start();mb.start();
        String database=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_URL","jdbc:h2:mem:genres"+UUID.randomUUID()+";MODE=PostgreSQL");
        String username=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"),password=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD","");
        ConfigurableApplicationContext app=null;String owner=null;
        try {
            app=start(database,username,password,"http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            JsonNode session=value("POST","/sessions",null,null,201);String token=session.path("token").asString();owner=session.path("userId").asString();
            JsonNode original=value("GET","/catalogue",token,null,200);
            for(JsonNode row:original)for(String[] credit:credits)
                assertFalse(row.path("title").asString().equals(credit[0])&&row.path("artist").asString().equals(credit[1]),"The genre fixture must exercise new catalogue recordings.");
            JsonNode first=value("POST","/recommendations",token,Map.of("prompt","보사노바 추천해줘, 힙합 제외"),200);
            assertEquals("openai",first.path("mode").asString());assertEquals(8,first.path("tracks").size());
            assertTrue(ids(first.path("tracks")).containsAll(recordingIds.subList(0,8)));assertFalse(ids(first.path("tracks")).contains(recordingIds.get(8)));
            assertEquals("[\"bossa_nova\"]",first.path("conditions").path("tags").toString());
            assertEquals("[\"hiphop\"]",first.path("conditions").path("excludedTags").toString());assertEquals("",first.path("conditions").path("language").asString());
            for(JsonNode pick:first.path("tracks")) {
                JsonNode track=pick.path("track");int index=recordingIds.indexOf(track.path("id").asString());assertTrue(index>=0&&index<8);
                assertEquals(credits[index][0],track.path("title").asString());assertEquals(credits[index][1],track.path("artist").asString());
                assertEquals("https://musicbrainz.org/recording/"+recordingIds.get(index),track.path("sourceUrl").asString());assertEquals("CC0-1.0",track.path("license").asString());
                assertEquals("MusicBrainz",track.path("source").asString());assertTrue(track.path("aiUseAllowed").asBoolean());
                assertTrue(track.path("tags").toString().contains("bossa_nova"));
            }
            assertEquals(original.size()+9,value("GET","/catalogue",token,null,200).size(),"Excluded verified recordings remain reusable catalogue facts.");
            assertEquals(1,sent.size());assertEquals(1,searches.get());
            JsonNode schema=sent.get(0).path("text").path("format").path("schema");
            assertTrue(schema.path("properties").path("tags").path("items").path("pattern").asString().contains("bossa_nova"));
            assertTrue(schema.path("properties").path("candidates").path("items").path("properties").path("tags").path("items").path("pattern").asString().contains("citypop"));
            String instructions=sent.get(0).path("instructions").asString();
            assertTrue(instructions.contains(json.writeValueAsString(Recommender.LABELS)),"The AI must receive the shared supported-tag meanings, including J-pop and bossa nova.");
            Map<String,Object> next=new LinkedHashMap<>(plan("citypop",List.of(song(credits[9][0],credits[9][1],List.of("citypop"),"unsupported"))));
            next.put("tags",List.of("citypop","pop"));model.set(next);
            String kept=first.path("tracks").get(0).path("track").path("id").asString();
            JsonNode refined=value("POST","/recommendations",token,Map.of("prompt","시티팝으로 바꿔줘","previousConditions",first.path("conditions"),"keptTrackIds",List.of(kept)),200);
            assertEquals(List.of(kept,recordingIds.get(9)),ids(refined.path("tracks")));assertEquals("[\"citypop\"]",refined.path("conditions").path("tags").toString());
            assertEquals("[\"hiphop\"]",refined.path("conditions").path("excludedTags").toString(),"Genre refinement must retain the explicit prior exclusion even when the model omits it.");
            assertFalse(refined.path("conditions").path("tags").toString().contains("\"pop\""));assertEquals("",refined.path("conditions").path("language").asString());
            assertEquals(2,sent.size());assertEquals(2,searches.get());

            String cityId=recordingIds.get(9);JdbcTemplate db=app.getBean(JdbcTemplate.class);
            JsonNode verifiedFacts=json.readTree(db.queryForObject("SELECT track_json FROM recommendation_tracks WHERE id=?",String.class,cityId));
            Track outdated=json.readValue(verifiedFacts.toString(),Track.class);outdated.tags=List.of("pop");outdated.language="ko";
            db.update("UPDATE recommendation_tracks SET track_json=? WHERE id=?",json.writeValueAsString(outdated),cityId);
            String savedPath="/playlists/"+value("POST","/playlists",token,Map.of("title","Classification snapshot"),201).path("id").asString();
            value("POST",savedPath+"/items",token,Map.of("trackId",cityId),200);
            JsonNode savedBefore=value("GET",savedPath,token,null,200);
            assertEquals("[\"pop\"]",savedBefore.path("items").get(0).path("track").path("tags").toString());
            JsonNode cached=value("POST","/recommendations",token,Map.of("prompt","시티팝 더 추천해줘","previousConditions",refined.path("conditions")),200);
            assertEquals(List.of(cityId),ids(cached.path("tracks")));assertEquals(3,sent.size());assertEquals(2,searches.get(),"Reclassifying a verified exact recording needs no new MusicBrainz lookup.");
            JsonNode refreshed=cached.path("tracks").get(0).path("track");
            assertEquals("[\"citypop\"]",refreshed.path("tags").toString());assertEquals("unsupported",refreshed.path("language").asString());
            for(String field:List.of("id","title","artist","source","sourceUrl","license","licenseReference","tagsSource","artworkUrl","youtubeVideoId","checkedAt","aiUseAllowed"))
                assertEquals(verifiedFacts.path(field),refreshed.path(field),"Reclassification must preserve verified recording facts: "+field);
            assertEquals(refreshed,json.readTree(db.queryForObject("SELECT track_json FROM recommendation_tracks WHERE id=?",String.class,cityId)),"The all-cache path must persist the current AI classification.");
            assertEquals(savedBefore,value("GET",savedPath,token,null,200),"Updating catalogue classification must not rewrite an already saved playlist snapshot.");
        } finally {
            try {
                if(app!=null) {
                    JdbcTemplate cleanup=app.getBean(JdbcTemplate.class);if(owner!=null)cleanup.update("DELETE FROM users WHERE id=?",owner);
                    for(String id:recordingIds)cleanup.update("DELETE FROM recommendation_tracks WHERE id=?",id);
                }
            } finally {
                try {if(app!=null)app.close();} finally {ai.stop(0);mb.stop(0);}
            }
        }
    }

    @Test void openGenresAndDistinctVocalLanguagesStayVerifiedAndSurviveRefinement() throws Exception {
        String french=UUID.randomUUID().toString(),japanese=UUID.randomUUID().toString(),release=UUID.randomUUID().toString();
        List<Map<String,Object>> candidates=List.of(song("La vie en rose","Édith Piaf",List.of("genre:chanson","warm"),"fr"),
            song("夜に駆ける","YOASOBI",List.of("genre:chanson","warm"),"ja"),song("Not independently verified","Unknown artist",List.of("genre:chanson"),"fr"));
        AtomicReference<Map<String,Object>> model=new AtomicReference<>(Map.of("tags",List.of("genre:chanson"),"excludedTags",List.of("genre:enka"),"language","fr","languageSpecified",true,"candidates",candidates));
        AtomicInteger catalogueStatus=new AtomicInteger(200);List<JsonNode> sent=new CopyOnWriteArrayList<>();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{sent.add(json.readTree(exchange.getRequestBody().readAllBytes()));respond(exchange,200,envelope(model.get()));});
        mb.createContext("/recording/",exchange->{if(catalogueStatus.get()==413){byte[] oversized="x".repeat(4_000_001).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,oversized.length);try{exchange.getResponseBody().write(oversized);}finally{exchange.close();}}else respond(exchange,200,Map.of("recordings",List.of(recording(french,"La vie en rose","Édith Piaf","",release),recording(japanese,"夜に駆ける","YOASOBI","",release))));});
        ai.start();mb.start();ConfigurableApplicationContext app=null;String owner=null;
        try {
            app=start("jdbc:h2:mem:open-vocabulary-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","","http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            JsonNode session=value("POST","/sessions",null,null,201);String token=session.path("token").asString();owner=session.path("userId").asString();
            JsonNode seed=value("GET","/catalogue",token,null,200).get(0);
            JsonNode first=value("POST","/recommendations",token,Map.of("prompt","프랑스어 샹송, 엔카는 제외"),200);
            assertEquals(List.of(french),ids(first.path("tracks")),"A Japanese candidate and an unverified title cannot fill a French request.");
            assertEquals("fr",first.path("conditions").path("language").asString());assertEquals("[\"genre:chanson\"]",first.path("conditions").path("tags").toString());
            assertEquals("[\"genre:enka\"]",first.path("conditions").path("excludedTags").toString());
            JsonNode schema=sent.get(0).path("text").path("format").path("schema");
            assertTrue(schema.path("properties").path("tags").path("items").path("pattern").asString().contains("genre:"));
            assertFalse(schema.path("properties").path("language").has("enum"));assertTrue(schema.path("properties").path("language").has("pattern"));
            value("PUT","/me/feedback/"+french,token,Map.of("reaction",1),200);
            model.set(Map.of("tags",List.of("genre:chanson","warm"),"excludedTags",List.of("genre:enka"),"language","fr","languageSpecified",false,"candidates",candidates));
            JsonNode warmer=value("POST","/recommendations",token,Map.of("prompt","더 따뜻하게","previousConditions",first.path("conditions")),200);
            assertEquals(List.of(french),ids(warmer.path("tracks")));assertEquals("fr",warmer.path("conditions").path("language").asString());
            assertTrue(warmer.path("conditions").path("tags").toString().contains("genre:chanson"));assertTrue(sent.get(1).path("instructions").asString().contains("\"genre:chanson\":1"));
            assertFalse(sent.get(1).toString().contains("La vie en rose"));assertFalse(sent.get(1).toString().contains(french));
            model.set(Map.of("tags",List.of("genre:chanson","warm"),"excludedTags",List.of("genre:enka"),"language","ja","languageSpecified",true,"candidates",candidates));
            JsonNode changed=value("POST","/recommendations",token,Map.of("prompt","일본어로 바꿔줘","previousConditions",warmer.path("conditions")),200);
            assertEquals(List.of(japanese),ids(changed.path("tracks")));assertEquals("ja",changed.path("conditions").path("language").asString());
            model.set(Map.of("tags",List.of("genre:gregorian_chant"),"excludedTags",List.of("genre:dhrupad"),"language","la","languageSpecified",true,"candidates",List.of()));
            JsonNode reset=value("POST","/recommendations",token,Map.of("prompt","처음부터 라틴어 그레고리오 성가, 드루파드는 제외","previousConditions",changed.path("conditions")),200);
            assertEquals("la",reset.path("conditions").path("language").asString());assertEquals("[\"genre:gregorian_chant\"]",reset.path("conditions").path("tags").toString());
            assertEquals(0,reset.path("tracks").size(),"No verified AI candidate means no catalogue fallback, even for an accepted open genre.");
            int before=sent.size();value("POST","/recommendations",token,Map.of("prompt","음악","previousConditions",Map.of("tags",List.of("genre:<script>"),"excludedTags",List.of(),"language","fr")),400);assertEquals(before,sent.size());
            model.set(plan("genre:dance_pop",List.of(song(seed.path("title").asString(),seed.path("artist").asString(),List.of("genre:dance_pop"),seed.path("language").asString()))));
            JsonNode seeded=value("POST","/recommendations",token,Map.of("prompt","댄스팝을 추천해줘"),200);
            assertEquals(List.of(seed.path("id").asString()),ids(seeded.path("tracks")),"Bundled classifications must not impose a fixed genre ceiling on an AI-selected verified identity.");
            JsonNode reclassified=seeded.path("tracks").get(0).path("track");assertEquals("[\"genre:dance_pop\"]",reclassified.path("tags").toString());
            for(String field:List.of("id","title","artist","source","sourceUrl","license","licenseReference","checkedAt","youtubeVideoId"))assertEquals(seed.path(field),reclassified.path(field));
            String playlist=value("POST","/playlists",token,Map.of("title","Open classification"),201).path("id").asString();
            value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId",seed.path("id").asString()),200);
            assertEquals(reclassified,value("GET","/playlists/"+playlist,token,null,200).path("items").get(0).path("track"));
            model.set(plan("genre:chanson",List.of(song("New lookup","Unknown artist",List.of("genre:chanson"),"fr"))));catalogueStatus.set(413);
            JsonNode oversized=value("POST","/recommendations",token,Map.of("prompt","샹송"),503);assertFalse(oversized.has("tracks"));
        } finally {if(app!=null){if(owner!=null)app.getBean(JdbcTemplate.class).update("DELETE FROM users WHERE id=?",owner);app.close();}ai.stop(0);mb.stop(0);}
    }

    @Test void catalogueQueueIsBoundedBeforeAnotherProviderRequestAndRecoversAfterRelease() throws Exception {
        String firstId=UUID.randomUUID().toString(),secondId=UUID.randomUUID().toString(),releaseId=UUID.randomUUID().toString();
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var calls=new AtomicInteger();
        var worker=java.util.concurrent.Executors.newCachedThreadPool();HttpServer mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);mb.setExecutor(worker);
        mb.createContext("/recording/",exchange->{
            byte[] body=json.writeValueAsBytes(Map.of("recordings",List.of(recording(firstId,"Take Five","The Dave Brubeck Quartet","",releaseId),recording(secondId,"So What","Miles Davis","",releaseId))));
            try {
                exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) {output.write(body,0,1);output.flush();if(calls.incrementAndGet()==1){held.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);}output.write(body,1,body.length-1);}
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}
        });mb.start();ConfigurableApplicationContext app=null;
        try {
            String endpoint="http://127.0.0.1:"+mb.getAddress().getPort();
            app=start("jdbc:h2:mem:catalogue-budget-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",endpoint+"/unused",endpoint+"/recording/");
            RecommendationCatalogue catalogue=app.getBean(RecommendationCatalogue.class);
            var first=new OpenAi.Suggestion("Take Five","The Dave Brubeck Quartet",List.of("jazz","instrumental"),"none");
            var second=new OpenAi.Suggestion("So What","Miles Davis",List.of("jazz","instrumental"),"none");
            var pending=worker.submit(()->catalogue.resolve(List.of(first)));assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS));
            long started=System.nanoTime();assertThrows(IllegalStateException.class,()->catalogue.resolve(List.of(second)));
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(4))<0);
            assertEquals(1,calls.get(),"A caller that cannot enter the catalogue queue must not issue another provider request.");
            assertEquals(0,app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM recommendation_tracks",Integer.class));
            release.countDown();assertEquals(List.of(firstId),pending.get(5,java.util.concurrent.TimeUnit.SECONDS).stream().map(track->track.id).toList());
            assertEquals(List.of(secondId),catalogue.resolve(List.of(second)).stream().map(track->track.id).toList());assertEquals(2,calls.get());
        }finally{release.countDown();if(app!=null)app.close();mb.stop(0);worker.shutdownNow();worker.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}
    }

    @Test void shortVerifiedResultGetsOneBoundedSupplementWithoutChangingConditionsOrUsingUnselectedCache() throws Exception {
        var aiCalls=new AtomicInteger();var failExtra=new java.util.concurrent.atomic.AtomicBoolean();
        var verificationCalls=new AtomicInteger();
        List<JsonNode> requests=new CopyOnWriteArrayList<>();List<Map<String,Object>> rows=new ArrayList<>();
        List<Map<String,Object>> initial=new ArrayList<>(),extra=new ArrayList<>();
        for(int i=0;i<11;i++) {
            String title="Supplement song "+i,artist="Supplement artist "+i;
            var suggestion=song(title,artist,List.of("jazz"),"en");
            if(i<7)initial.add(suggestion);else extra.add(suggestion);
            if(i<4||i>=7)rows.add(recording(UUID.randomUUID().toString(),title,artist,"",UUID.randomUUID().toString()));
        }
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));int call=aiCalls.incrementAndGet();
            try {if(call==1)Thread.sleep(11000);else if(call==2)Thread.sleep(4500);}catch(InterruptedException error){Thread.currentThread().interrupt();}
            if(call%2==0&&failExtra.get())respond(exchange,503,Map.of("error",Map.of("code","unavailable")));
            else respond(exchange,200,envelope(plan(call%2==1?"jazz":"pop",call%2==1?initial:extra)));
        });
        mb.createContext("/recording/",exchange->{
            try {if(verificationCalls.incrementAndGet()==1)Thread.sleep(3500);}catch(InterruptedException error){Thread.currentThread().interrupt();}
            respond(exchange,200,Map.of("recordings",rows));
        });ai.start();mb.start();
        ConfigurableApplicationContext app=null;
        try {
            app=start("jdbc:h2:mem:supplement-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",
                "http://127.0.0.1:"+ai.getAddress().getPort()+"/responses","http://127.0.0.1:"+mb.getAddress().getPort()+"/recording/");
            String token=value("POST","/sessions",null,null,201).path("token").asString();
            long started=System.nanoTime();
            JsonNode complete=value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),200);
            assertEquals(8,complete.path("tracks").size());assertEquals(2,aiCalls.get());
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(30))<0,"A slow first round must leave time for both supplementation and verification within the app deadline");
            assertEquals("[\"jazz\"]",complete.path("conditions").path("tags").toString(),"Supplement output cannot replace original constraints");
            String instructions=requests.get(1).path("instructions").asString();
            assertTrue(instructions.contains("earlier AI-generated title/artist proposals"));assertTrue(instructions.contains("Supplement song 0"));
            failExtra.set(true);
            JsonNode partial=value("POST","/recommendations",token,Map.of("prompt","재즈 추천"),200);
            assertEquals(4,partial.path("tracks").size(),"A failed supplement must retain only verified proposals selected by the first AI round");
            assertEquals(4,aiCalls.get(),"Only one supplement is permitted per request");
            assertFalse(partial.path("notice").asString().contains("조건을 줄이면"));
        }finally{if(app!=null)app.close();ai.stop(0);mb.stop(0);}
    }

    @Test void legitimateReleaseMetadataLargerThanOneMegabyteCanStillVerifyARecording() throws Exception {
        String id=UUID.randomUUID().toString(),releaseId=UUID.randomUUID().toString();
        var row=new LinkedHashMap<>(recording(id,"Large release metadata song","Verified artist","",releaseId));
        row.put("releases",List.of(Map.of("id",releaseId,"title","release metadata ".repeat(70_000))));
        byte[] bytes=json.writeValueAsBytes(Map.of("recordings",List.of(row)));assertTrue(bytes.length>1_000_000);
        HttpServer mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mb.createContext("/recording/",exchange->{exchange.sendResponseHeaders(200,bytes.length);try{exchange.getResponseBody().write(bytes);}finally{exchange.close();}});mb.start();
        ConfigurableApplicationContext app=null;
        try {
            String endpoint="http://127.0.0.1:"+mb.getAddress().getPort();
            app=start("jdbc:h2:mem:large-recording-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",endpoint+"/unused",endpoint+"/recording/");
            var result=app.getBean(RecommendationCatalogue.class).resolve(List.of(new OpenAi.Suggestion("Large release metadata song","Verified artist",List.of("genre:shoegaze"),"en")));
            assertEquals(List.of(id),result.stream().map(track->track.id).toList());
            assertEquals("https://coverartarchive.org/release/"+releaseId+"/front-500",result.get(0).artworkUrl);
        }finally{if(app!=null)app.close();mb.stop(0);}
    }

    @Test void stalledCatalogueResponseRetriesWithinTheSharedTimeBudget() throws Exception {
        var calls=new AtomicInteger();String id=UUID.randomUUID().toString();
        HttpServer mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mb.createContext("/recording/",exchange->{
            if(calls.incrementAndGet()==1) {
                try {Thread.sleep(6500);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                exchange.close();return;
            }
            respond(exchange,200,Map.of("recordings",List.of(recording(id,"Timed song","Timed artist","",UUID.randomUUID().toString()))));
        });mb.start();ConfigurableApplicationContext app=null;
        try {
            String endpoint="http://127.0.0.1:"+mb.getAddress().getPort();
            app=start("jdbc:h2:mem:timeout-retry-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",endpoint+"/unused",endpoint+"/recording/");
            long started=System.nanoTime();
            var tracks=app.getBean(RecommendationCatalogue.class).resolve(List.of(new OpenAi.Suggestion("Timed song","Timed artist",List.of("jazz"),"en")));
            assertEquals(List.of(id),tracks.stream().map(track->track.id).toList());assertEquals(2,calls.get());
            long elapsed=System.nanoTime()-started;
            assertTrue(elapsed>=Duration.ofSeconds(5).toNanos());assertTrue(elapsed<Duration.ofMillis(12500).toNanos());
        }finally{if(app!=null)app.close();mb.stop(0);}
    }

    @Test void transientCatalogueFailureRetriesOnceAtProviderRateAndPermanentFailureStops() throws Exception {
        var calls=new AtomicInteger();var timestamps=new CopyOnWriteArrayList<Long>();
        String id=UUID.randomUUID().toString(),releaseId=UUID.randomUUID().toString();
        var failure=new AtomicInteger(503);
        HttpServer mb=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mb.createContext("/recording/",exchange->{
            timestamps.add(System.nanoTime());int call=calls.incrementAndGet();
            respond(exchange,call==1?503:failure.get(),Map.of("recordings",List.of(recording(id,"Retry song","Retry artist","",releaseId))));
        });mb.start();ConfigurableApplicationContext app=null;
        try {
            String endpoint="http://127.0.0.1:"+mb.getAddress().getPort();
            app=start("jdbc:h2:mem:retry-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",endpoint+"/unused",endpoint+"/recording/");
            var catalogue=app.getBean(RecommendationCatalogue.class);
            var song=new OpenAi.Suggestion("Retry song","Retry artist",List.of("genre:retry_genre"),"en");
            failure.set(200);
            assertEquals(List.of(id),catalogue.resolve(List.of(song)).stream().map(track->track.id).toList());
            assertEquals(2,calls.get());assertTrue(timestamps.get(1)-timestamps.get(0)>=Duration.ofMillis(900).toNanos());
            var missing=new OpenAi.Suggestion("Another song","Another artist",List.of("genre:retry_genre"),"en");
            failure.set(503);long started=System.nanoTime();
            assertEquals("CATALOGUE_HTTP_503",assertThrows(IllegalStateException.class,()->catalogue.resolve(List.of(missing))).getMessage());
            assertEquals(4,calls.get());assertTrue(System.nanoTime()-started<Duration.ofSeconds(8).toNanos());
            failure.set(400);
            assertEquals("CATALOGUE_HTTP_400",assertThrows(IllegalStateException.class,()->catalogue.resolve(List.of(missing))).getMessage());
            assertEquals(5,calls.get(),"Permanent errors must not be retried");
            assertEquals(1,app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM recommendation_tracks",Integer.class));
        }finally{if(app!=null)app.close();mb.stop(0);}
    }

    @Test void planRejectsMalformedCandidatesAndRetainsConditionOnlyCompatibility() throws Exception {
        AtomicReference<Object> payload=new AtomicReference<>(plan("jazz",List.of(song("Take Five","The Dave Brubeck Quartet",List.of("jazz","instrumental"),"none"))));
        AtomicReference<JsonNode> request=new AtomicReference<>();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{request.set(json.readTree(exchange.getRequestBody().readAllBytes()));respond(exchange,200,envelope(payload.get()));});ai.start();
        try {
            OpenAi api=new OpenAi(json,"check-only","check-model","http://127.0.0.1:"+ai.getAddress().getPort()+"/responses");
            OpenAi.Plan valid=api.plan("재즈만 추천해줘",List.of(),null,Map.of("warm",1));
            assertEquals(1,valid.candidates().size());assertEquals(List.of("jazz","instrumental"),valid.candidates().get(0).tags());assertEquals("none",valid.candidates().get(0).language());
            payload.set(plan("genre:nu_metal",List.of(song("Faint","Linkin Park",List.of("genre:nu_metal"),"en"))));
            Map<String,Integer> manyLikes=new LinkedHashMap<>();for(int i=0;i<80;i++)manyLikes.put("genre:free_"+i,i+1);
            OpenAi.Plan subgenre=api.plan("nu metal",List.of(),null,manyLikes);
            assertEquals(List.of("genre:nu_metal"),subgenre.conditions().tags,"A partial dictionary match on metal must not erase the model's specific genre.");
            String instructions=request.get().path("instructions").asString();assertEquals(64,java.util.regex.Pattern.compile("genre:free_[0-9]+\"").matcher(instructions).results().count());
            assertTrue(instructions.contains("preserving their native writing system"));
            assertTrue(instructions.contains("neighboring styles and shared instruments are NOT synonyms"));
            assertTrue(instructions.contains("genre:free_79"));assertFalse(instructions.contains("\"genre:free_0\""));
            for(var languages:Map.of("일본어 말고 프랑스어","fr","프랑스어 말고 일본어","ja","영어와 일본어 제외하고 프랑스어","fr","French or Japanese","fr","힌디어 노래","hi").entrySet()) {
                payload.set(Map.of("tags",List.of("genre:chanson"),"excludedTags",List.of(),"language",languages.getValue(),"languageSpecified",true,"candidates",List.of()));
                assertEquals(languages.getValue(),api.plan(languages.getKey(),List.of(),null,Map.of()).conditions().language);
            }
            Map<String,Object> legacy=new LinkedHashMap<>(plan("jazz",List.of()));legacy.remove("candidates");payload.set(legacy);
            assertTrue(api.plan("재즈만 추천해줘",List.of(),null,Map.of()).candidates().isEmpty());
            Map<String,Object> good=song("Take Five","The Dave Brubeck Quartet",List.of("jazz","instrumental"),"none");
            List<Object> malformed=List.of("not an array",Collections.nCopies(13,good),List.of(song("","Artist",List.of(),"")),
                List.of(song("a".repeat(161),"Artist",List.of(),"")),List.of(song("Song","a".repeat(161),List.of(),"")),
                List.of(song("Song","Artist",List.of("invented"),"")),List.of(song("Song","Artist",List.of(),"not_a_language")),
                List.of(song("Song","Artist",List.of("instrumental"),"ko")),List.of(song("Song","Artist",List.of("jazz"),"none")),List.of(Map.of("title",7,"artist","Artist","tags",List.of(),"language","")));
            for(Object invalid:malformed) {
                Map<String,Object> changed=new LinkedHashMap<>(legacy);changed.put("candidates",invalid);payload.set(changed);
                assertThrows(Exception.class,()->api.plan("재즈만 추천해줘",List.of(),null,Map.of()),"Malformed suggestions must not become catalogue rows.");
            }
            Map<String,Object> missingFlag=new LinkedHashMap<>(legacy);missingFlag.remove("languageSpecified");payload.set(missingFlag);
            assertThrows(Exception.class,()->api.plan("재즈만 추천해줘",List.of(),null,Map.of()));
        } finally {ai.stop(0);}
    }

    private ConfigurableApplicationContext start(String database,String username,String password,String ai,String mb) {
        var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url="+database,"--spring.datasource.username="+username,"--spring.datasource.password="+password,
            "--taptap.openai.key=check-only","--taptap.openai.model=check-model","--taptap.openai.endpoint="+ai,"--taptap.catalogue.endpoint="+mb,
            "--taptap.ai.daily-limit=100","--taptap.ai.user-hourly-limit=100","--logging.level.root=WARN","--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();return context;
    }
    private HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode value(String method,String path,String token,Object body,int status) throws Exception {
        var response=request(method,path,token,body);assertEquals(status,response.statusCode(),response.body());
        return response.body().isEmpty()?json.createObjectNode():json.readTree(response.body());
    }
    private static List<String> ids(JsonNode rows) {
        List<String> result=new ArrayList<>();for(JsonNode row:rows)result.add(row.isString()?row.asString():row.path("track").path("id").asString());return result;
    }
    private static Map<String,Object> song(String title,String artist,List<String> tags,String language) {return Map.of("title",title,"artist",artist,"tags",tags,"language",language);}
    private static Map<String,Object> plan(String genre,List<Map<String,Object>> songs) {return Map.of("tags",List.of(genre),"excludedTags",List.of(),"language","","languageSpecified",false,"candidates",songs);}
    private static Map<String,Object> recording(String id,String title,String artist,String version,String release) {
        return Map.of("id",id,"title",title,"video",false,"disambiguation",version,
            "artist-credit",List.of(Map.of("name",artist,"artist",Map.of("name",artist),"joinphrase","")),"releases",List.of(Map.of("id",release)));
    }
    private Map<String,Object> envelope(Object payload) {return Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(payload))))));}
    private void respond(HttpExchange exchange,int status,Object body) throws java.io.IOException {
        byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
}
