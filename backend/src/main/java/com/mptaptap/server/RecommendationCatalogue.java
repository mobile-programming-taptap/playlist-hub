package com.mptaptap.server;

import com.mptaptap.core.Recommender;
import com.mptaptap.core.ReleaseYear;
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
        return resolve(candidates,Duration.ofSeconds(12),null);
    }

    List<Track> resolve(List<OpenAi.Suggestion> candidates,Duration timeout) throws Exception {
        return resolve(candidates,timeout,null);
    }

    /** 조건을 함께 받아야 연주곡 요청과 제외 가수를 조회 단계에서 반영할 수 있다. */
    List<Track> resolve(List<OpenAi.Suggestion> candidates,Duration timeout,Recommender.Conditions conditions) throws Exception {
        long deadline=System.nanoTime()+Math.min(Duration.ofSeconds(12).toNanos(),timeout.toNanos());
        if(candidates.isEmpty())return List.of();
        if(candidates.size()>12)throw new IllegalArgumentException("Too many song candidates");
        int proposed=candidates.size();
        boolean wantsInstrumental=conditions!=null&&(conditions.tags.contains("instrumental")||conditions.language.equals("none"));
        List<String> excludedArtists=conditions==null?List.of():
                (conditions.excludedArtists==null?List.of():conditions.excludedArtists);
        // 연대 조건이 있으면 연도를 모르는 캐시 곡도 같은 검색에 묶어 연도를 채운다. 검색 요청 수는 늘지 않는다.
        boolean wantsDecades=conditions!=null&&conditions.decades!=null&&!conditions.decades.isEmpty();
        List<Track> undated=new ArrayList<>();
        List<Track> known=all();List<OpenAi.Suggestion> missing=new ArrayList<>();
        Map<String,Track> verified=new LinkedHashMap<>();Set<String> identities=new java.util.HashSet<>();
        int cachedHits=0,skippedByArtist=0;
        for(OpenAi.Suggestion song:candidates) {
            if(!identities.add(normalized(song.title())+"|"+normalized(song.artist())))continue;
            // 직접 제외한 가수는 조회 전에 걸러 MusicBrainz 요청을 낭비하지 않는다.
            if(artistExcluded(song.artist(),excludedArtists)){skippedByArtist++;continue;}
            Track cached=known.stream().filter(track->normalized(track.title).equals(normalized(song.title()))
                    &&normalized(track.artist).equals(normalized(song.artist()))).findFirst().orElse(null);
            if(cached==null)missing.add(song);
            else {
                // 기존 분류를 보존하고, 이번 추천에만 AI 분류를 사용한다.
                cached=json.readValue(json.writeValueAsString(cached),Track.class);
                cached.tags=new ArrayList<>(song.tags());
                cached.language=song.language();
                cached.tagsSource="OpenAI song classification; recording identity verified by MusicBrainz";
                verified.put(cached.id,cached);
                cachedHits++;
                if(wantsDecades&&cached.releaseYear==ReleaseYear.UNKNOWN)undated.add(cached);
            }
        }
        int searched=missing.size();
        if(missing.isEmpty()&&undated.isEmpty()){
            List<Track> result=new ArrayList<>(verified.values());
            logFunnel(proposed,cachedHits,searched,skippedByArtist,result.size());
            return result;
        }
        List<String> clauses=new ArrayList<>();
        for(OpenAi.Suggestion song:missing)clauses.add(clause(song.title(),song.artist()));
        for(Track track:undated)clauses.add(clause(track.title,track.artist));
        String query=String.join(" OR ",clauses);
        JsonNode rows;
        try{rows=search(query,deadline).path("recordings");}
        catch(Exception error){
            // 나머지 곡 조회가 실패해도 이미 확인한 곡은 보여 준다. 하나도 없을 때만 실패로 알린다.
            if(verified.isEmpty())throw error;
            org.slf4j.LoggerFactory.getLogger(RecommendationCatalogue.class).warn("Recording search failed; using {} verified songs: {}",verified.size(),error.getMessage());
            List<Track> result=new ArrayList<>(verified.values());
            logFunnel(proposed,cachedHits,searched,skippedByArtist,result.size());
            return result;
        }
        List<Track> discovered=new ArrayList<>();
        for(OpenAi.Suggestion song:missing) {
            for(JsonNode row:rows) {
                String id=row.path("id").asString(),title=row.path("title").asString();
                if(!id.matches(UUID_PATTERN)||row.path("video").asBoolean(false)||!normalized(title).equals(normalized(song.title())))continue;
                String version=row.path("disambiguation").asString().toLowerCase(Locale.ROOT);
                if(version.matches("(?s).*(live|remix|karaoke|cover|demo|atmos).*"))continue;
                // 연주곡을 요청했을 때만 instrumental 버전을 살린다.
                if(version.contains("instrumental")&&!wantsInstrumental)continue;
                boolean artistMatches=false;StringBuilder artist=new StringBuilder();
                for(JsonNode credit:row.path("artist-credit")) {
                    String name=credit.path("name").asString(credit.path("artist").path("name").asString());
                    if(normalized(name).equals(normalized(song.artist()))||normalized(credit.path("artist").path("name").asString()).equals(normalized(song.artist())))artistMatches=true;
                    artist.append(name).append(credit.path("joinphrase").asString());
                }
                artistMatches|=normalized(artist.toString()).equals(normalized(song.artist()));
                if(!artistMatches||artist.isEmpty()||verified.containsKey(id))continue;
                if(artistExcluded(artist.toString(),excludedArtists)){skippedByArtist++;continue;}
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
                track.releaseYear=releaseYear(row);
                if(track.usable()){
                    verified.put(id,track);
                    if(!seeds.containsKey(id))discovered.add(track);
                    break;
                }
            }
        }
        backfill(undated,rows,known);
        List<Track> result=new ArrayList<>(verified.values());
        logFunnel(proposed,cachedHits,searched,skippedByArtist,result.size());
        if(!discovered.isEmpty())remember(discovered);
        return result;
    }

    private static String clause(String title,String artist){return "(recording:"+literal(title)+" AND artist:"+literal(artist)+")";}

    /** 녹음 검색 행의 발매 연도. 응답 형식은 API 문서 기준이고 실측하지 않았다. 판정 기준은 ReleaseYear 에 모여 있다. */
    private static int releaseYear(JsonNode row){
        List<String> dates=new ArrayList<>();
        for(JsonNode release:row.path("releases"))dates.add(release.path("date").asString());
        return ReleaseYear.of(row.path("first-release-date").asString(),dates);
    }

    /** 연도를 모르던 캐시 곡에 같은 녹음 id 의 검색 결과로 연도를 채우고 저장한다. 분류는 저장된 원래 값을 유지한다. */
    private void backfill(List<Track> undated,JsonNode rows,List<Track> known){
        if(undated.isEmpty())return;
        List<Track> filled=new ArrayList<>();
        for(Track track:undated)for(JsonNode row:rows) {
            if(!row.path("id").asString().equals(track.id))continue;
            int year=releaseYear(row);
            if(year==ReleaseYear.UNKNOWN)break;
            track.releaseYear=year;
            Track original=known.stream().filter(candidate->candidate.id.equals(track.id)).findFirst().orElse(null);
            if(original!=null){Track stored=json.readValue(json.writeValueAsString(original),Track.class);stored.releaseYear=year;filled.add(stored);}
            break;
        }
        org.slf4j.LoggerFactory.getLogger(RecommendationCatalogue.class).info("Release year backfill: undated={}, filled={}",undated.size(),filled.size());
        if(!filled.isEmpty())store(filled);
    }

    private synchronized void store(List<Track> tracks) {
        tx.executeWithoutResult(status->{for(Track track:tracks) {
            String snapshot=json.writeValueAsString(track);
            if(db.update("UPDATE recommendation_tracks SET track_json=? WHERE id=?",snapshot,track.id)==0)
                db.update("INSERT INTO recommendation_tracks (id,track_json) VALUES (?,?) ON CONFLICT DO NOTHING",track.id,snapshot);
        }});
    }

    /** AI 후보가 어느 단계에서 줄었는지 남긴다. 조건 부족과 외부 오류를 구분하는 근거가 된다. */
    private void logFunnel(int proposed,int cachedHits,int searched,int skippedByArtist,int resolved) {
        org.slf4j.LoggerFactory.getLogger(RecommendationCatalogue.class).info(
            "Catalogue funnel: proposed={}, cached={}, searched={}, skippedByArtist={}, resolved={}, lostInSearch={}",
            proposed,cachedHits,searched,skippedByArtist,resolved,(searched+skippedByArtist)-(resolved-cachedHits));
    }

    private static boolean artistExcluded(String credit,List<String> excludedArtists) {
        if(excludedArtists==null||excludedArtists.isEmpty())return false;
        String artist=Recommender.normalizeArtist(credit);
        if(artist.isEmpty())return false;
        for(String excluded:excludedArtists)
            if(artist.equals(excluded)||artist.startsWith(excluded))return true;
        return false;
    }

    private synchronized void remember(List<Track> tracks) {
        tx.executeWithoutResult(status->{for(Track track:tracks) {
            String snapshot=json.writeValueAsString(track);
            db.update("INSERT INTO recommendation_tracks (id,track_json) VALUES (?,?) ON CONFLICT DO NOTHING",track.id,snapshot);
        }});
    }

    // ponytail: one application instance shares the official one-request/second gate; coordinate instances if deployed as a cluster.
    private JsonNode search(String query,long deadline) throws Exception {
        long budget=deadline-System.nanoTime();
        if(budget<=0||!searchLock.tryLock(Math.min(Duration.ofSeconds(2).toNanos(),budget),java.util.concurrent.TimeUnit.NANOSECONDS))throw new IllegalStateException("CATALOGUE_BUSY");
        try {
            // MusicBrainz answers 503 when it is busy; two more tries, each waiting longer, fit the shared deadline.
            for(int attempt=0;attempt<3;attempt++) {
                long remaining=nextRequest-System.nanoTime();if(remaining>0){if(remaining>=deadline-System.nanoTime())throw new IllegalStateException("CATALOGUE_BUSY");Thread.sleep(Duration.ofNanos(remaining));}
                remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("CATALOGUE_BUSY");
                nextRequest=System.nanoTime()+(attempt+1)*1_000_000_000L;
                URI url=URI.create(endpoint+"?fmt=json&limit=100&query="+URLEncoder.encode(query,StandardCharsets.UTF_8));
                String contact=PublicPagesApi.validatedEmail(supportEmail);
                HttpRequest request=HttpRequest.newBuilder(url).timeout(Duration.ofNanos(Math.min(Duration.ofSeconds(6).toNanos(),remaining)))
                        .header("User-Agent","TapTap/1.0 ("+(contact.isEmpty()?"https://github.com/mobile-programming-taptap/playlist-hub":contact)+")")
                        .header("Accept","application/json").GET().build();
                final BoundedHttp.Reply response;
                try {response=client.send(request,4_000_000);}
                catch(java.io.IOException error) {
                    if(attempt<2&&!"RESPONSE_TOO_LARGE".equals(error.getMessage())&&nextRequest<deadline-1_000_000_000L)continue;
                    throw error;
                }
                if(response.status()!=200) {
                    if(attempt<2&&List.of(502,503,504).contains(response.status())&&nextRequest<deadline-1_000_000_000L)continue;
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

    private static String literal(String value){return "\""+value.replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/])","\\\\$1")+"\"";}
    private static String normalized(String value) {
        String base=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
        return base;
    }
}