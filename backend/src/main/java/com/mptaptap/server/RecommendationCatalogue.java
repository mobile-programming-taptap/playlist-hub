package com.mptaptap.server;

import com.mptaptap.core.Track;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Only public CC0 recording facts enter this catalogue; imports remain separate. */
@Component
final class RecommendationCatalogue {
    private static final String UUID_PATTERN="[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}";
    private final Map<String,Track> seeds=new LinkedHashMap<>();
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final URI endpoint;
    @Value("${taptap.support-email:}") private String supportEmail="";
    private final BoundedHttp client=new BoundedHttp(Duration.ofSeconds(3));
    @PreDestroy void close(){client.close();}
    private long nextRequest;
    private final java.util.concurrent.locks.ReentrantLock searchLock=new java.util.concurrent.locks.ReentrantLock(true);

    RecommendationCatalogue(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${taptap.catalogue.endpoint:https://musicbrainz.org/ws/2/recording/}") String endpoint) throws Exception {
        this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.endpoint=URI.create(endpoint);
        if(!endpoint.equals("https://musicbrainz.org/ws/2/recording/")
                &&!(List.of("localhost","127.0.0.1").contains(this.endpoint.getHost())&&"http".equals(this.endpoint.getScheme())&&this.endpoint.getUserInfo()==null))
            throw new IllegalArgumentException("Catalogue endpoint must be MusicBrainz, or loopback for checks.");
        try(var input=new ClassPathResource("tracks.json").getInputStream()) {
            for(Track track:json.readerFor(Track[].class).without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).<Track[]>readValue(input))
                if(!track.usable()||seeds.put(track.id,track)!=null)throw new IllegalStateException("Invalid catalogue entry");
        }
    }

    // ponytail: scan this prototype's verified catalogue; indexed retrieval if it outgrows memory.
    List<Track> all() {
        Map<String,Track> tracks=new LinkedHashMap<>(seeds);
        for(Track track:db.query("SELECT track_json FROM recommendation_tracks ORDER BY id",(rs,row)->read(rs.getString(1))))tracks.put(track.id,track);
        return new ArrayList<>(tracks.values());
    }

    Track track(String id) {
        List<Track> tracks=db.query("SELECT track_json FROM recommendation_tracks WHERE id=?",(rs,row)->read(rs.getString(1)),id);
        if(tracks.isEmpty()){Track seed=seeds.get(id);if(seed!=null)return seed;throw new ResponseStatusException(HttpStatus.NOT_FOUND,"곡을 찾을 수 없어요.");}
        return tracks.get(0);
    }

    private Track read(String value) {
        Track track=json.readValue(value,Track.class);
        if(!track.usable())throw new IllegalStateException("Invalid verified recording");
        return track;
    }

    List<Track> resolve(List<OpenAi.Suggestion> candidates) throws Exception {
        return resolve(candidates,Duration.ofSeconds(12));
    }

    List<Track> resolve(List<OpenAi.Suggestion> candidates,Duration timeout) throws Exception {
        long deadline=System.nanoTime()+Math.min(Duration.ofSeconds(12).toNanos(),timeout.toNanos());
        if(candidates.isEmpty())return List.of();
        if(candidates.size()>12)throw new IllegalArgumentException("Too many song candidates");
        List<Track> known=all();List<OpenAi.Suggestion> missing=new ArrayList<>();
        Map<String,Track> verified=new LinkedHashMap<>();Set<String> identities=new java.util.HashSet<>();
        for(OpenAi.Suggestion song:candidates) {
            if(!identities.add(normalized(song.title())+"|"+normalized(song.artist())))continue;
            Track cached=known.stream().filter(track->normalized(track.title).equals(normalized(song.title()))
                    &&normalized(track.artist).equals(normalized(song.artist()))).findFirst().orElse(null);
            if(cached==null)missing.add(song);
            else {
                // Reclassify every verified recording without mutating bundled identity facts or saved snapshots.
                cached=json.readValue(json.writeValueAsString(cached),Track.class);cached.tags=new ArrayList<>(song.tags());cached.language=song.language();
                cached.tagsSource="OpenAI song classification; recording identity verified by MusicBrainz";
                verified.put(cached.id,cached);
            }
        }
        if(missing.isEmpty()){List<Track> result=new ArrayList<>(verified.values());remember(result);return result;}
        String query=missing.stream().map(song->"(recording:"+literal(song.title())+" AND artist:"+literal(song.artist())+")")
                .collect(Collectors.joining(" OR "));
        JsonNode rows=search(query,deadline).path("recordings");
        for(OpenAi.Suggestion song:missing) {
            for(JsonNode row:rows) {
                String id=row.path("id").asString(),title=row.path("title").asString();
                if(!id.matches(UUID_PATTERN)||row.path("video").asBoolean(false)||!normalized(title).equals(normalized(song.title())))continue;
                String version=row.path("disambiguation").asString().toLowerCase(Locale.ROOT);
                if(version.matches("(?s).*(live|remix|karaoke|cover|demo|instrumental|atmos).*"))continue;
                boolean artistMatches=false;StringBuilder artist=new StringBuilder();
                for(JsonNode credit:row.path("artist-credit")) {
                    String name=credit.path("name").asString(credit.path("artist").path("name").asString());
                    if(normalized(name).equals(normalized(song.artist()))||normalized(credit.path("artist").path("name").asString()).equals(normalized(song.artist())))artistMatches=true;
                    artist.append(name).append(credit.path("joinphrase").asString());
                }
                artistMatches|=normalized(artist.toString()).equals(normalized(song.artist()));
                if(!artistMatches||artist.isEmpty()||verified.containsKey(id))continue;
                Track track=new Track();track.id=id;track.title=title;track.artist=artist.toString();
                track.tags=new ArrayList<>(song.tags());track.language=song.language();
                track.source="MusicBrainz";track.sourceUrl="https://musicbrainz.org/recording/"+id;
                track.license="CC0-1.0";track.licenseReference="https://musicbrainz.org/doc/About/Data_License";
                track.tagsSource="OpenAI song classification; recording identity verified by MusicBrainz";
                track.aiUseAllowed=true;track.checkedAt=Instant.now().toString();
                for(JsonNode release:row.path("releases")) {
                    String releaseId=release.path("id").asString();
                    if(releaseId.matches(UUID_PATTERN)){track.artworkUrl="https://coverartarchive.org/release/"+releaseId+"/front-500";break;}
                }
                if(track.usable()){verified.put(id,track);break;}
            }
        }
        List<Track> result=new ArrayList<>(verified.values());remember(result);return result;
    }

