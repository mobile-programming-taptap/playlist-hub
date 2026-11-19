package com.mptaptap.server;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Official-video checks per recording, kept apart from the CC0 recording records.
 * An empty video id means "no official video found". Found videos are rechecked after 30 days, misses after 7.
 */
@Component
class YoutubeLinks {
    private static final Duration FOUND=Duration.ofDays(30),MISSING=Duration.ofDays(7);
    private final JdbcTemplate db;
    YoutubeLinks(JdbcTemplate db){this.db=db;}

    /** The video id, "" when known to have none, or empty when unknown or expired. */
    Optional<String> fresh(String trackId,Instant now) {
        return db.query("SELECT video_id,checked_at FROM youtube_links WHERE track_id=?",(rs,row)->{
            String video=rs.getString(1);Instant checked=Instant.parse(rs.getString(2));
            return checked.plus(video.isEmpty()?MISSING:FOUND).isAfter(now)?Optional.of(video):Optional.<String>empty();
        },trackId).stream().findFirst().flatMap(found->found);
    }

    synchronized void remember(String trackId,String videoId,Instant now) {
        if(!videoId.isEmpty()&&!videoId.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("Invalid video id");
        db.update("DELETE FROM youtube_links WHERE track_id=?",trackId);
        db.update("INSERT INTO youtube_links (track_id,video_id,checked_at) VALUES (?,?,?)",trackId,videoId,now.toString());
    }

    void forget(String trackId){db.update("DELETE FROM youtube_links WHERE track_id=?",trackId);}
}
