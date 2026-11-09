package com.mptaptap.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class CommunitySafetyCheckTest {
    @Test void explicitRulesBlocksAndReportsRemainPrivateAndModerationHidesOnlyIntendedPublications() throws Exception {
        try(var f=new Fixture(true)) {
            Owner author=f.owner(),reader=f.owner(),other=f.owner();String first=f.playlist(author,"First"),second=f.playlist(author,"Second"),unrelated=f.playlist(other,"Unrelated");
            f.call("GET","/community/blocks",null,null,401);
            f.call("PUT","/community/rules/acceptance",null,Map.of("version",Store.COMMUNITY_RULES_VERSION,"accepted",true),401);
            assertFalse(f.call("GET","/community/rules/acceptance",author,null,200).path("accepted").asBoolean());
            f.call("PUT","/playlists/"+first+"/publication",author,Map.of("public",true),409);
            f.call("PUT","/community/rules/acceptance",author,Map.of("version","outdated","accepted",true),400);
            f.accept(author,false);f.call("PUT","/playlists/"+first+"/publication",author,Map.of("public",true),409);
            f.accept(author,true);f.accept(other,true);
            f.db.update("UPDATE community_rules_acceptance SET rules_version='old-version' WHERE user_id=?",author.id());
            f.call("PUT","/playlists/"+first+"/publication",author,Map.of("public",true),409);f.accept(author,true);
            String a=f.publish(author,first),b=f.publish(author,second),c=f.publish(other,unrelated);
            assertTrue(f.call("GET","/public/playlists/"+a,author,null,200).path("isOwner").asBoolean());
            JsonNode detail=f.call("GET","/public/playlists/"+a,reader,null,200);
            assertFalse(detail.path("isOwner").asBoolean());f.noIdentity(detail,author,reader);
            f.call("PUT","/public/playlists/"+a+"/favourite",reader,Map.of("favourite",true),200);
            String copy=f.call("POST","/public/playlists/"+a+"/copy",reader,Map.of("id",UUID.randomUUID().toString()),201).path("id").asString();
            f.call("POST","/public/playlists/"+a+"/reports",author,Map.of("target","author","reason","spam"),400);
            f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","other","reason","spam"),400);
            f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","playlist","reason","unknown"),400);
            f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","playlist","reason","spam","details","x".repeat(501)),400);
            JsonNode report=f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","playlist","reason","spam","details","Please review <script>fixture</script>"),201);
            JsonNode duplicate=f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","playlist","reason","hate"),200);
            assertEquals(report.path("reportId"),duplicate.path("reportId"));assertTrue(duplicate.path("duplicate").asBoolean());
            String authorReport=f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","author","reason","harassment"),201).path("reportId").asString();
            assertEquals(authorReport,f.call("POST","/public/playlists/"+b+"/reports",reader,Map.of("target","author","reason","other"),200).path("reportId").asString());
            String block=f.call("POST","/public/playlists/"+a+"/block-author",reader,null,200).path("blockId").asString();
            assertEquals(block,f.call("POST","/public/playlists/"+b+"/block-author",reader,null,200).path("blockId").asString());
            f.call("POST","/public/playlists/"+a+"/block-author",author,null,400);
            assertEquals(1,f.call("GET","/community/blocks",reader,null,200).size());
            f.call("DELETE","/community/blocks/"+block,other,null,404);
            for(String query:List.of("?sort=popular","?sort=recent","?q=First")) {
                var visible=f.call("GET","/public/playlists"+query,reader,null,200);
                for(var row:visible)assertFalse(List.of(a,b).contains(row.path("shareId").asString()));
            }
            assertEquals(0,f.call("GET","/public/playlists?favourites=true",reader,null,200).size());
            f.call("GET","/public/playlists/"+a,reader,null,404);
            f.call("POST","/public/playlists/"+a+"/copy",reader,null,404);
            assertEquals(200,f.request("GET","/share/"+a,null,null,null).statusCode(),"A personal block must not pretend to revoke the public web link.");
            assertEquals(1,f.call("GET","/playlists/"+copy,reader,null,200).path("items").size());
            var restored=new Store(f.db,new DataSourceTransactionManager(f.db.getDataSource()),f.json,200,20);
            assertEquals(1,restored.communityBlocks(reader.id()).size());assertTrue(restored.rulesAccepted(author.id()));
            f.call("DELETE","/community/blocks/"+block,reader,null,204);
            assertEquals(3,f.call("GET","/public/playlists",reader,null,200).size());
            assertEquals(1,f.call("GET","/public/playlists?favourites=true",reader,null,200).size());
            f.call("POST","/public/playlists/"+b+"/block-author",other,null,200);
            assertEquals(401,f.request("GET","/moderation/reports",null,null,null).statusCode());
            var wrong=f.request("GET","/moderation/reports",null,null,"wrong-key");assertEquals(401,wrong.statusCode());assertFalse(wrong.body().contains("wrong-key"));
            assertEquals(401,f.request("GET","/moderation/reports?key="+Fixture.KEY,null,null,null).statusCode());
            var review=f.request("GET","/moderation/reports",null,null,Fixture.KEY);assertEquals(200,review.statusCode());assertEquals("no-store",review.headers().firstValue("Cache-Control").orElse(""));
            f.noIdentity(f.json.readTree(review.body()),author,reader);assertEquals(2,f.json.readTree(review.body()).size());
            assertEquals(1,f.decide(report.path("reportId").asString(),"hide").path("hiddenPublications").asInt());
            assertEquals("HIDDEN",f.call("POST","/public/playlists/"+a+"/reports",reader,Map.of("target","playlist","reason","spam"),200).path("status").asString());
            f.call("GET","/public/playlists/"+a,null,null,404);f.call("GET","/public/playlists/"+b,null,null,200);
            f.call("PUT","/playlists/"+first+"/publication",author,Map.of("public",false),200);
            f.call("PUT","/playlists/"+first+"/publication",author,Map.of("public",true),403);
            assertEquals(1,f.decide(authorReport,"hide").path("hiddenPublications").asInt());
            assertEquals(0,f.decide(authorReport,"hide").path("hiddenPublications").asInt());
            f.call("GET","/public/playlists/"+b,null,null,404);f.call("GET","/public/playlists/"+c,null,null,200);
            String pending=f.playlist(other,"Unpublished before review"),pendingShare=f.publish(other,pending);
            String pendingReport=f.call("POST","/public/playlists/"+pendingShare+"/reports",reader,Map.of("target","playlist","reason","other"),201).path("reportId").asString();
            f.call("PUT","/playlists/"+pending+"/publication",other,Map.of("public",false),200);
            assertEquals(0,f.decide(pendingReport,"hide").path("hiddenPublications").asInt(),"An already-private source adds a marker without claiming a public page was hidden.");
            f.call("PUT","/playlists/"+pending+"/publication",other,Map.of("public",true),403);
            f.call("DELETE","/playlists/"+pending,other,null,204);
            String retained=f.call("POST","/public/playlists/"+c+"/reports",reader,Map.of("target","playlist","reason","other"),201).path("reportId").asString();
            String dismissal=f.call("POST","/public/playlists/"+c+"/reports",reader,Map.of("target","author","reason","other"),201).path("reportId").asString();
            f.call("PUT","/playlists/"+unrelated+"/publication",other,Map.of("public",false),200);
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM community_reports WHERE id=?",Integer.class,retained),"Unpublishing must not erase a pending operator report.");
            assertEquals(retained,f.call("POST","/public/playlists/"+c+"/reports",reader,Map.of("target","playlist","reason","other"),200).path("reportId").asString());
            f.call("DELETE","/playlists/"+unrelated,other,null,204);
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM community_reports WHERE id=?",Integer.class,retained),"Deleting a source list retains the minimal original resource ID until review or account deletion.");
            f.call("POST","/playlists",reader,Map.of("id",unrelated,"title","Different owner's reused UUID"),201);f.accept(reader,true);
            String replacement=f.publish(reader,unrelated);
            assertEquals(0,f.decide(retained,"hide").path("hiddenPublications").asInt(),"A retained source ID must never hide another owner's replacement list.");
            f.call("GET","/public/playlists/"+replacement,reader,null,200);
            assertEquals("DISMISSED",f.decide(dismissal,"dismiss").path("status").asString());
            assertEquals(409,f.request("POST","/moderation/reports/"+dismissal+"/decision",null,Map.of("action","hide"),Fixture.KEY).statusCode());
            assertEquals(400,f.request("POST","/moderation/reports/"+retained+"/decision",null,Map.of("action","unknown"),Fixture.KEY).statusCode());
            f.call("POST","/public/playlists/"+b+"/block-author",reader,null,404);
            f.call("DELETE","/accounts/me",author,null,204);
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM community_reports WHERE reported_user_id=?",Integer.class,author.id()));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM community_rules_acceptance WHERE user_id=?",Integer.class,author.id()));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM community_hidden_playlists",Integer.class));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM community_author_blocks",Integer.class));
            assertEquals(1,f.call("GET","/playlists/"+copy,reader,null,200).path("items").size(),"Independent copies are not moderation or account-delete targets.");
            f.call("DELETE","/accounts/me",reader,null,204);
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM community_reports",Integer.class));
        }
    }

    @Test void dailyReportLimitIsPerReporterAndDuplicateRetriesRemainAccepted() throws Exception {
        try(var f=new Fixture(true)) {
            Owner author=f.owner(),reader=f.owner(),another=f.owner();f.accept(author,true);String first=null,last=null;
            for(int i=0;i<11;i++) {String share=f.publish(author,f.playlist(author,"Fixture "+i));if(first==null)first=share;last=share;f.call("POST","/public/playlists/"+share+"/reports",reader,Map.of("target","playlist","reason","spam"),i<10?201:429);}
            f.call("POST","/public/playlists/"+first+"/reports",reader,Map.of("target","playlist","reason","other"),200);
            f.call("POST","/public/playlists/"+last+"/reports",another,Map.of("target","playlist","reason","other"),201);
            assertEquals(10,f.db.queryForObject("SELECT COUNT(*) FROM community_reports WHERE reporter_user_id=?",Integer.class,reader.id()));
            Instant now=Instant.parse("2026-10-19T00:00:00.123456Z"),cutoff=now.minus(Duration.ofDays(1));
            List<String> reports=f.db.query("SELECT id FROM community_reports WHERE reporter_user_id=? ORDER BY id",(rs,n)->rs.getString(1),reader.id());
            f.db.update("UPDATE community_reports SET created_at=? WHERE reporter_user_id=?",now.toString(),reader.id());
            f.db.update("UPDATE community_reports SET created_at=? WHERE id=?","2026-10-18T00:00:00.123Z",reports.get(0));
            f.db.update("UPDATE community_reports SET created_at=? WHERE id=?",cutoff.toString(),reports.get(1));
            f.db.update("UPDATE community_reports SET created_at=? WHERE id=?","2026-10-18T00:00:00.123457Z",reports.get(2));
            assertEquals(false,f.store.reportPublic(reader.id(),last,"playlist","other",null,now).get("duplicate"),"The .123 report is older than the .123456 cutoff, even though its text sorts after it.");
            String extra=f.publish(author,f.playlist(author,"Boundary limit"));
            var limit=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->f.store.reportPublic(reader.id(),extra,"playlist","other",null,now));
            assertEquals(429,limit.getStatusCode().value(),"Exactly 24 hours and .123457 must both count toward the limit.");
            assertEquals(0,f.store.feedback(reader.id()).size(),"Reports and blocks must not become recommendation feedback.");
        }
    }

    @Test void missingOperatorSecretStillPersistsReportsWithoutClaimingAReviewOccurred() throws Exception {
        try(var f=new Fixture(false)) {
            Owner author=f.owner(),reader=f.owner();f.accept(author,true);String share=f.publish(author,f.playlist(author,"No operator fixture"));
            assertEquals("OPEN",f.call("POST","/public/playlists/"+share+"/reports",reader,Map.of("target","playlist","reason","other"),201).path("status").asString());
            var response=f.request("GET","/moderation/reports",null,null,Fixture.KEY);assertEquals(503,response.statusCode());assertFalse(response.body().contains(Fixture.KEY));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM community_reports WHERE status='OPEN'",Integer.class));
            var registered=f.call("POST","/accounts/register",reader,Map.of("email","community-expiry-"+UUID.randomUUID()+"@example.test","password","Community-session-check!","name","Fixture"),201);
            Owner expired=new Owner(reader.id(),"Bearer "+registered.path("token").asString());
            f.db.update("UPDATE account_sessions SET created_at=? WHERE user_id=?",Instant.now().minus(Duration.ofDays(31)).toString(),reader.id());
            assertEquals("SESSION_EXPIRED",f.call("GET","/community/blocks",expired,null,401).path("code").asString());
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM community_reports WHERE status='OPEN'",Integer.class),"Expiry must not delete existing reports.");
        }
    }

    record Owner(String id,String auth){}
    private static final class Fixture implements AutoCloseable {
        static final String KEY="fixture-only-operator-key-"+"x".repeat(32);
        final ObjectMapper json=new ObjectMapper();final HttpClient http=HttpClient.newHttpClient();final ConfigurableApplicationContext context;final JdbcTemplate db,schemaOwner;final Store store;final String base,schema;
        Fixture(boolean admin) {
            String configured=System.getenv("TAPTAP_TEST_DATABASE_URL");boolean pg=configured!=null&&!configured.isBlank();
            String user=pg?System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"):"sa",password=pg?System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD",""):"";
            schema=pg?"community_safety_"+UUID.randomUUID().toString().replace("-",""):null;
            schemaOwner=pg?new JdbcTemplate(new DriverManagerDataSource(configured,user,password)):null;if(pg)schemaOwner.execute("CREATE SCHEMA "+schema);
            String database=pg?configured+(configured.contains("?")?"&":"?")+"currentSchema="+schema:"jdbc:h2:mem:community-safety-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
            context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1","--spring.datasource.url="+database,"--spring.datasource.username="+user,"--spring.datasource.password="+password,"--taptap.openai.key=","--MODERATION_ADMIN_SECRET="+(admin?KEY:""),"--logging.level.root=WARN","--spring.main.banner-mode=off");
            base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();db=context.getBean(JdbcTemplate.class);store=context.getBean(Store.class);
        }
        HttpResponse<String> request(String method,String path,Owner owner,Object body,String key) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");if(owner!=null)builder.header("Authorization",owner.auth());if(key!=null)builder.header("X-TapTap-Moderation-Key",key);
            return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode call(String method,String path,Owner owner,Object body,int expected) throws Exception {var reply=request(method,path,owner,body,null);assertEquals(expected,reply.statusCode(),method+" "+path);return reply.body().isEmpty()?json.createObjectNode():json.readTree(reply.body());}
        Owner owner() throws Exception {var session=call("POST","/sessions",null,null,201);return new Owner(session.path("userId").asString(),"Bearer "+session.path("token").asString());}
        void accept(Owner owner,boolean accepted) throws Exception {call("PUT","/community/rules/acceptance",owner,Map.of("version",call("GET","/community/rules",null,null,200).path("version").asString(),"accepted",accepted),200);}
        String playlist(Owner owner,String title) throws Exception {String id=call("POST","/playlists",owner,Map.of("title",title),201).path("id").asString();String track=call("GET","/catalogue",owner,null,200).get(0).path("id").asString();call("POST","/playlists/"+id+"/items",owner,Map.of("trackId",track),200);return id;}
        String publish(Owner owner,String id) throws Exception {return call("PUT","/playlists/"+id+"/publication",owner,Map.of("public",true),200).path("shareId").asString();}
        JsonNode decide(String report,String action) throws Exception {var reply=request("POST","/moderation/reports/"+report+"/decision",null,Map.of("action",action),KEY);assertEquals(200,reply.statusCode());return json.readTree(reply.body());}
        void noIdentity(JsonNode value,Owner... owners){String encoded=value.toString();for(Owner owner:owners){assertFalse(encoded.contains(owner.id()));assertFalse(encoded.contains(owner.auth().substring(7)));}assertFalse(encoded.contains(KEY));assertFalse(value.has("userId"));assertFalse(value.has("email"));}
        @Override public void close(){context.close();http.shutdownNow();if(schemaOwner!=null)schemaOwner.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
}
