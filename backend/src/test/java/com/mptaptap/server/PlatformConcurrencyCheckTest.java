package com.mptaptap.server;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PlatformConcurrencyCheckTest {
    @Test void slowRefreshDoesNotBlockOtherOwnerAndUnlinkCannotRestoreRefreshedToken() throws Exception {
        try(var f=new Fixture();var threads=Executors.newFixedThreadPool(3)) {
            f.connect(f.a,true);f.connect(f.b,false);f.remote.holdRefresh=true;
            var read=threads.submit(()->f.api.playlists(f.a.auth(),"spotify",""));
            assertTrue(f.remote.refreshStarted.await(3,TimeUnit.SECONDS));
            assertEquals("spotify",threads.submit(()->f.api.playlists(f.b.auth(),"spotify","")).get(2,TimeUnit.SECONDS).provider());
            var unlink=threads.submit(()->f.api.unlink(f.a.auth(),"spotify"));
            assertThrows(TimeoutException.class,()->unlink.get(100,TimeUnit.MILLISECONDS),"Same-owner unlink must wait for the in-flight refresh.");
            f.remote.releaseRefresh.countDown();read.get(3,TimeUnit.SECONDS);unlink.get(3,TimeUnit.SECONDS);
            assertEquals(1,f.remote.refreshes.get());
            assertTrue(f.api.connections(f.a.auth()).isEmpty());
            assertEquals(409,assertThrows(ResponseStatusException.class,()->f.api.playlists(f.a.auth(),"spotify","")).getStatusCode().value());
        }
    }

    @Test void callbacksForOtherOwnersProceedAndUnlinkInvalidatesWaitingCallback() throws Exception {
        try(var f=new Fixture();var threads=Executors.newFixedThreadPool(3)) {
            String aState=f.api.start(f.a.auth(),"spotify").get("state"),bState=f.api.start(f.b.auth(),"spotify").get("state");
            f.remote.holdExchange=true;
            var a=threads.submit(()->f.api.callback("spotify",aState,"slow",null));
            assertTrue(f.remote.exchangeStarted.await(3,TimeUnit.SECONDS));
            assertTrue(threads.submit(()->f.api.callback("spotify",bState,"fast",null)).get(2,TimeUnit.SECONDS).getHeaders().getLocation().toString().contains("status=connected"));
            f.remote.releaseExchange.countDown();assertTrue(a.get(3,TimeUnit.SECONDS).getHeaders().getLocation().toString().contains("status=connected"));
            String waiting=f.api.start(f.a.auth(),"spotify").get("state");
            f.store.peeked=new CountDownLatch(1);
            java.util.concurrent.Future<org.springframework.http.ResponseEntity<Void>> callback;
            synchronized(f.users.ownerLock(f.a.id())) {
                callback=threads.submit(()->f.api.callback("spotify",waiting,"fast",null));
                assertTrue(f.store.peeked.await(3,TimeUnit.SECONDS));
                f.api.unlink(f.a.auth(),"spotify");
            }
            assertTrue(callback.get(3,TimeUnit.SECONDS).getHeaders().getLocation().toString().contains("session_expired"));
            assertTrue(f.api.connections(f.a.auth()).isEmpty());
            assertEquals(2,f.remote.exchanges.get(),"A callback invalidated while waiting must never exchange or recreate a connection.");
        }
    }

    @Test void bearerIsRevalidatedAfterWaitingForItsOwner() throws Exception {
        try(var f=new Fixture();var threads=Executors.newSingleThreadExecutor()) {
            f.users.lookedUp=new CountDownLatch(1);
            java.util.concurrent.Future<Map<String,Object>> waiting;
            synchronized(f.users.ownerLock(f.a.id())) {
                waiting=threads.submit(()->f.api.conversion(f.a.auth(),new PlatformApi.Conversion(UUID.randomUUID().toString(),"https://www.youtube.com/watch?v=dQw4w9WgXcQ","0:00 Artist - Song",180)));
                assertTrue(f.users.lookedUp.await(3,TimeUnit.SECONDS));
                f.db.update("DELETE FROM users WHERE id=?",f.a.id());
            }
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->waiting.get(3,TimeUnit.SECONDS));
            assertInstanceOf(ResponseStatusException.class,failure.getCause());
            assertEquals(401,((ResponseStatusException)failure.getCause()).getStatusCode().value());
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM platform_documents",Integer.class));
        }
    }

    @Test void queuedSameOwnerTransfersDoNotOccupyBothWorkersAndRetriesDoNotDuplicateWrites() throws Exception {
        try(var f=new Fixture()) {
            f.connect(f.a,false);f.connect(f.b,false);
            String first=f.reviewed(f.a),second=f.reviewed(f.a),other=f.reviewed(f.b);
            String firstJob=UUID.randomUUID().toString(),secondJob=UUID.randomUUID().toString(),otherJob=UUID.randomUUID().toString();
            f.remote.slowMarker="TapTap transfer "+firstJob;f.remote.loseFirstAdd=true;
            synchronized(f.users.ownerLock(f.a.id())) {
                f.api.transfer(f.a.auth(),new PlatformApi.TransferBody(firstJob,first,"spotify","First",true));
                f.api.transfer(f.a.auth(),new PlatformApi.TransferBody(secondJob,second,"spotify","Second",true));
            }
            assertTrue(f.remote.createStarted.await(3,TimeUnit.SECONDS));
            f.api.transfer(f.b.auth(),new PlatformApi.TransferBody(otherJob,other,"spotify","Other owner",true));
            assertEquals("COMPLETED",f.await(f.b,otherJob,"COMPLETED").get("status"));
            assertEquals(1L,f.remote.releaseCreate.getCount(),"Owner B must complete while owner A is still blocked.");
            f.remote.releaseCreate.countDown();
            f.await(f.a,firstJob,"UNKNOWN");f.await(f.a,secondJob,"COMPLETED");
            f.api.retry(f.a.auth(),firstJob,new PlatformApi.Confirm(true));f.await(f.a,firstJob,"COMPLETED");
            f.api.retry(f.a.auth(),firstJob,new PlatformApi.Confirm(true));
            f.api.transfer(f.a.auth(),new PlatformApi.TransferBody(firstJob,first,"spotify","First",true));
            assertEquals(3,f.remote.creates.get());assertEquals(3,f.remote.adds.get(),"Lost add responses must reconcile before retrying.");
            assertEquals(3,f.store.documents(f.a.id(),"transfer").size()+f.store.documents(f.b.id(),"transfer").size());
        }
    }

    @Test void rejectedSchedulingKeepsDurableJobAndSameIdCanResumeAfterControllerRestart() throws Exception {
        try(var f=new Fixture()) {
            f.connect(f.a,false);String source=f.reviewed(f.a),id=UUID.randomUUID().toString();f.api.close();
            var body=new PlatformApi.TransferBody(id,source,"spotify","Retry after restart",true);
            assertEquals(503,assertThrows(ResponseStatusException.class,()->f.api.transfer(f.a.auth(),body)).getStatusCode().value());
            assertEquals("PENDING",f.api.transferStatus(f.a.auth(),id).get("status"));
            assertEquals(1,f.store.documents(f.a.id(),"transfer").size());
            var restarted=new PlatformApi(f.users,f.store,f.remote,f.json,new DataSourceTransactionManager(f.db.getDataSource()));
            try {
                restarted.retry(f.a.auth(),id,new PlatformApi.Confirm(true));
                f.await(f.a,id,"COMPLETED");
                restarted.transfer(f.a.auth(),body);
                assertEquals(1,f.remote.creates.get());assertEquals(1,f.remote.adds.get());
                assertEquals(1,f.store.documents(f.a.id(),"transfer").size());
            } finally{restarted.close();}
        }
    }

    record Owner(String id,String auth) {}
    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();final JdbcTemplate db;final TestStore users;final TestPlatformStore store;final SlowProvider remote;final PlatformApi api;final Owner a,b;
        Fixture() {
            var data=new DriverManagerDataSource("jdbc:h2:mem:platform-concurrency-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            db=new JdbcTemplate(data);new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(data);
            var manager=new DataSourceTransactionManager(data);users=new TestStore(db,manager,json);
            a=owner(users.register());Owner different=owner(users.register());
            for(int tries=0;users.ownerLock(a.id())==users.ownerLock(different.id());tries++) {assertTrue(tries<100);different=owner(users.register());}
            b=different;store=new TestPlatformStore(db,manager,json);remote=new SlowProvider(json);api=new PlatformApi(users,store,remote,json,manager);
        }
        private Owner owner(Map<String,String> value){return new Owner(value.get("userId"),"Bearer "+value.get("token"));}
        void connect(Owner owner,boolean expired){store.connection(owner.id(),"spotify",new PlatformClient.Tokens("access-"+owner.id(),"refresh",owner.id(),"playlist-read-private playlist-modify-private",Instant.now().plusSeconds(expired?-1:3600)));}
        String reviewed(Owner owner){String id=UUID.randomUUID().toString();store.create(owner.id(),id,"import","spotify",Map.of("id",id,"provider","spotify","targetProvider","spotify","reviews",List.of(Map.of("sourceIndex",0,"approved",true,"status","MATCHED","selected",Map.of("externalId","0123456789012345678901")))));return id;}
        Map<String,Object> await(Owner owner,String id,String expected) throws Exception {
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();Map<String,Object> last;
            do{last=api.transferStatus(owner.auth(),id);if(expected.equals(last.get("status")))return last;Thread.sleep(10);}while(System.nanoTime()<deadline);
            fail("Transfer did not reach "+expected+"; last status="+last.get("status"));return last;
        }
        @Override public void close(){remote.releaseRefresh.countDown();remote.releaseExchange.countDown();remote.releaseCreate.countDown();api.close();remote.close();db.execute("SHUTDOWN");}
    }
    private static final class TestStore extends Store {
        volatile CountDownLatch lookedUp;
        TestStore(JdbcTemplate db,DataSourceTransactionManager manager,ObjectMapper json){super(db,manager,json,200,20);}
        @Override public String user(String auth){String value=super.user(auth);var signal=lookedUp;if(signal!=null)signal.countDown();return value;}
    }
    private static final class TestPlatformStore extends PlatformStore {
        volatile CountDownLatch peeked;
        TestPlatformStore(JdbcTemplate db,DataSourceTransactionManager manager,ObjectMapper json){super(db,manager,json,Base64.getEncoder().encodeToString(new byte[32]));}
        @Override synchronized Pending pending(String state,String provider,boolean consume){var result=super.pending(state,provider,consume);var signal=peeked;if(!consume&&signal!=null)signal.countDown();return result;}
    }
    private static final class SlowProvider extends PlatformClient {
        final CountDownLatch refreshStarted=new CountDownLatch(1),releaseRefresh=new CountDownLatch(1),exchangeStarted=new CountDownLatch(1),releaseExchange=new CountDownLatch(1),createStarted=new CountDownLatch(1),releaseCreate=new CountDownLatch(1);
        final AtomicInteger refreshes=new AtomicInteger(),exchanges=new AtomicInteger(),creates=new AtomicInteger(),adds=new AtomicInteger();
        final Map<String,String> markers=new ConcurrentHashMap<>();final Map<String,List<String>> tracks=new ConcurrentHashMap<>();
        volatile boolean holdRefresh,holdExchange,loseFirstAdd;volatile String slowMarker="";
        SlowProvider(ObjectMapper json){super(json,Map.of(),Map.of());}
        @Override List<String> configurationIssues(String provider){return List.of();}
        @Override boolean configured(String provider){return true;}
        @Override String authorize(String provider,String state,String challenge){return "https://accounts.spotify.com/authorize?state="+state;}
        @Override Tokens refresh(String provider,Tokens previous){refreshes.incrementAndGet();if(holdRefresh){refreshStarted.countDown();waitFor(releaseRefresh);}return new Tokens("new-access","refresh",previous.accountId(),previous.scopes(),Instant.now().plusSeconds(3600));}
        @Override Tokens exchange(String provider,String code,String verifier){exchanges.incrementAndGet();if(holdExchange&&code.equals("slow")){exchangeStarted.countDown();waitFor(releaseExchange);}return new Tokens("new-access","refresh",code,"playlist-read-private",Instant.now().plusSeconds(3600));}
        @Override void revoke(String provider,Tokens tokens){}
        @Override PlaylistPage playlists(String provider,String cursor,Tokens token){return new PlaylistPage(provider,List.of(),"");}
        @Override String findCreated(String provider,String marker,Tokens token){return markers.getOrDefault(marker,"");}
        @Override String createPrivate(String provider,String title,String marker,Tokens token){creates.incrementAndGet();if(marker.equals(slowMarker)){createStarted.countDown();waitFor(releaseCreate);}String id=UUID.randomUUID().toString().replace("-","").substring(0,22);markers.put(marker,id);tracks.put(id,new CopyOnWriteArrayList<>());return id;}
        @Override List<String> existingIds(String provider,String playlist,Tokens token){return new ArrayList<>(tracks.get(playlist));}
        @Override void add(String provider,String playlist,String target,Tokens token){tracks.get(playlist).add(target);adds.incrementAndGet();if(loseFirstAdd&&playlist.equals(markers.get(slowMarker))){loseFirstAdd=false;throw new Failure(502);}}
        private static void waitFor(CountDownLatch signal){try{assertTrue(signal.await(5,TimeUnit.SECONDS),"Fixture latch timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}
    }
}