    private synchronized void remember(List<Track> tracks) {
        tx.executeWithoutResult(status->{for(Track track:tracks) {
            String snapshot=json.writeValueAsString(track);
            if(db.update("UPDATE recommendation_tracks SET track_json=? WHERE id=?",snapshot,track.id)==0)
                db.update("INSERT INTO recommendation_tracks (id,track_json) VALUES (?,?)",track.id,snapshot);
        }});
    }

    // ponytail: one application instance shares the official one-request/second gate; coordinate instances if deployed as a cluster.
    private JsonNode search(String query,long deadline) throws Exception {
        long budget=deadline-System.nanoTime();
        if(budget<=0||!searchLock.tryLock(Math.min(Duration.ofSeconds(2).toNanos(),budget),java.util.concurrent.TimeUnit.NANOSECONDS))throw new IllegalStateException("CATALOGUE_BUSY");
        try {
            for(int attempt=0;attempt<2;attempt++) {
                long remaining=nextRequest-System.nanoTime();if(remaining>0){if(remaining>=deadline-System.nanoTime())throw new IllegalStateException("CATALOGUE_BUSY");Thread.sleep(Duration.ofNanos(remaining));}
                remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("CATALOGUE_BUSY");
                nextRequest=System.nanoTime()+1_000_000_000L;
                URI url=URI.create(endpoint+"?fmt=json&limit=100&query="+URLEncoder.encode(query,StandardCharsets.UTF_8));
                String contact=PublicPagesApi.validatedEmail(supportEmail);
                HttpRequest request=HttpRequest.newBuilder(url).timeout(Duration.ofNanos(Math.min(Duration.ofSeconds(6).toNanos(),remaining)))
                        .header("User-Agent","TapTap/1.0 ("+(contact.isEmpty()?"https://github.com/mobile-programming-taptap/playlist-hub":contact)+")")
                        .header("Accept","application/json").GET().build();
                final BoundedHttp.Reply response;
                try {response=client.send(request,4_000_000);}
                catch(java.io.IOException error) {
                    if(attempt==0&&!"RESPONSE_TOO_LARGE".equals(error.getMessage())&&deadline-System.nanoTime()>1_000_000_000L)continue;
                    throw error;
                }
                if(response.status()!=200) {
                    if(attempt==0&&List.of(502,503,504).contains(response.status()))continue;
                    throw new IllegalStateException("CATALOGUE_HTTP_"+response.status());
                }
                JsonNode result=json.readTree(response.body());
                if(!result.path("recordings").isArray())throw new IllegalStateException("CATALOGUE_INVALID");
                org.slf4j.LoggerFactory.getLogger(RecommendationCatalogue.class).info("Recording search: returned={}, total={}, bytes={}",
                    result.path("recordings").size(),result.path("count").asInt(),response.body().length);
                return result;
            }
            throw new IllegalStateException("CATALOGUE_UNAVAILABLE");
        }finally{searchLock.unlock();}
    }

    private static String literal(String value) {return "\""+value.replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/])","\\\\$1")+"\"";}
    private static String normalized(String value) {return Normalizer.normalize(value,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
}
