package com.mptaptap.server;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Provider fixtures exercise actual HTTP verification; no real Kakao credentials are used. */
class KakaoCheckTest {
    private static final String PASSWORD="Legacy-password!2026";
    private final ObjectMapper json=new ObjectMapper();
    private static HttpServletRequest request(String address) {
        return (HttpServletRequest)Proxy.newProxyInstance(KakaoCheckTest.class.getClassLoader(),new Class[]{HttpServletRequest.class},
            (proxy,method,args)->method.getName().equals("getRemoteAddr")?address:null);
    }
    private static int failure(Runnable action) { return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value(); }

    @Test void missingConfigurationIsReportedThroughHttpWithoutPrivateValues() throws Exception {
        try(var context=new SpringApplicationBuilder(TapTapApplication.class).run("--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:kakao-http-"+UUID.randomUUID()+";MODE=PostgreSQL","--taptap.kakao.app-id=",
                "--taptap.openai.key=","--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
            try(var client=HttpClient.newHttpClient()) {
                var config=client.send(HttpRequest.newBuilder(URI.create(base+"/accounts/kakao/config")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,config.statusCode());var value=json.readTree(config.body());assertFalse(value.path("configured").asBoolean());
                assertTrue(value.path("setupMessage").asString().contains("KAKAO_APP_ID"));assertEquals(2,value.size());
                var login=client.send(HttpRequest.newBuilder(URI.create(base+"/accounts/kakao")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"accessToken\":\"private-test-token\"}")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,login.statusCode());assertFalse(login.body().contains("private-test-token"));
                assertEquals(0,context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM accounts",Integer.class));
            }
        }
    }

    @Test void verifiesAppExpiryIdentityAndBoundedResponsesBeforeCreatingAnyAccount() throws Exception {
        try(Fixture f=new Fixture()) {
            assertEquals(true,f.api.kakaoConfig().get("configured"));assertEquals("",f.api.kakaoConfig().get("setupMessage"));
            assertEquals(401,failure(()->f.login(null,null)));
            for(String invalid:List.of("","with space","line\nbreak","x".repeat(4097)))assertEquals(401,failure(()->f.login(null,invalid)));
            assertEquals(0,f.calls.get());
            f.status=401;assertEquals(401,failure(()->f.login(null,"expired")));
            f.status=403;assertEquals(403,failure(()->f.login(null,"denied")));
            f.status=429;assertEquals(429,failure(()->f.login(null,"rate-limited")));
            f.status=200;f.info="{\"id\":100,\"app_id\":1234,\"expires_in\":0}";assertEquals(401,failure(()->f.login(null,"zero-expiry")));
            f.info="{\"id\":100,\"app_id\":4321,\"expires_in\":3600}";assertEquals(403,failure(()->f.login(null,"wrong-app")));
            for(String malformed:List.of("{\"id\":0,\"app_id\":1234,\"expires_in\":3600}","{\"id\":100.5,\"app_id\":1234,\"expires_in\":3600}","{\"id\":\"100\",\"app_id\":1234,\"expires_in\":3600}","{\"id\":100,\"expires_in\":3600}")) {
                f.info=malformed;assertEquals(401,failure(()->f.login(null,"malformed-"+f.calls.get())));
            }
            f.info=info(100);f.me="{\"id\":200}";assertEquals(401,failure(()->f.login(null,"mismatch")));
            f.me="[100]";assertEquals(502,failure(()->f.login(null,"array-response")));
            f.info="{\"padding\":\""+"x".repeat(65536)+"\"}";assertEquals(502,failure(()->f.login(null,"oversized")));
            f.status=302;assertEquals(502,failure(()->f.login(null,"redirect")));assertEquals(0,f.redirectCalls.get());
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM accounts",Integer.class));
        }
    }

    @Test void anonymousOwnerIsClaimedAndRepeatedLoginsRestoreOnlyTheirOwnData() throws Exception {
        try(Fixture f=new Fixture()) {
            Map<String,String> anonymous=f.store.register(),other=f.store.register();String owner=anonymous.get("userId"),auth="Bearer "+anonymous.get("token");
            String playlist=(String)f.store.createPlaylist(owner,"기존 내 목록",null).get("id");
            String track=f.track;f.store.feedback(owner,track,1);
            var account=f.login(auth,"sdk-token-owner");String token=account.get("token");
            assertEquals(owner,account.get("userId"));assertEquals("kakao",account.get("authProvider"));assertEquals("카카오 사용자",account.get("name"));assertEquals("",account.get("email"));
            assertEquals(401,failure(()->f.store.user(auth)));assertEquals(Map.of(track,1),f.store.feedback(owner));assertEquals(playlist,f.store.playlists(owner).get(0).get("id"));
            assertNull(f.db.queryForObject("SELECT email FROM accounts WHERE user_id=?",String.class,owner));assertNull(f.db.queryForObject("SELECT password_hash FROM accounts WHERE user_id=?",String.class,owner));
            f.info=info(200);f.me="{\"id\":200}";
            assertEquals(401,failure(()->f.login(auth,"new-identity-with-stale-bearer")),"A new identity cannot claim an invalid old owner.");
            f.info=info(100);f.me="{\"id\":100}";
            var recovered=f.login(auth,"sdk-token-owner");
            assertEquals(owner,recovered.get("userId"),"A lost first login response leaves an invalid anonymous bearer; verified Kakao identity still restores its existing owner.");
            assertNotEquals(token,recovered.get("token"));assertEquals(Map.of(track,1),f.store.feedback(owner));assertEquals(playlist,f.store.playlists(owner).get(0).get("id"));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM accounts",Integer.class));
            f.me="{\"id\":100,\"kakao_account\":{\"profile\":{\"nickname\":\"새 이름\"},\"email\":\"OWNER@EXAMPLE.TEST\",\"is_email_valid\":true,\"is_email_verified\":true}}";
            var repeated=f.login("Bearer "+other.get("token"),"sdk-token-owner-new-device");
            assertEquals(owner,repeated.get("userId"));assertNotEquals(token,repeated.get("token"));assertEquals("새 이름",repeated.get("name"));assertEquals("owner@example.test",repeated.get("email"));
            assertEquals(other.get("userId"),f.store.user("Bearer "+other.get("token")));assertTrue(f.store.playlists(other.get("userId")).isEmpty());
            assertEquals(404,failure(()->f.store.ownedPlaylist(other.get("userId"),playlist)));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM social_accounts",Integer.class));
            assertFalse(json.writeValueAsString(f.db.queryForList("SELECT * FROM social_accounts")).contains("sdk-token"));
            f.store.logout("Bearer "+token);assertEquals(401,failure(()->f.store.user("Bearer "+token)));
            assertEquals(owner,f.store.user("Bearer "+repeated.get("token")),"Logging out one device preserves the other device's app session.");
        }
    }

    @Test void sameEmailNeverMergesAndOldSchemaLegacyAccountsRemainUsable() throws Exception {
        try(Fixture f=new Fixture()) {
            var legacy=f.store.registerAccount(null,"shared@example.test",PASSWORD,"이메일 계정","legacy-address");String owner=legacy.get("userId");
            f.db.execute("ALTER TABLE accounts ALTER COLUMN email SET NOT NULL");f.db.execute("ALTER TABLE accounts ALTER COLUMN password_hash SET NOT NULL");f.migrate();
            assertEquals(owner,f.store.login("shared@example.test",PASSWORD,"legacy-address").get("userId"));
            assertEquals("email",f.store.account(owner).get("authProvider"));
            String legacyHash=f.db.queryForObject("SELECT password_hash FROM accounts WHERE user_id=?",String.class,owner);
            f.db.update("UPDATE accounts SET password_hash=NULL WHERE user_id=?",owner);
            assertEquals(401,failure(()->f.store.login("shared@example.test",PASSWORD,"legacy-address")));
            assertEquals(401,failure(()->f.store.deleteAccount("Bearer "+legacy.get("token"),PASSWORD,"legacy-address")));
            f.db.update("UPDATE accounts SET password_hash=? WHERE user_id=?",legacyHash,owner);
            f.me="{\"id\":100,\"kakao_account\":{\"email\":\"shared@example.test\",\"is_email_valid\":true,\"is_email_verified\":true}}";
            assertEquals(409,failure(()->f.login("Bearer "+legacy.get("token"),"new-identity")));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM social_accounts",Integer.class));
            var kakao=f.login(null,"same-email-social");assertNotEquals(owner,kakao.get("userId"));assertEquals("shared@example.test",kakao.get("email"));
            assertEquals(owner,f.store.login("shared@example.test",PASSWORD,"legacy-address").get("userId"));
            f.info=info(200);f.me="{\"id\":200,\"kakao_account\":{\"email\":\"shared@example.test\",\"is_email_valid\":true,\"is_email_verified\":false}}";
            var unverified=f.login(null,"unverified-email");assertEquals("",unverified.get("email"));assertNotEquals(kakao.get("userId"),unverified.get("userId"));
            f.api.delete("Bearer "+legacy.get("token"),new AccountApi.Password(PASSWORD,null),request("legacy-address"));
            assertEquals(401,failure(()->f.store.user("Bearer "+legacy.get("token"))));assertEquals(kakao.get("userId"),f.store.user("Bearer "+kakao.get("token")));
            assertEquals(2,f.db.queryForObject("SELECT COUNT(*) FROM social_accounts",Integer.class));
        }
    }

    @Test void deletionRequiresFreshMatchingKakaoIdentityAndCascadesAllLocalOwnership() throws Exception {
        try(Fixture f=new Fixture()) {
            var account=f.login(null,"owner-login");String owner=account.get("userId"),auth="Bearer "+account.get("token");
            String playlist=(String)f.store.createPlaylist(owner,"탈퇴 확인 목록",null).get("id");f.store.feedback(owner,f.track,1);
            assertEquals(401,failure(()->f.api.delete(auth,new AccountApi.Password(PASSWORD,null),request("delete-address"))));
            assertEquals(401,failure(()->f.store.deleteAccount(auth,PASSWORD,"delete-address")));
            f.status=401;assertEquals(401,failure(()->f.api.delete(auth,new AccountApi.Password(null,"expired-owner"),request("delete-address"))));
            f.status=200;f.info=info(200);f.me="{\"id\":200}";
            assertEquals(403,failure(()->f.api.delete(auth,new AccountApi.Password(null,"other-owner"),request("delete-address"))));
            assertEquals(owner,f.store.user(auth));assertEquals(playlist,f.store.playlists(owner).get(0).get("id"));
            f.info=info(100);f.me="{\"id\":100}";
            f.api.delete(auth,new AccountApi.Password(null,"fresh-owner"),request("delete-address"));
            assertEquals(401,failure(()->f.store.user(auth)));
            for(String table:List.of("accounts","social_accounts","account_sessions","playlists","recommendation_feedback"))assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            var again=f.login(null,"rejoin-owner");assertNotEquals(owner,again.get("userId"));assertTrue(f.store.playlists(again.get("userId")).isEmpty());
        }
    }

    @Test void repeatedRequestsAreThrottledBeforeAnyAdditionalProviderNetworkCall() throws Exception {
        try(Fixture f=new Fixture()) {
            f.status=401;for(int i=0;i<8;i++)assertEquals(401,failure(()->f.login(null,"same-invalid-token")));
            assertEquals(8,f.calls.get());assertEquals(429,failure(()->f.login(null,"same-invalid-token")));assertEquals(8,f.calls.get());
            for(int i=8;i<40;i++){String token="different-token-"+i;assertEquals(401,failure(()->f.login(null,token)));}
            assertEquals(40,f.calls.get());assertEquals(429,failure(()->f.login(null,"one-more-token")));assertEquals(40,f.calls.get());
        }
    }

    @Test void postgresMigrationSocialSessionsAndReauthenticatedDeletionPersistAcrossStores() throws Exception {
        String url=System.getenv("TAPTAP_TEST_DATABASE_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url!=null&&url.startsWith("jdbc:postgresql:"),"Dedicated PostgreSQL test database was not provided.");
        String username=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_USER","sa"),password=System.getenv().getOrDefault("TAPTAP_TEST_DATABASE_PASSWORD","");
        String schema="kakao_"+UUID.randomUUID().toString().replace("-","");
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(url,username,password));admin.execute("CREATE SCHEMA "+schema);
        try(Fixture f=new Fixture()) {
            var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,username,password);
            JdbcTemplate db=new JdbcTemplate(source);var migration=new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));migration.execute(source);
            var manager=new DataSourceTransactionManager(source);var store=new Store(db,manager,json,200,20);
            var legacy=store.registerAccount(null,"shared@example.test",PASSWORD,"Legacy","pg-legacy");
            db.execute("DROP TABLE social_accounts");db.execute("ALTER TABLE accounts ALTER COLUMN email SET NOT NULL");db.execute("ALTER TABLE accounts ALTER COLUMN password_hash SET NOT NULL");migration.execute(source);
            assertEquals(legacy.get("userId"),store.login("shared@example.test",PASSWORD,"pg-legacy").get("userId"));
            assertEquals(legacy.get("userId"),store.user("Bearer "+legacy.get("token")));
            var anonymous=store.register();String owner=anonymous.get("userId"),playlist=(String)store.createPlaylist(owner,"PG 기존 목록",null).get("id");
            store.feedback(owner,f.track,1);db.update("INSERT INTO playlist_items (id,playlist_id,track_id,position) VALUES (?,?,?,?)",UUID.randomUUID().toString(),playlist,f.track,0);
            f.me="{\"id\":100,\"kakao_account\":{\"email\":\"shared@example.test\",\"is_email_valid\":true,\"is_email_verified\":true}}";
            var api=new AccountApi(store,f.remote);var account=api.kakaoLogin("Bearer "+anonymous.get("token"),new AccountApi.KakaoLogin("pg-first-login"),request("pg-login"));
            assertEquals(owner,account.get("userId"));assertNotEquals(legacy.get("userId"),owner);assertEquals("shared@example.test",account.get("email"));
            assertNull(db.queryForObject("SELECT email FROM accounts WHERE user_id=?",String.class,owner));assertNull(db.queryForObject("SELECT password_hash FROM accounts WHERE user_id=?",String.class,owner));
            var reloaded=new Store(db,manager,json,200,20);var nextApi=new AccountApi(reloaded,f.remote);
            assertEquals("kakao",reloaded.account(reloaded.user("Bearer "+account.get("token"))).get("authProvider"));
            var repeated=nextApi.kakaoLogin(null,new AccountApi.KakaoLogin("pg-second-device"),request("pg-login"));
            assertEquals(owner,repeated.get("userId"));assertNotEquals(account.get("token"),repeated.get("token"));assertEquals(Map.of(f.track,1),reloaded.feedback(owner));
            assertEquals(playlist,reloaded.playlists(owner).get(0).get("id"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?",Integer.class,playlist));
            f.info=info(200);f.me="{\"id\":200}";
            assertEquals(403,failure(()->nextApi.delete("Bearer "+repeated.get("token"),new AccountApi.Password(null,"pg-wrong-owner"),request("pg-delete"))));
            assertEquals(owner,reloaded.user("Bearer "+account.get("token")));
            f.info=info(100);f.me="{\"id\":100}";nextApi.delete("Bearer "+repeated.get("token"),new AccountApi.Password(null,"pg-fresh-owner"),request("pg-delete"));
            assertEquals(401,failure(()->reloaded.user("Bearer "+account.get("token"))));assertEquals(401,failure(()->reloaded.user("Bearer "+repeated.get("token"))));
            for(String table:List.of("social_accounts","playlists","playlist_items","recommendation_feedback"))assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            assertEquals(legacy.get("userId"),reloaded.login("shared@example.test",PASSWORD,"pg-legacy").get("userId"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM accounts",Integer.class));
        } finally { admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    }

    private static String info(long id) { return "{\"id\":"+id+",\"app_id\":1234,\"expires_in\":3600}"; }
    private final class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger calls=new AtomicInteger(),redirectCalls=new AtomicInteger();
        final SingleConnectionDataSource source=new SingleConnectionDataSource("jdbc:h2:mem:kakao-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","",true);
        final JdbcTemplate db=new JdbcTemplate(source);
        final Store store;
        final KakaoClient remote;
        final AccountApi api;
        final String track;
        int status=200;
        String info=info(100),me="{\"id\":100}";
        Fixture() throws Exception {
            migrate();store=new Store(db,new DataSourceTransactionManager(source),json,200,20);
            try(var input=new ClassPathResource("tracks.json").getInputStream()){track=json.readTree(input).get(0).path("id").asString();}
            server.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath();
                if(path.equals("/redirected"))redirectCalls.incrementAndGet();else calls.incrementAndGet();
                assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
                exchange.getResponseHeaders().add("Content-Type","application/json");
                if(status==302)exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
                String payload=path.equals("/v1/user/access_token_info")?info:me;byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            remote=new KakaoClient(json,"1234","http://127.0.0.1:"+server.getAddress().getPort());api=new AccountApi(store,remote);
        }
        void migrate() { new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source); }
        Map<String,String> login(String auth,String token) { return api.kakaoLogin(auth,new AccountApi.KakaoLogin(token),request("login-address")); }
        @Override public void close() { remote.close();server.stop(0);try{db.execute("DROP ALL OBJECTS");}finally{source.destroy();} }
    }
}
