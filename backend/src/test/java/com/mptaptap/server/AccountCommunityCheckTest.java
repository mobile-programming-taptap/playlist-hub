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
        return start(database,"");
    }
    private ConfigurableApplicationContext start(String database,String supportEmail) {
        var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url="+database,"--spring.datasource.username="+System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"),
            "--spring.datasource.password="+System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD",""),"--taptap.openai.key=",
            "--taptap.openai.endpoint=https://api.openai.com/v1/responses","--taptap.support-email="+supportEmail,"--logging.level.root=WARN","--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort(); return context;
    }

    @Test void registeredSessionExpiresWithoutDeletingAccountOrOtherDevices() throws Exception {
        try(var context=start("jdbc:h2:mem:lifetime"+UUID.randomUUID()+";MODE=PostgreSQL")) {
            Store store=context.getBean(Store.class);JdbcTemplate db=context.getBean(JdbcTemplate.class);
            var platforms=new PlatformStore(db,context.getBean(org.springframework.transaction.PlatformTransactionManager.class),json,java.util.Base64.getEncoder().encodeToString(new byte[32]));
            String email="expiry-"+UUID.randomUUID()+"@example.test",password="Session-lifetime-check!";
            JsonNode anon=value("POST","/sessions",null,null,201);
            String owner=anon.path("userId").asString(),anonymous=anon.path("token").asString();
            db.update("UPDATE users SET created_at=? WHERE id=?",Instant.now().minus(Duration.ofDays(365)).toString(),owner);
            assertEquals(owner,store.user("Bearer "+anonymous),"Anonymous data must not be erased or made inaccessible by account-session expiry.");
            String playlist=value("POST","/playlists",anonymous,Map.of("title","Preserved after expiry"),201).path("id").asString();
            String first=value("POST","/accounts/register",anonymous,Map.of("email",email,"password",password,"name","Expiry check"),201).path("token").asString();
            String second=value("POST","/accounts/login",null,Map.of("email",email,"password",password),200).path("token").asString();
            String state=platforms.start(owner,"youtube","Bearer "+first).get("state");
            Instant created=Instant.parse("2026-10-01T00:00:00.123456Z"),boundary=created.plus(Store.ACCOUNT_SESSION_LIFETIME);
            db.update("UPDATE account_sessions SET created_at=? WHERE token_hash=?",created.toString(),PlatformStore.hash(first));
            assertEquals(owner,store.user("Bearer "+first,boundary.minusNanos(1)));
            assertThrows(Store.SessionExpired.class,()->store.user("Bearer "+first,boundary));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM account_sessions WHERE user_id=?",Integer.class,owner));
            assertEquals(owner,value("GET","/accounts/me",second,null,200).path("userId").asString());
            assertThrows(IllegalArgumentException.class,()->platforms.pending(state,"youtube",true));
            String third=value("POST","/accounts/login",null,Map.of("email",email,"password",password),200).path("token").asString();
            String expiredState=platforms.start(owner,"youtube","Bearer "+third).get("state");
            db.update("UPDATE account_sessions SET created_at=? WHERE token_hash=?",Instant.now().minus(Duration.ofDays(31)).toString(),PlatformStore.hash(third));
            assertThrows(IllegalArgumentException.class,()->platforms.pending(expiredState,"youtube",true),"The callback must reject an expired session even before that token has made an API request.");
            assertEquals("SESSION_EXPIRED",value("GET","/playlists",third,null,401).path("code").asString());
            assertEquals("Preserved after expiry",value("GET","/playlists/"+playlist,second,null,200).path("title").asString());
            value("DELETE","/accounts/me",second,null,400);
            assertEquals(403,assertThrows(ResponseStatusException.class,()->store.deleteAnonymous("Bearer "+second)).getStatusCode().value());
            value("GET","/accounts/me",second,null,200);
            value("DELETE","/accounts/me",second,Map.of("password",password),204);
        }
    }

    @Test void anonymousCanDeleteOwnServerDataWithoutRegistering() throws Exception {
        try(var context=start("jdbc:h2:mem:anonymousDeletion"+UUID.randomUUID()+";MODE=PostgreSQL")) {
            Store store=context.getBean(Store.class);JdbcTemplate db=context.getBean(JdbcTemplate.class);
            var platforms=new PlatformStore(db,context.getBean(org.springframework.transaction.PlatformTransactionManager.class),json,java.util.Base64.getEncoder().encodeToString(new byte[32]));
            JsonNode anon=value("POST","/sessions",null,null,201),other=value("POST","/sessions",null,null,201);
            String owner=anon.path("userId").asString(),token=anon.path("token").asString(),otherToken=other.path("token").asString();
            String playlist=value("POST","/playlists",token,Map.of("title","Erase own data"),201).path("id").asString();
            String track=value("GET","/catalogue",token,null,200).get(0).path("id").asString();
            value("POST","/playlists/"+playlist+"/items",token,Map.of("trackId",track),200);
            value("PUT","/me/feedback/"+track,token,Map.of("reaction",1),200);
            store.reserveAi(owner);
            platforms.connection(owner,"youtube",new PlatformClient.Tokens("fixture-access","fixture-refresh","fixture-channel","",Instant.now().plusSeconds(3600)));
            platforms.start(owner,"youtube","Bearer "+token);
            platforms.create(owner,UUID.randomUUID().toString(),"import","youtube",Map.of("items",List.of()));
            String otherPlaylist=value("POST","/playlists",otherToken,Map.of("title","Other owner's data"),201).path("id").asString();
            value("DELETE","/accounts/me",null,null,401);
            value("DELETE","/accounts/me",token,null,204);
            for(String table:List.of("recommendation_feedback","ai_usage","playlists","platform_connections","platform_oauth_states","platform_documents"))
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,owner),table);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,owner));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?",Integer.class,playlist));
            String fresh=value("POST","/sessions",null,null,201).path("token").asString();
            value("DELETE","/accounts/me",token,null,401);
            assertEquals(0,value("GET","/playlists",fresh,null,200).size(),"A replay must not touch a replacement anonymous session.");
            assertEquals("Other owner's data",value("GET","/playlists/"+otherPlaylist,otherToken,null,200).path("title").asString());
        }
    }

    @Test void accountMigrationDeviceSessionsAndExternalSnapshots() throws Exception {
        String database=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_URL","jdbc:h2:mem:community"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String email="check-"+UUID.randomUUID()+"@example.test", password="Only-for-checks!2026";
        List<String> owners=new ArrayList<>(); ConfigurableApplicationContext context=start(database,"help&demo@example.test");
        try {
            Store store=context.getBean(Store.class); JdbcTemplate db=context.getBean(JdbcTemplate.class);
            for(String route:List.of("/","/privacy")) {
                HttpResponse<String> page=request("GET",route,null,null); assertEquals(200,page.statusCode());
                assertTrue(page.headers().firstValue("Content-Type").orElse("").contains("text/html"));
                assertEquals("no-store",page.headers().firstValue("Cache-Control").orElse(""));
                assertEquals("nosniff",page.headers().firstValue("X-Content-Type-Options").orElse(""));
                assertEquals("no-referrer",page.headers().firstValue("Referrer-Policy").orElse(""));
                assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
                assertTrue(page.headers().firstValue("Set-Cookie").isEmpty());
                assertTrue(page.body().contains("href=\"/privacy\"")); assertTrue(page.body().contains("개발·시연"));
                assertFalse(page.body().contains("Apple Music"));assertFalse(page.body().contains("MusicKit"));
                assertTrue(page.body().contains("help&amp;demo@example.test")); assertTrue(page.body().contains("mailto:help%26demo@example.test"));
                assertFalse(page.body().contains("<script"));
            }
            String invalidContact=new PublicPagesApi("bad@example.test?subject=<script>alert(1)</script>").privacy().getBody();
            assertFalse(invalidContact.contains("mailto:")); assertFalse(invalidContact.contains("<script"));
            assertTrue(invalidContact.contains("운영자 문의 이메일은 아직 설정되지 않았습니다"));
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

            Map<String,Object> oldSnapshot=new LinkedHashMap<>(cast(edited.path("track"))); oldSnapshot.put("checkedAt",Instant.now().minus(Duration.ofDays(31)).toString());
            db.update("UPDATE playlist_items SET track_json=? WHERE id=?",json.writeValueAsString(oldSnapshot),editable);
            store.purgeExpiredExternal();
            JsonNode expired=value("GET",path,first,null,200).path("items").get(2).path("track");
            assertTrue(expired.path("needsRefresh").asBoolean()); assertFalse(expired.has("title")); assertFalse(expired.has("artist")); assertFalse(expired.has("sourceRow"));
            assertEquals("dQw4w9WgXcQ",expired.path("sourceId").asString());
            assertFalse(json.readTree(db.queryForObject("SELECT track_json FROM playlist_items WHERE id=?",String.class,editable)).has("title"));
            assertTrue(value("GET",path,first,null,200).path("items").get(0).path("track").has("title"),"Catalogue snapshots do not expire.");
            value("PUT",path+"/items/"+editable,first,Map.of("title","Expired edit","artist","Artist"),409);
            value("DELETE","/accounts/session",first,null,204);
            value("GET","/accounts/me",first,null,401); value("GET","/accounts/me",second,null,200);
            String oldReader=reader;
            reader=value("POST","/accounts/register",reader,Map.of("email","reader-"+UUID.randomUUID()+"@example.test","password",password,"name","Reader"),201).path("token").asString();
            value("GET","/playlists",oldReader,null,401);

            context.close(); context=start(database); db=context.getBean(JdbcTemplate.class);
            assertFalse(request("GET","/privacy",null,null).body().contains("mailto:"),"An unset operator address must not invent a contact channel.");
            assertEquals(owner,value("GET","/accounts/me",second,null,200).path("userId").asString());
            assertEquals(3,value("GET",path,second,null,200).path("items").size());
            assertEquals(1,value("GET","/me/feedback",second,null,200).path(track).asInt());
            String third=value("POST","/accounts/login",null,Map.of("email",email,"password",password),200).path("token").asString();
            JsonNode fresh=value("POST","/accounts/register",null,Map.of("email","fresh-"+UUID.randomUUID()+"@example.test","password",password,"name","Fresh"),201);
            owners.add(fresh.path("userId").asString());
            value("GET",path,fresh.path("token").asString(),null,404);
            value("DELETE","/accounts/me",fresh.path("token").asString(),Map.of("password",password),204);
            value("DELETE","/accounts/me",second,Map.of("password","wrong"),401);
            value("DELETE","/accounts/me",second,Map.of("password",password),204);
            value("GET","/accounts/me",second,null,401); value("GET","/accounts/me",third,null,401);
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
