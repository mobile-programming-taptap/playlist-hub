package com.mptaptap.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AccountCommunityCheckTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();
    private String base;

    private HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode value(String method,String path,String token,Object body,int expected) throws Exception {
        var response=request(method,path,token,body);
        assertEquals(expected,response.statusCode(),method+" "+path);
        return response.body().isEmpty()?json.createObjectNode():json.readTree(response.body());
    }
    private ConfigurableApplicationContext start(String database) {
        var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url="+database,"--spring.datasource.username="+System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"),
            "--spring.datasource.password="+System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD",""),"--taptap.openai.key=",
            "--taptap.openai.endpoint=https://api.openai.com/v1/responses","--logging.level.root=WARN","--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort(); return context;
    }

    @Test void accountMigrationDeviceSessionsPublicOwnershipAndExternalSnapshots() throws Exception {
        String database=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_URL","jdbc:h2:mem:community"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String email="check-"+UUID.randomUUID()+"@example.test", password="Only-for-checks!2026";
        List<String> owners=new ArrayList<>(); ConfigurableApplicationContext context=start(database);
        try {
            Store store=context.getBean(Store.class); JdbcTemplate db=context.getBean(JdbcTemplate.class);
            assertFalse(value("GET","/health",null,null,200).path("aiConfigured").asBoolean());
            JsonNode anon=value("POST","/sessions",null,null,201), other=value("POST","/sessions",null,null,201);
            owners.add(anon.path("userId").asString()); owners.add(other.path("userId").asString());
            String anonymous=anon.path("token").asString(), reader=other.path("token").asString(), owner=owners.get(0);
            String track=value("GET","/catalogue",anonymous,null,200).get(0).path("id").asString();
            String playlist=value("POST","/playlists",anonymous,Map.of("title","<script>Private title</script>"),201).path("id").asString();
            String path="/playlists/"+playlist;
            value("POST",path+"/items",anonymous,Map.of("trackId",track),200);
            value("PUT","/me/feedback/"+track,anonymous,Map.of("reaction",1),200);
            value("POST","/accounts/register",anonymous,Map.of("email",email,"password","too-short","name","Owner"),400);
            JsonNode registered=value("POST","/accounts/register",anonymous,Map.of("email",email.toUpperCase(),"password",password,"name","<b>Owner</b>"),201);
            String first=registered.path("token").asString(); assertEquals(owner,registered.path("userId").asString());
            assertEquals(email,registered.path("email").asString()); assertFalse(registered.has("password"));
            value("GET","/playlists",anonymous,null,401);
            assertEquals(1,value("GET","/me/feedback",first,null,200).path(track).asInt());
            assertEquals(1,value("GET",path,first,null,200).path("items").size());
            value("POST","/accounts/register",reader,Map.of("email",email,"password",password,"name","Duplicate"),409);
            value("POST","/accounts/login",null,Map.of("email",email,"password","wrong"),401);
            String second=value("POST","/accounts/login",null,Map.of("email",email,"password",password),200).path("token").asString();
            assertNotEquals(first,second); assertEquals(owner,value("GET","/accounts/me",second,null,200).path("userId").asString());
            value("GET","/accounts/me",reader,null,404); value("GET",path,reader,null,404);
            assertEquals(40,value("GET","/catalogue",reader,null,200).size(),"A valid anonymous session remains valid when it has no account profile.");
            String encoded=db.queryForObject("SELECT password_hash FROM accounts WHERE user_id=?",String.class,owner);
            assertTrue(encoded.startsWith("pbkdf2-sha256$600000$")); assertFalse(encoded.contains(password));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM account_sessions WHERE user_id=?",Integer.class,owner));

            String source=UUID.randomUUID().toString(), anotherSource=UUID.randomUUID().toString();
            Map<String,Object> row0=external(0,"Raw title - raw artist"), row1=external(1,"Repeated title - raw artist");
            List<Map<String,Object>> imported=store.addExternal(owner,playlist,List.of(row0,row1),source);
            assertEquals(2,imported.size()); assertNotEquals(imported.get(0).get("id"),imported.get(1).get("id"));
            assertEquals(imported,store.addExternal(owner,playlist,List.of(row0,row1),source));
            Map<String,Object> unknownDuration=new LinkedHashMap<>(external(5,"Other import")); unknownDuration.put("durationMs",0);
            store.addExternal(owner,playlist,List.of(unknownDuration),anotherSource);
            assertEquals(4,value("GET",path,first,null,200).path("items").size());
            assertEquals(1,value("GET","/me/saved-tracks",first,null,200).size(),"External metadata never becomes recommendation feedback.");
            Map<String,Object> changed=new LinkedHashMap<>(row1); changed.put("title","Reviewed change");
            changed.put("sourceType","timeline"); changed.put("durationMs",10_000); changed.put("videoDurationSeconds",300);
            assertEquals(409,assertThrows(ResponseStatusException.class,()->store.addExternal(owner,playlist,List.of(external(3,"New"),changed),source)).getStatusCode().value());
            assertEquals(4,value("GET",path,first,null,200).path("items").size(),"The failed import rolls back every row.");
            List<Map<String,Object>> refreshed=store.refreshExternal(owner,playlist,List.of(changed),source);
            assertEquals(imported.get(1).get("id"),refreshed.get(0).get("id"));
            JsonNode detail=value("GET",path,second,null,200); assertEquals(3,detail.path("items").size());
            assertEquals(track,detail.path("items").get(0).path("trackId").asString());
            assertEquals("Other import",detail.path("items").get(1).path("track").path("sourceRow").asString());
            String editable=(String)refreshed.get(0).get("id");
            JsonNode edited=value("PUT",path+"/items/"+editable,second,Map.of("title","<img src=x onerror=alert(1)>","artist","Artist","seconds",30),200);
            assertTrue(edited.path("trackId").isNull()); assertEquals(30,edited.path("track").path("timestamp").asInt());
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s",edited.path("track").path("sourceUrl").asString());
            assertEquals(60,value("PUT",path+"/items/"+detail.path("items").get(1).path("id").asString(),second,Map.of("title","Unknown duration","artist","Artist","seconds",60),200).path("track").path("timestamp").asInt());
            value("PUT",path+"/items/"+editable,reader,Map.of("title","Stolen","artist","Artist"),404);
            value("PUT",path+"/items/"+editable,second,Map.of("title","Bad time","artist","Artist","seconds",301),400);
            value("PUT",path+"/items/"+detail.path("items").get(0).path("id").asString(),second,Map.of("title","Catalogue change","artist","Artist"),409);
            assertFalse(value("GET",path+"/publication",first,null,200).path("public").asBoolean());
            value("PUT",path+"/publication",reader,Map.of("public",true),404);
            String share=value("PUT",path+"/publication",first,Map.of("public",true),200).path("shareId").asString();
            assertTrue(share.matches("[0-9a-f]{32}"));
            JsonNode shared=value("GET","/public/playlists/"+share,null,null,200);
            assertEquals(3,shared.path("size").asInt()); assertFalse(shared.has("email")); assertFalse(shared.has("userId"));
            assertEquals("MusicBrainz",shared.path("items").get(0).path("track").path("source").asString());
            HttpResponse<String> html=request("GET","/share/"+share,null,null); assertEquals(200,html.statusCode());
            assertTrue(html.body().contains("&lt;script&gt;")); assertTrue(html.body().contains("&lt;img src=x onerror=alert(1)&gt;"));
            assertFalse(html.body().contains("<script>")); assertTrue(html.headers().firstValue("Content-Security-Policy").isPresent());
            assertEquals(1,value("PUT","/public/playlists/"+share+"/favourite",first,Map.of("favourite",true),200).path("favourites").asInt());
            assertEquals(1,value("PUT","/public/playlists/"+share+"/favourite",second,Map.of("favourite",true),200).path("favourites").asInt());
            assertEquals(2,value("PUT","/public/playlists/"+share+"/favourite",reader,Map.of("favourite",true),200).path("favourites").asInt());
            assertEquals(share,value("GET","/public/playlists?sort=popular",reader,null,200).get(0).path("shareId").asString());
            assertEquals(1,value("GET","/public/playlists?favourites=true",reader,null,200).size());
            value("GET","/public/playlists?favourites=true",null,null,401);
            String newerPlaylist=value("POST","/playlists",first,Map.of("title","Newer public list"),201).path("id").asString();
            String newerShare=value("PUT","/playlists/"+newerPlaylist+"/publication",first,Map.of("public",true),200).path("shareId").asString();
            db.update("UPDATE public_playlists SET published_at=? WHERE share_id=?","2026-10-04T00:00:00.1Z",share);
            db.update("UPDATE public_playlists SET published_at=? WHERE share_id=?","2026-10-04T00:00:00.11Z",newerShare);
            JsonNode recent=value("GET","/public/playlists?sort=recent",reader,null,200);
            assertEquals(newerShare,recent.get(0).path("shareId").asString(),"Fractional seconds must be compared as instants, not strings");
            assertEquals(share,recent.get(1).path("shareId").asString());
            assertEquals(share,value("GET","/public/playlists?sort=popular",reader,null,200).get(0).path("shareId").asString(),"Favourite count takes priority over recency");
            value("PUT","/public/playlists/"+newerShare+"/favourite",first,Map.of("favourite",true),200);
            value("PUT","/public/playlists/"+newerShare+"/favourite",reader,Map.of("favourite",true),200);
            JsonNode popular=value("GET","/public/playlists?sort=popular",reader,null,200);
            assertEquals(newerShare,popular.get(0).path("shareId").asString(),"Equal favourite counts use the actual newest instant");
            assertEquals(share,popular.get(1).path("shareId").asString());
            value("DELETE","/playlists/"+newerPlaylist,first,null,204);
            Map<String,String> copyBody=Map.of("title","Copied public list","id",UUID.randomUUID().toString());
            String copy=value("POST","/public/playlists/"+share+"/copy",reader,copyBody,201).path("id").asString();
            assertEquals(copy,value("POST","/public/playlists/"+share+"/copy",reader,copyBody,201).path("id").asString());
            assertEquals(1,value("GET","/playlists",reader,null,200).size());
            JsonNode copied=value("GET","/playlists/"+copy,reader,null,200).path("items");
            assertEquals(shared.path("items").get(0).path("track"),copied.get(0).path("track"));
            assertEquals(shared.path("items").get(2).path("track"),copied.get(2).path("track"));
            assertFalse(value("GET","/playlists/"+copy+"/publication",reader,null,200).path("public").asBoolean());
            value("PUT",path+"/publication",first,Map.of("public",false),200);
            value("GET","/public/playlists/"+share,null,null,404);
            value("PUT","/public/playlists/"+share+"/favourite",reader,Map.of("favourite",true),404);
            value("POST","/public/playlists/"+share+"/copy",reader,null,404);
            assertEquals(0,value("GET","/public/playlists?favourites=true",reader,null,200).size());
            String republished=value("PUT",path+"/publication",first,Map.of("public",true),200).path("shareId").asString(); assertNotEquals(share,republished);
            value("PUT","/public/playlists/"+republished+"/favourite",reader,Map.of("favourite",true),200);

            Map<String,Object> oldSnapshot=new LinkedHashMap<>(cast(edited.path("track"))); oldSnapshot.put("checkedAt",Instant.now().minus(Duration.ofDays(31)).toString());
            db.update("UPDATE playlist_items SET track_json=? WHERE id=?",json.writeValueAsString(oldSnapshot),editable);
            Map<String,Object> oldPublic=cast(value("GET","/public/playlists/"+republished,null,null,200));
            @SuppressWarnings("unchecked") List<Map<String,Object>> publicItems=(List<Map<String,Object>>)oldPublic.get("items");
            publicItems.get(2).put("track",oldSnapshot);
            db.update("UPDATE public_playlists SET snapshot_json=? WHERE share_id=?",json.writeValueAsString(oldPublic),republished);
            store.purgeExpiredExternal();
            JsonNode expired=value("GET",path,first,null,200).path("items").get(2).path("track");
            assertTrue(expired.path("needsRefresh").asBoolean()); assertFalse(expired.has("title")); assertFalse(expired.has("artist")); assertFalse(expired.has("sourceRow"));
            assertEquals("dQw4w9WgXcQ",expired.path("sourceId").asString());
            assertFalse(json.readTree(db.queryForObject("SELECT track_json FROM playlist_items WHERE id=?",String.class,editable)).has("title"));
            assertFalse(value("GET","/public/playlists/"+republished,null,null,200).path("items").get(2).path("track").has("title"));
            assertTrue(value("GET",path,first,null,200).path("items").get(0).path("track").has("title"),"Catalogue snapshots do not expire.");
            value("PUT",path+"/items/"+editable,first,Map.of("title","Expired edit","artist","Artist"),409);
            value("DELETE","/accounts/session",first,null,204);
            value("GET","/accounts/me",first,null,401); value("GET","/accounts/me",second,null,200);
            String oldReader=reader;
            reader=value("POST","/accounts/register",reader,Map.of("email","reader-"+UUID.randomUUID()+"@example.test","password",password,"name","Reader"),201).path("token").asString();
            value("GET","/playlists",oldReader,null,401);
            assertEquals(1,value("GET","/public/playlists?favourites=true",reader,null,200).size(),"Registration retains the anonymous favourites and copied lists.");
            assertEquals(3,value("GET","/playlists/"+copy,reader,null,200).path("items").size());

            context.close(); context=start(database); db=context.getBean(JdbcTemplate.class);
            assertEquals(owner,value("GET","/accounts/me",second,null,200).path("userId").asString());
            assertEquals(3,value("GET",path,second,null,200).path("items").size());
            assertEquals(1,value("GET","/me/feedback",second,null,200).path(track).asInt());
            assertTrue(value("GET","/public/playlists/"+republished,reader,null,200).path("favourited").asBoolean());
            String third=value("POST","/accounts/login",null,Map.of("email",email,"password",password),200).path("token").asString();
            JsonNode fresh=value("POST","/accounts/register",null,Map.of("email","fresh-"+UUID.randomUUID()+"@example.test","password",password,"name","Fresh"),201);
            owners.add(fresh.path("userId").asString());
            value("GET",path,fresh.path("token").asString(),null,404);
            value("DELETE","/accounts/me",fresh.path("token").asString(),Map.of("password",password),204);
            value("DELETE","/accounts/me",second,Map.of("password","wrong"),401);
            value("DELETE","/accounts/me",second,Map.of("password",password),204);
            value("GET","/accounts/me",second,null,401); value("GET","/accounts/me",third,null,401);
            value("GET","/public/playlists/"+republished,null,null,404);
            assertEquals(0,value("GET","/public/playlists?favourites=true",reader,null,200).size());
            assertEquals(3,value("GET","/playlists/"+copy,reader,null,200).path("items").size(),"A copied private list belongs to its new owner.");
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM recommendation_feedback WHERE user_id=?",Integer.class,owner));
            String missing="missing-"+UUID.randomUUID()+"@example.test";
            for(int i=0;i<8;i++)value("POST","/accounts/login",null,Map.of("email",missing,"password",password),401);
            value("POST","/accounts/login",null,Map.of("email",missing,"password",password),429);
        } finally {
            if(context.isActive()) {
                JdbcTemplate db=context.getBean(JdbcTemplate.class);
                for(String owner:owners)db.update("DELETE FROM users WHERE id=?",owner);
                context.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> cast(JsonNode value) { return (Map<String,Object>)json.readValue(value.toString(),Map.class); }
    private static Map<String,Object> external(int index,String raw) {
        return Map.of("platform","youtube","externalId","dQw4w9WgXcQ","title","Imported title","artist","Imported artist",
            "sourceUrl","https://www.youtube.com/watch?v=dQw4w9WgXcQ","sourceIndex",index,"sourceRow",raw,"durationMs",300_000);
    }
}
