package com.mptaptap.server;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

    @Test void recommendationOwnershipStorageAndOpenAiContract() throws Exception {
        List<JsonNode> aiRequests=new CopyOnWriteArrayList<>();
        HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/v1/responses",exchange->{
            JsonNode incoming=json.readTree(exchange.getRequestBody().readAllBytes());aiRequests.add(incoming);
            String conditions="{\"tags\":[\"calm\",\"piano\",\"instrumental\",\"study\"],\"excludedTags\":[],\"language\":\"none\"}";
            if(incoming.path("input").asString().equals("잘못된 AI 응답 검사"))conditions="{\"tags\":[\"invented\"],\"excludedTags\":[],\"language\":\"none\"}";
            byte[] reply=json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",conditions))))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });stub.start();
        String database="jdbc:h2:mem:check"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        String endpoint="http://127.0.0.1:"+stub.getAddress().getPort()+"/v1/responses";
        String token;
        String playlist;
        String saved;
        try {
            try(ConfigurableApplicationContext context=start(database,endpoint)) {
                base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
                assertEquals(40,value("GET","/health",null,null,200).path("catalogueSize").asInt());
                value("GET","/playlists",null,null,401);
                token=value("POST","/sessions",null,null,201).path("token").asString();
                String other=value("POST","/sessions",null,null,201).path("token").asString();
                JsonNode found=value("GET","/catalogue?q=blackpink",token,null,200);
                assertTrue(found.size()>0); for(JsonNode t:found)assertTrue(t.path("artist").asString().toLowerCase().contains("blackpink"));
                assertEquals(0,value("GET","/catalogue?q=no-such-track",token,null,200).size());
                value("GET","/catalogue?q="+"x".repeat(101),token,null,400);
                value("POST","/recommendations",token,Map.of("prompt"," "),400);
                JsonNode recovered=value("POST","/recommendations",token,Map.of("prompt","잘못된 AI 응답 검사"),200);
                assertEquals("basic",recovered.path("mode").asString());
                assertTrue(recovered.path("notice").asString().contains("AI 연결"));
                String prompt="공부할 때 가사 없는 잔잔한 피아노 음악";
                JsonNode result=value("POST","/recommendations",token,Map.of("prompt",prompt),200);
                assertEquals("openai",result.path("mode").asString());assertEquals(8,result.path("tracks").size());
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
                assertEquals("basic",value("POST","/recommendations",token,Map.of("prompt",prompt),200).path("mode").asString());
                assertEquals(3,aiRequests.size(),"The daily cap must include failed calls and prevent extra calls.");
                for(JsonNode body:aiRequests){
                    assertTrue(List.of(prompt,"잘못된 AI 응답 검사").contains(body.path("input").asString()));assertFalse(body.path("store").asBoolean());
                    assertEquals("json_schema",body.path("text").path("format").path("type").asString());
                    assertTrue(body.path("text").path("format").path("strict").asBoolean());
                    assertFalse(body.toString().contains(saved),"Catalogue and external metadata must not enter the AI request.");
                }
                value("PUT","/me/feedback/"+dislike,token,Map.of("reaction",2),400);
                value("DELETE","/me/feedback/"+dislike,token,null,204);
                assertEquals(0,value("GET","/me/feedback",token,null,200).size());
                value("POST","/playlists",token,Map.of("title"," "),400);
                playlist=value("POST","/playlists",token,Map.of("title","공부 음악"),201).path("id").asString();
                value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId","invented"),404);
                JsonNode first=value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId",saved),200);
                assertEquals(first,value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId",saved),200));
                assertEquals(1,value("GET","/playlists/"+playlist,token,null,200).path("items").size());
                org.springframework.jdbc.core.JdbcTemplate db=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                String snapshot=db.queryForObject("SELECT track_json FROM playlist_items WHERE id=?",String.class,first.path("id").asString());
                assertEquals("MusicBrainz",json.readTree(snapshot).path("source").asString());
                assertEquals("https://musicbrainz.org/recording/"+saved,json.readTree(snapshot).path("sourceUrl").asString());
                value("GET","/playlists/"+playlist,other,null,404);
                value("POST","/playlists/"+playlist+"/items",other,Map.of("trackId",saved),404);
                value("DELETE","/playlists/"+playlist+"/items/"+first.path("id").asString(),other,null,404);
                assertEquals(1,value("GET","/me/saved-tracks",token,null,200).size());
                assertEquals("search",value("GET","/tracks/"+saved+"/links",token,null,200).get(0).path("kind").asString());
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
            }
            try(ConfigurableApplicationContext context=start(database,endpoint)) {
                base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
                JsonNode item=value("GET","/playlists/"+playlist,token,null,200).path("items").get(0);
                assertEquals(saved,item.path("track").path("id").asString());
                assertEquals("https://musicbrainz.org/recording/"+saved,item.path("track").path("sourceUrl").asString());
                value("DELETE","/playlists/"+playlist+"/items/"+item.path("id").asString(),token,null,204);
                assertEquals(0,value("GET","/playlists/"+playlist,token,null,200).path("items").size());
            }
        } finally { stub.stop(0); }
    }

    private ConfigurableApplicationContext start(String database,String endpoint) {
        return new SpringApplicationBuilder(TapTapApplication.class).run("--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url="+database,"--taptap.openai.key=check-only","--taptap.openai.endpoint="+endpoint,
            "--taptap.ai.daily-limit=3","--logging.level.root=WARN","--spring.main.banner-mode=off");
    }
}
