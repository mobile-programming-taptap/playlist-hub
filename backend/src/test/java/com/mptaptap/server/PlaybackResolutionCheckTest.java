package com.mptaptap.server;

import com.mptaptap.core.Track;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

class PlaybackResolutionCheckTest {
    private static final String VIDEO="abcdefghijk";

    @Test void newSongsResolveWithoutBundledIdsAndOnlyTheReferenceIsCached() throws Exception {
        try(Fixture f=new Fixture(true)){
            assertEquals("",f.catalogue.track(f.track.id).youtubeVideoId);
            var first=f.api.resolve(f.auth,f.track.id);
            assertEquals(VIDEO,first.getBody().videoId());assertTrue(first.getBody().playbackAllowed());
            assertEquals("no-store",first.getHeaders().getFirst("Cache-Control"));
            assertEquals(1,f.searches.get());assertEquals(1,f.details.get());assertEquals("",f.authorization);
            f.status=Map.of("madeForKids",false,"embeddable",false);
            f.videos=List.of(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Justin Timberlake","public"));
            assertFalse(f.api.resolve(f.auth,f.track.id).getBody().playbackAllowed());
            assertEquals(2,f.searches.get(),"A no-longer-embeddable cached video triggers a search for another version.");assertEquals(1,f.statusChecks.get(),"Repeat plays must recheck current permission.");
            assertEquals("",f.catalogue.track(f.track.id).youtubeVideoId,"YouTube data stays outside CC0 recommendation records.");
        }
    }

    @Test void coversWrongArtistsPrivateVideosAndDifferentSongsNeverAutoConnect() throws Exception {
        try(Fixture f=new Fixture(true)){
            for(Map<String,Object> video:List.of(
                    f.video("Can't Stop the Feeling! (Official Video)","Another Artist","public"),
                    f.video("Can't Stop the Feeling! (Cover)","Justin Timberlake","public"),
                    f.video("Can't Stop the Feeling! (Live)","Justin Timberlake","public"),
                    f.video("Another song (Official Video)","Justin Timberlake","public"),
                    f.video("Can't Stop the Feeling! Part Two (Official Video)","Justin Timberlake","public"),
                    f.video("Can't Stop the Feeling!","Justin Timberlake","private"),
                    f.video("Can't Stop the Feeling!","Justin Timberlake Covers","public"))) {
                f.videos=List.of(video);
                assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id)).getStatusCode().value());
            }
            f.videos=List.of(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","justintimberlakeVEVO","public"));
            assertTrue(f.api.resolve(f.auth,f.track.id).getBody().playbackAllowed(),"No-match responses must not permanently hide later results.");
        }
    }

    @Test void officialTranslatedTitlesRequireAnExactOriginalTitleAndArtistChannel() throws Exception {
        try(Fixture f=new Fixture(true)){
            f.track.title="ポリリズム";f.track.artist="Perfume";
            var video=new LinkedHashMap<>(f.video("Perfume - Polyrhythm (Official Music Video)","Perfume","public"));
            video.put("snippet",Map.of("title","Perfume - Polyrhythm (Official Music Video)","channelTitle","Perfume","description","Perfume「ポリリズム」\nPolyrhythm"));
            f.videos=List.of(video);assertNotNull(f.remote.findPlayback(f.track,null));
            for(String title:List.of("Perfume - Polyrhythm (Official Live)","Perfume - Polyrhythm (Official Remix)","Perfume - Polyrhythm (Official Cover)","Perfume - Polyrhythm (Official Short)","Perfume - Polyrhythm (Official ライブ)")) {
                video.put("snippet",Map.of("title",title,"channelTitle","Perfume","description","Perfume「ポリリズム」"));
                assertNull(f.remote.findPlayback(f.track,null));
            }
            video.put("snippet",Map.of("title","Perfume - Polyrhythm (Official Music Video)","channelTitle","Other Artist","description","Perfume「ポリリズム」"));
            assertNull(f.remote.findPlayback(f.track,null));
            video.put("snippet",Map.of("title","Perfume - Polyrhythm (Official Music Video)","channelTitle","Perfume","description","Other song\nPerfume「ポリリズム」"));
            assertNull(f.remote.findPlayback(f.track,null));
        }
    }

    @Test void matchingTitleOnOtherChannelRequiresConfirmationAndIsNotCached() throws Exception {
        try(Fixture f=new Fixture(true)){
            f.videos=List.of(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Record Label","public"));
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id)).getStatusCode().value());
            var candidate=f.api.resolve(f.auth,f.track.id,true).getBody();
            assertTrue(candidate.requiresConfirmation());assertEquals("Record Label",candidate.candidateChannel());assertTrue(candidate.playbackAllowed());
            assertTrue(f.api.resolve(f.auth,f.track.id,true).getBody().requiresConfirmation());assertEquals(3,f.searches.get());
            f.videos=List.of(f.video("Justin Timberlake - Can't Stop the Feeling! (Live)","Record Label","public"));
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id,true)).getStatusCode().value());
        }
    }

    @Test void additionalVideoTitleDetailsAreOfferedOnlyAsUnverifiedCandidates() throws Exception {
        try(Fixture f=new Fixture(true)){
            f.videos=List.of(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Music Video Extended Edit)","Label","public"));
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id)).getStatusCode().value());
            var candidate=f.api.resolve(f.auth,f.track.id,true).getBody();assertTrue(candidate.requiresConfirmation());assertTrue(candidate.playbackAllowed());
            f.videos=List.of(f.video("Justin Timberlake - Can't Stop the Feeling! Music Clip (Film Theme)","Label","public"));
            assertTrue(f.api.resolve(f.auth,f.track.id,true).getBody().requiresConfirmation());
            for(String title:List.of("Justin Timberlake - Can't Stop the Feeling! (Official Live Video)","Justin Timberlake - Can't Stop the Feeling! (Official Cover Video)","Different Artist - Can't Stop the Feeling! (Official Video)")) {
                f.videos=List.of(f.video(title,"Label","public"));assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id,true)).getStatusCode().value());
            }
        }
    }

    @Test void alternativesIncludeOnlyPlayableUnverifiedMatches() throws Exception {
        try(Fixture f=new Fixture(true)){
            var first=new LinkedHashMap<>(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","First Label","public"));
            var second=new LinkedHashMap<>(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Second Label","public"));second.put("id","abcdefghij2");
            var blocked=new LinkedHashMap<>(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Third Label","public"));blocked.put("id","abcdefghij3");blocked.put("status",Map.of("privacyStatus","public","madeForKids",false,"embeddable",false));
            f.videos=List.of(first,second,blocked);
            var result=f.api.resolve(f.auth,f.track.id,true).getBody();assertTrue(result.requiresConfirmation());
            assertEquals(1,result.alternatives().size());assertEquals("abcdefghij2",result.alternatives().get(0).videoId());
            assertEquals("Second Label",result.alternatives().get(0).candidateChannel());
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id)).getStatusCode().value());
        }
    }

    @Test void playableCandidateIsOfferedWhenOfficialVideoCannotBeEmbedded() throws Exception {
        try(Fixture f=new Fixture(true)){
            var official=new LinkedHashMap<>(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Justin Timberlake","public"));
            official.put("status",Map.of("privacyStatus","public","madeForKids",false,"embeddable",false));
            var alternate=new LinkedHashMap<>(f.video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Record Label","public"));
            alternate.put("id","abcdefghij2");f.videos=List.of(official,alternate);
            var legacy=f.api.resolve(f.auth,f.track.id).getBody();assertFalse(legacy.playbackAllowed());assertFalse(legacy.requiresConfirmation());
            var result=f.api.resolve(f.auth,f.track.id,true).getBody();
            assertEquals("abcdefghij2",result.videoId());assertTrue(result.playbackAllowed());assertTrue(result.requiresConfirmation());
            assertEquals(2,f.searches.get(),"Unplayable official videos must not be cached as successful matches.");
        }
    }

    @Test void onlyTheAuthenticatedOwnersGrantIsUsed() throws Exception {
        try(Fixture f=new Fixture(false)){
            assertEquals(401,assertThrows(ResponseStatusException.class,()->f.api.resolve(null,f.track.id)).getStatusCode().value());
            Map<String,String> other=f.users.register();String auth="Bearer "+other.get("token");
            f.connections.connection(other.get("userId"),"youtube",new PlatformClient.Tokens("fixture-owner-grant","fixture-refresh","fixture-channel","",Instant.now().plusSeconds(3600)));
            assertEquals(503,assertThrows(ResponseStatusException.class,()->f.api.resolve(f.auth,f.track.id)).getStatusCode().value());assertEquals(0,f.searches.get());
            assertTrue(f.api.resolve(auth,f.track.id).getBody().playbackAllowed());assertEquals("Bearer fixture-owner-grant",f.authorization);
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.api.resolve(auth,UUID.randomUUID().toString())).getStatusCode().value());
            assertEquals(1,f.searches.get());
        }
    }

    @Test void errorsStayErrorsAndDeletedCachedVideosTriggerAnotherSearch() throws Exception {
        try(Fixture f=new Fixture(true)){
            f.searchCode=429;assertEquals(429,assertThrows(PlatformClient.Failure.class,()->f.api.resolve(f.auth,f.track.id)).status);
            f.searchCode=200;assertTrue(f.api.resolve(f.auth,f.track.id).getBody().playbackAllowed());
            f.statusCode=404;assertTrue(f.api.resolve(f.auth,f.track.id).getBody().playbackAllowed());
            assertEquals(3,f.searches.get());
        }
    }

    @Test void multilingualChannelsAndGeneratedReleasesNeedNoHardcodedSongList() throws Exception {
        try(Fixture f=new Fixture(true)){
            f.track.title="夜に駆ける";f.track.artist="YOASOBI";
            f.videos=List.of(f.video("YOASOBI「夜に駆ける」 Official Music Video","Ayase / YOASOBI","public"));
            assertNotNull(f.remote.findPlayback(f.track,null));
            f.track.title="R&B";f.track.artist="Example Artist";
            f.videos=List.of(f.video("R&amp;B","Example Artist - Topic","public"));assertNotNull(f.remote.findPlayback(f.track,null));
            var generated=new LinkedHashMap<>(f.video("R&B","Label Releases","public"));
            generated.put("snippet",Map.of("title","R&B","channelTitle","Label Releases","description","Provided to YouTube by Label\nR&B · Example Artist\nAuto-generated by YouTube."));
            f.videos=List.of(generated);assertNotNull(f.remote.findPlayback(f.track,null));
            f.status=Map.of("embeddable",true);f.videos=List.of(f.video("R&B","Example Artist - Topic","public"));
            assertFalse(f.remote.findPlayback(f.track,null).playbackAllowed());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger searches=new AtomicInteger(),details=new AtomicInteger(),statusChecks=new AtomicInteger();
        volatile int searchCode=200,statusCode=200;
        volatile Map<String,Object> status=Map.of("madeForKids",false,"embeddable",true);
        volatile List<Map<String,Object>> videos;
        volatile String authorization="";
        final Store users;final PlatformStore connections;final PlatformClient remote;final RecommendationCatalogue catalogue;final PlaybackApi api;final Track track;final String auth;
        Fixture(boolean key) throws Exception {
            server.createContext("/search",exchange->{
                searches.incrementAndGet();assertTrue(exchange.getRequestURI().getRawQuery().contains("type=video"));assertTrue(exchange.getRequestURI().getRawQuery().contains("q="));assertTrue(exchange.getRequestURI().getRawQuery().contains("maxResults=50"));
                byte[] body=json.writeValueAsBytes(Map.of("items",videos.stream().map(v->Map.of("id",Map.of("videoId",v.get("id")))).toList()));
                exchange.sendResponseHeaders(searchCode,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/videos",exchange->{
                String grant=exchange.getRequestHeaders().getFirst("Authorization");authorization=grant==null?"":grant;
                boolean searching=exchange.getRequestURI().getRawQuery().contains("part=snippet,status");
                if(searching)details.incrementAndGet();else statusChecks.incrementAndGet();
                byte[] body=json.writeValueAsBytes(Map.of("items",searching?videos:List.of(Map.of("id",VIDEO,"status",status))));
                exchange.sendResponseHeaders(searching?200:statusCode,body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
            var source=new DriverManagerDataSource("jdbc:h2:mem:playback-resolution-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            var db=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);
            catalogue=new RecommendationCatalogue(db,manager,json,"https://musicbrainz.org/ws/2/recording/");
            track=json.readValue(json.writeValueAsString(catalogue.all().get(0)),Track.class);track.id=UUID.randomUUID().toString();track.sourceUrl="https://musicbrainz.org/recording/"+track.id;
            track.title="Can't Stop the Feeling!";track.artist="Justin Timberlake";track.youtubeVideoId="";
            db.update("INSERT INTO recommendation_tracks (id,track_json) VALUES (?,?)",track.id,json.writeValueAsString(track));
            videos=List.of(video("Justin Timberlake - Can't Stop the Feeling! (Official Video)","Justin Timberlake","public"));
            users=new Store(db,manager,json,200,20);connections=new PlatformStore(db,manager,json,Base64.getEncoder().encodeToString(new byte[32]));
            remote=new PlatformClient(json,key?Map.of("YOUTUBE_API_KEY","fixture-key"):Map.of(),Map.of("youtube","http://127.0.0.1:"+server.getAddress().getPort()));
            api=new PlaybackApi(users,connections,remote,catalogue);auth="Bearer "+users.register().get("token");
        }
        Map<String,Object> video(String title,String channel,String privacy){var state=new LinkedHashMap<>(status);state.put("privacyStatus",privacy);return Map.of("id",VIDEO,"snippet",Map.of("title",title,"channelTitle",channel),"status",state);}
        public void close(){catalogue.close();remote.close();server.stop(0);}
    }
}
