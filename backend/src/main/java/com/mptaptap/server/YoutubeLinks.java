package com.mptaptap.server;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Video checks per recording, kept apart from the CC0 recording records.
 * A check is an official video, an unconfirmed candidate, or none. Official videos are rechecked after 30 days,
 * candidates and misses after 7. Background checks share a daily search budget so playing a song still has quota left.
 */
@Component
class YoutubeLinks {
    record Link(String videoId,boolean verified) { boolean none(){return videoId.isEmpty();} }
    private static final Duration VERIFIED=Duration.ofDays(30),OTHER=Duration.ofDays(7);
    private final JdbcTemplate db;
    private final int dailyChecks;
    YoutubeLinks(JdbcTemplate db){this(db,60);}
    @Autowired YoutubeLinks(JdbcTemplate db,@Value("${taptap.youtube.check-daily-limit:60}") int dailyChecks){this.db=db;this.dailyChecks=dailyChecks;}

    /** The last check while it is still valid. */
    Optional<Link> fresh(String trackId,Instant now) {
        return db.query("SELECT video_id,verified,checked_at FROM youtube_links WHERE track_id=?",(rs,row)->{
            Link link=new Link(rs.getString(1),rs.getBoolean(2));Instant checked=Instant.parse(rs.getString(3));
            return checked.plus(link.verified()?VERIFIED:OTHER).isAfter(now)?Optional.of(link):Optional.<Link>empty();
        },trackId).stream().findFirst().flatMap(found->found);
    }

    /** An official video, or none when {@code videoId} is empty. */
    void remember(String trackId,String videoId,Instant now){store(trackId,videoId,!videoId.isEmpty(),now);}
    void rememberCandidate(String trackId,String videoId,Instant now){store(trackId,videoId,false,now);}
    private synchronized void store(String trackId,String videoId,boolean verified,Instant now) {
        if(!videoId.isEmpty()&&!videoId.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("Invalid video id");
        db.update("DELETE FROM youtube_links WHERE track_id=?",trackId);
        db.update("INSERT INTO youtube_links (track_id,video_id,verified,checked_at) VALUES (?,?,?,?)",trackId,videoId,verified,now.toString());
    }
    void forget(String trackId){db.update("DELETE FROM youtube_links WHERE track_id=?",trackId);}

    /** Takes one background search from today's budget; YouTube quota days start at midnight Pacific time. */
    synchronized boolean reserveCheck(Instant now) {
        String day=java.time.LocalDate.ofInstant(now,java.time.ZoneId.of("America/Los_Angeles")).toString();
        db.update("DELETE FROM youtube_checks WHERE check_day<>?",day);
        Integer used=db.query("SELECT used FROM youtube_checks WHERE check_day=?",(rs,row)->rs.getInt(1),day).stream().findFirst().orElse(null);
        if(used!=null&&used>=dailyChecks)return false;
        if(used==null)db.update("INSERT INTO youtube_checks (check_day,used) VALUES (?,1)",day);
        else db.update("UPDATE youtube_checks SET used=used+1 WHERE check_day=?",day);
        return true;
    }
}
