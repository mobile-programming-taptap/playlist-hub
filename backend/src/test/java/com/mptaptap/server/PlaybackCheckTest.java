package com.mptaptap.server;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackCheckTest {
    private static final String VIDEO="dQw4w9WgXcQ";

    @Test void everyEmbedUsesFreshExplicitOfficialStatus() throws Exception {
        try(Fixture f=new Fixture(true)){
            assertTrue(f.api.check(f.auth,VIDEO).getBody().playbackAllowed());
            assertEquals("no-store",f.api.check(f.auth,VIDEO).getHeaders().getFirst("Cache-Control"));
            f.status.set(Map.of("madeForKids",true,"embeddable",true));
            assertFalse(f.api.check(f.auth,VIDEO).getBody().playbackAllowed());
            assertTrue(f.api.check(f.auth,VIDEO).getBody().madeForKids());
            for(Map<String,Object> unknown:List.<Map<String,Object>>of(Map.of("embeddable",true),Map.of("madeForKids","false","embeddable",true),Map.of("madeForKids",false,"embeddable",false),Map.of("madeForKids",false,"embeddable","true"))){
                f.status.set(unknown);assertFalse(f.api.check(f.auth,VIDEO).getBody().playbackAllowed());
            }
            assertEquals(8,f.calls.get(),"Player status must not be reused across separate plays.");
            assertEquals("",f.authorization.get(),"A public API key lookup must not attach an unrelated user's Google grant.");
        }
    }

    @Test void wrongVideoAndProviderFailuresNeverAuthorizeEmbedding() throws Exception {
        try(Fixture f=new Fixture(true)){
            f.id.set("gdZLi9oWNZg");assertEquals(502,assertThrows(PlatformClient.Failure.class,()->f.api.check(f.auth,VIDEO)).status);
            f.id.set(VIDEO);f.code.set(429);assertEquals(429,assertThrows(PlatformClient.Failure.class,()->f.api.check(f.auth,VIDEO)).status);
            assertThrows(IllegalArgumentException.class,()->f.api.check(f.auth,"../wrong"));
            assertEquals(2,f.calls.get());
            assertEquals(401,assertThrows(ResponseStatusException.class,()->f.api.check(null,VIDEO)).getStatusCode().value());
            f.users.deleteAnonymous(f.auth);
            assertEquals(401,assertThrows(ResponseStatusException.class,()->f.api.check(f.auth,VIDEO)).getStatusCode().value());
            assertEquals(2,f.calls.get());
        }
    }

    @Test void privateGrantBelongsOnlyToTheAuthenticatedOwner() throws Exception {
        try(Fixture f=new Fixture(false)){
            Map<String,String> other=f.users.register();String owner=other.get("userId"),auth="Bearer "+other.get("token");
            f.connections.connection(owner,"youtube",new PlatformClient.Tokens("fixture-owner-grant","fixture-refresh","fixture-channel","",Instant.now().plusSeconds(3600)));
            assertEquals(503,assertThrows(ResponseStatusException.class,()->f.api.check(f.auth,VIDEO)).getStatusCode().value());
            assertEquals(0,f.calls.get());
            assertTrue(f.api.check(auth,VIDEO).getBody().playbackAllowed());
            assertEquals("Bearer fixture-owner-grant",f.authorization.get());
            assertEquals(1,f.calls.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger calls=new AtomicInteger(),code=new AtomicInteger(200);
        final AtomicReference<Map<String,Object>> status=new AtomicReference<>(Map.of("madeForKids",false,"embeddable",true));
        final AtomicReference<String> id=new AtomicReference<>(VIDEO),authorization=new AtomicReference<>("");
        final Store users;final PlatformStore connections;final PlatformClient remote;final RecommendationCatalogue catalogue;final PlaybackApi api;final String auth;
        Fixture(boolean key) throws Exception {
            server.createContext("/videos",exchange->{
                calls.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization")==null?"":exchange.getRequestHeaders().getFirst("Authorization"));
                String query=exchange.getRequestURI().getRawQuery();assertTrue(query.contains("part=status"));assertTrue(query.contains("id="+VIDEO));
                byte[] body=json.writeValueAsString(Map.of("items",List.of(Map.of("id",id.get(),"status",status.get())))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
            var source=new DriverManagerDataSource("jdbc:h2:mem:playback-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            var db=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);
            users=new Store(db,manager,json,200,20);connections=new PlatformStore(db,manager,json,Base64.getEncoder().encodeToString(new byte[32]));
            remote=new PlatformClient(json,key?Map.of("YOUTUBE_API_KEY","fixture-key"):Map.of(),Map.of("youtube","http://127.0.0.1:"+server.getAddress().getPort()));
            catalogue=new RecommendationCatalogue(db,manager,json,"http://127.0.0.1:"+server.getAddress().getPort()+"/recording/");
            api=new PlaybackApi(users,connections,remote,catalogue);auth="Bearer "+users.register().get("token");
        }
        public void close(){catalogue.close();remote.close();server.stop(0);}
    }
}
