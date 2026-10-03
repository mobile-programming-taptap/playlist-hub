package com.mptaptap.server;

import com.mptaptap.core.Track;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Component
public class Store {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final int dailyLimit;
    private final int hourlyLimit;

    Store(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json,
          @Value("${taptap.ai.daily-limit}") int dailyLimit,
          @Value("${taptap.ai.user-hourly-limit}") int hourlyLimit) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.json = json;
        this.dailyLimit = dailyLimit; this.hourlyLimit = hourlyLimit;
    }

    public synchronized Map<String, String> register() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO users VALUES (?, ?, ?)", id, hash(token), Instant.now().toString());
        return Map.of("userId", id, "token", token);
    }

    public String user(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "앱을 다시 연결해 주세요.");
        List<String> ids = db.query("SELECT id FROM users WHERE token_hash=?", (rs, row) -> rs.getString(1), hash(authorization.substring(7)));
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "앱을 다시 연결해 주세요.");
        return ids.get(0);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public Map<String, Integer> feedback(String user) {
        Map<String, Integer> result = new LinkedHashMap<>();
        db.query("SELECT track_id,reaction FROM recommendation_feedback WHERE user_id=?", rs -> {
            result.put(rs.getString(1), rs.getInt(2));
        }, user);
        return result;
    }

    public List<String> savedTracks(String user) {
        return db.query("SELECT DISTINCT i.track_id FROM playlist_items i JOIN playlists p ON p.id=i.playlist_id WHERE p.user_id=?", (rs,row) -> rs.getString(1), user);
    }

    // ponytail: serialize writes in this single-server prototype; database upserts for multiple instances.
    public synchronized void feedback(String user, String track, int reaction) {
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM recommendation_feedback WHERE user_id=? AND track_id=?", user, track);
            if (reaction != 0) db.update("INSERT INTO recommendation_feedback VALUES (?,?,?,?)", user, track, reaction, Instant.now().toString());
        });
    }

    public List<Map<String, Object>> playlists(String user) {
        return db.query("SELECT p.id,p.title,p.created_at,COUNT(i.id) AS size FROM playlists p LEFT JOIN playlist_items i ON p.id=i.playlist_id WHERE p.user_id=? GROUP BY p.id,p.title,p.created_at ORDER BY p.created_at DESC,p.id", (rs, row) ->
                Map.of("id", rs.getString(1), "title", rs.getString(2), "createdAt", rs.getString(3), "size", rs.getInt(4)), user);
    }

    public synchronized Map<String, Object> createPlaylist(String user, String title) {
        int count = db.queryForObject("SELECT COUNT(*) FROM playlists WHERE user_id=?", Integer.class, user);
        if (count >= 100) throw new ResponseStatusException(HttpStatus.CONFLICT, "목록은 최대 100개까지 만들 수 있어요.");
        String id = UUID.randomUUID().toString(); String at = Instant.now().toString();
        db.update("INSERT INTO playlists VALUES (?,?,?,?)", id, user, title, at);
        return Map.of("id", id, "title", title, "createdAt", at, "size", 0);
    }

    public Map<String, Object> ownedPlaylist(String user, String id) {
        List<Map<String, Object>> rows = db.query("SELECT id,title,created_at FROM playlists WHERE user_id=? AND id=?", (rs, row) ->
                Map.of("id", rs.getString(1), "title", rs.getString(2), "createdAt", rs.getString(3)), user, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "목록을 찾을 수 없어요.");
        return rows.get(0);
    }

    // ponytail: share this server's write lock; database snapshot transactions for multiple instances.
    public synchronized Map<String, Object> playlist(String user, String playlist) {
        Map<String, Object> result = new LinkedHashMap<>(ownedPlaylist(user, playlist));
        List<Map<String, Object>> items = db.query("SELECT id,track_id,position,track_json FROM playlist_items WHERE playlist_id=? ORDER BY position,id", (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString(1)); item.put("trackId", rs.getString(2)); item.put("position", rs.getInt(3));
            String snapshot = rs.getString(4);
            if (snapshot != null) item.put("track", json.readValue(snapshot, Track.class));
            return item;
        }, playlist);
        result.put("items", items);
        return result;
    }

    public synchronized Map<String, Object> add(String user, String playlist, Track track) {
        ownedPlaylist(user, playlist);
        List<Map<String, Object>> existing = db.query("SELECT id,track_id,position FROM playlist_items WHERE playlist_id=? AND track_id=?", (rs, row) ->
                Map.of("id", rs.getString(1), "trackId", rs.getString(2), "position", rs.getInt(3)), playlist, track.id);
        if (!existing.isEmpty()) return existing.get(0);
        int count = db.queryForObject("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?", Integer.class, playlist);
        if (count >= 500) throw new ResponseStatusException(HttpStatus.CONFLICT, "한 목록에는 500곡까지 저장할 수 있어요.");
        int position = db.queryForObject("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_items WHERE playlist_id=?", Integer.class, playlist);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO playlist_items (id,playlist_id,track_id,position,track_json) VALUES (?,?,?,?,?)", id, playlist, track.id, position, json.writeValueAsString(track));
        return Map.of("id", id, "trackId", track.id, "position", position);
    }

    public synchronized void remove(String user, String playlist, String item) {
        ownedPlaylist(user, playlist);
        db.update("DELETE FROM playlist_items WHERE playlist_id=? AND id=?", playlist, item);
    }

    public synchronized List<Map<String, Object>> addAll(String user, String playlist, List<Track> tracks) {
        ownedPlaylist(user, playlist);
        return tx.execute(status -> tracks.stream().map(track -> add(user, playlist, track)).toList());
    }

    public synchronized void rename(String user, String playlist, String title) {
        ownedPlaylist(user, playlist);
        db.update("UPDATE playlists SET title=? WHERE id=? AND user_id=?", title, playlist, user);
    }

    public synchronized void delete(String user, String playlist) {
        ownedPlaylist(user, playlist);
        db.update("DELETE FROM playlists WHERE id=? AND user_id=?", playlist, user);
    }

    public synchronized void reorder(String user, String playlist, List<String> ids) {
        ownedPlaylist(user, playlist);
        tx.executeWithoutResult(status -> {
            List<String> current = db.query("SELECT id FROM playlist_items WHERE playlist_id=?", (rs,row) -> rs.getString(1), playlist);
            if (ids == null || ids.size() > 500 || ids.size() != current.size() || !new HashSet<>(ids).equals(new HashSet<>(current)))
                throw new IllegalArgumentException("목록의 모든 곡을 중복 없이 지정해 주세요. 목록이 바뀌었다면 다시 열어주세요.");
            for (int i=0;i<ids.size();i++) db.update("UPDATE playlist_items SET position=? WHERE playlist_id=? AND id=?", i, playlist, ids.get(i));
        });
    }

    public synchronized void clearFeedback(String user) {
        db.update("DELETE FROM recommendation_feedback WHERE user_id=?", user);
    }

    public synchronized boolean reserveAi(String user) {
        Instant now = Instant.now();
        db.update("DELETE FROM ai_usage WHERE used_at < ?", now.minus(2, ChronoUnit.DAYS).toString());
        int daily = db.queryForObject("SELECT COUNT(*) FROM ai_usage WHERE used_at>=?", Integer.class, now.truncatedTo(ChronoUnit.DAYS).toString());
        int hourly = db.queryForObject("SELECT COUNT(*) FROM ai_usage WHERE user_id=? AND used_at>=?", Integer.class, user, now.minus(1, ChronoUnit.HOURS).toString());
        if (daily >= dailyLimit || hourly >= hourlyLimit) return false;
        db.update("INSERT INTO ai_usage VALUES (?,?,?)", UUID.randomUUID().toString(), user, now.toString());
        return true;
    }
}
