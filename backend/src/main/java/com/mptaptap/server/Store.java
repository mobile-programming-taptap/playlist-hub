package com.mptaptap.server;

import com.mptaptap.core.Track;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final Map<String, AuthFailures> authFailures = new LinkedHashMap<>();
    private final String dummyPasswordHash = passwordHash("not-an-account-password");
    private record AuthFailures(int count, Instant since) {}

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
        String tokenHash = tokenHash(authorization);
        List<String> ids = db.query("SELECT user_id FROM account_sessions WHERE token_hash=?", (rs, row) -> rs.getString(1), tokenHash);
        if (ids.isEmpty()) ids = db.query("SELECT u.id FROM users u WHERE u.token_hash=? AND NOT EXISTS (SELECT 1 FROM accounts a WHERE a.user_id=u.id)", (rs, row) -> rs.getString(1), tokenHash);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "앱을 다시 연결해 주세요.");
        return ids.get(0);
    }

    private static String tokenHash(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "앱을 다시 연결해 주세요.");
        return hash(authorization.substring(7));
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized Map<String, String> registerAccount(String authorization, String email, String password, String name, String address) {
        email = email(email); checkPassword(password); name = accountName(name);
        checkAuthLimit(email, address);
        if (db.queryForObject("SELECT COUNT(*) FROM accounts WHERE email=?",Integer.class,email) > 0) {
            failedAuth(email,address);
            throw new ResponseStatusException(HttpStatus.CONFLICT,"이 이메일로 가입한 계정이 있어요. 로그인해 주세요.");
        }
        String owner = authorization == null || authorization.isBlank() ? null : user(authorization);
        if (owner != null && db.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_id=?",Integer.class,owner) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 로그인한 계정이에요.");
        String ownerId = owner, mail = email, label = name, encoded = passwordHash(password);
        return tx.execute(status -> {
            String id = ownerId == null ? register().get("userId") : ownerId;
            db.update("INSERT INTO accounts (user_id,email,name,password_hash,created_at) VALUES (?,?,?,?,?)",id,mail,label,encoded,Instant.now().toString());
            // The anonymous owner becomes the account owner atomically; existing lists and reactions keep their IDs.
            return accountSession(id);
        });
    }

    public synchronized Map<String, String> login(String email, String password, String address) {
        email = email(email);
        if (password == null || password.length() > 128) throw new IllegalArgumentException("이메일과 비밀번호를 확인해 주세요.");
        checkAuthLimit(email,address);
        List<Map<String,String>> rows = db.query("SELECT user_id,password_hash FROM accounts WHERE email=?",(rs,row)->Map.of("id",rs.getString(1),"hash",rs.getString(2)),email);
        boolean matches = passwordMatches(password,rows.isEmpty() ? dummyPasswordHash : rows.get(0).get("hash"));
        if (rows.isEmpty() || !matches) {
            failedAuth(email,address);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"이메일 또는 비밀번호가 맞지 않아요.");
        }
        authFailures.remove("email:"+hash(email));
        return accountSession(rows.get(0).get("id"));
    }

    public Map<String,String> account(String user) {
        List<Map<String,String>> rows = db.query("SELECT user_id,email,name FROM accounts WHERE user_id=?",(rs,row)->Map.of("userId",rs.getString(1),"email",rs.getString(2),"name",rs.getString(3)),user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"아직 가입한 계정이 없어요.");
        return rows.get(0);
    }

    private Map<String,String> accountSession(String user) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO account_sessions (token_hash,user_id,created_at) VALUES (?,?,?)",hash(token),user,Instant.now().toString());
        Map<String,String> result = new LinkedHashMap<>(account(user)); result.put("token",token);
        return result;
    }

    public synchronized void logout(String authorization) {
        account(user(authorization));
        db.update("DELETE FROM account_sessions WHERE token_hash=?",tokenHash(authorization));
    }

    public synchronized void deleteAccount(String authorization, String password, String address) {
        String owner = user(authorization); Map<String,String> profile = account(owner);
        checkAuthLimit(profile.get("email"),address);
        String encoded = db.queryForObject("SELECT password_hash FROM accounts WHERE user_id=?",String.class,owner);
        if (password == null || password.length() > 128 || !passwordMatches(password,encoded)) {
            failedAuth(profile.get("email"),address);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"비밀번호가 맞지 않아요.");
        }
        tx.executeWithoutResult(status -> db.update("DELETE FROM users WHERE id=?",owner));
    }

    private static String email(String value) {
        if (value == null) throw new IllegalArgumentException("이메일을 입력해 주세요.");
        value = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (value.length()>254 || !value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))
            throw new IllegalArgumentException("이메일 형식을 확인해 주세요.");
        return value;
    }

    private static void checkPassword(String value) {
        if (value == null || value.isBlank() || value.length()<15 || value.length()>128)
            throw new IllegalArgumentException("비밀번호를 15~128자로 입력해 주세요.");
    }

    private static String accountName(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length()>80)
            throw new IllegalArgumentException("이름을 1~80자로 입력해 주세요.");
        return value.strip();
    }

    private String passwordHash(String password) {
        byte[] salt = new byte[16]; random.nextBytes(salt);
        return "pbkdf2-sha256$600000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derivePassword(password,salt,600000));
    }

    private static boolean passwordMatches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length!=4 || !parts[0].equals("pbkdf2-sha256") || !parts[1].equals("600000")) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),derivePassword(password,Base64.getDecoder().decode(parts[2]),600000));
        } catch (IllegalArgumentException error) { return false; }
    }

    private static byte[] derivePassword(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray(); PBEKeySpec spec = new PBEKeySpec(chars,salt,iterations,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
        finally { spec.clearPassword(); Arrays.fill(chars,'\0'); }
    }

    // ponytail: bounded single-server throttling; use a shared rate limiter when deploying multiple instances.
    private void checkAuthLimit(String email, String address) {
        Instant oldest = Instant.now().minus(15,ChronoUnit.MINUTES);
        authFailures.entrySet().removeIf(entry -> entry.getValue().since().isBefore(oldest));
        if (authFailures.size()>5000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"로그인 요청이 많아요. 잠시 후 다시 시도해 주세요.");
        for (String key : List.of("email:"+hash(email),"address:"+hash(address==null?"unknown":address))) {
            AuthFailures failures = authFailures.get(key);
            if (failures!=null && failures.count()>=(key.startsWith("email:")?8:40))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"시도가 너무 많아요. 15분 후 다시 시도해 주세요.");
        }
    }

    private void failedAuth(String email, String address) {
        for (String key : List.of("email:"+hash(email),"address:"+hash(address==null?"unknown":address))) {
            AuthFailures old = authFailures.get(key);
            authFailures.put(key,new AuthFailures(old==null?1:old.count()+1,old==null?Instant.now():old.since()));
        }
    }

    public Map<String, Integer> feedback(String user) {
        Map<String, Integer> result = new LinkedHashMap<>();
        db.query("SELECT track_id,reaction FROM recommendation_feedback WHERE user_id=?", rs -> {
            result.put(rs.getString(1), rs.getInt(2));
        }, user);
        return result;
    }

    public List<String> savedTracks(String user) {
        return db.query("SELECT DISTINCT i.track_id FROM playlist_items i JOIN playlists p ON p.id=i.playlist_id WHERE p.user_id=? AND i.track_id IS NOT NULL", (rs,row) -> rs.getString(1), user);
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

    public synchronized Map<String, Object> createPlaylist(String user, String title, String id) {
        if (id == null) id = UUID.randomUUID().toString();
        else if (!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("목록 생성 ID는 소문자 UUID 형식이어야 해요.");
        if (db.queryForObject("SELECT COUNT(*) FROM playlists WHERE id=?", Integer.class, id) > 0) {
            Map<String, Object> existing = new LinkedHashMap<>(ownedPlaylist(user, id));
            if (!title.equals(existing.get("title")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "목록이 이미 만들어졌어요. 원래 이름으로 다시 시도해 주세요.");
            existing.put("size", db.queryForObject("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?", Integer.class, id));
            return existing;
        }
        int count = db.queryForObject("SELECT COUNT(*) FROM playlists WHERE user_id=?", Integer.class, user);
        if (count >= 100) throw new ResponseStatusException(HttpStatus.CONFLICT, "목록은 최대 100개까지 만들 수 있어요.");
        String at = Instant.now().toString();
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
            if (snapshot != null) {
                if (rs.getString(2) == null) item.put("track", externalSnapshot(snapshot));
                else item.put("track", json.readValue(snapshot, Track.class));
            } else if (rs.getString(2) == null) item.put("track", Map.of("external",true,"needsRefresh",true));
            return item;
        }, playlist);
        result.put("items", items);
        return result;
    }

    public synchronized List<Map<String,Object>> externalItems(String user, String playlist, String sourceKey) {
        ownedPlaylist(user,playlist);
        if (sourceKey==null || sourceKey.isBlank() || sourceKey.length()>200) throw new IllegalArgumentException("가져온 목록 정보를 확인해 주세요.");
        return db.query("SELECT id,position,track_json FROM playlist_items WHERE playlist_id=? AND track_id IS NULL AND external_source_key=? ORDER BY position,id",(rs,row)->{
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("id",rs.getString(1)); item.put("position",rs.getInt(2)); item.put("trackId",null);
            String snapshot = rs.getString(3);
            item.put("track",snapshot==null?Map.of("external",true,"needsRefresh",true):externalSnapshot(snapshot));
            return item;
        },playlist,hash(sourceKey));
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

    public synchronized List<Map<String,Object>> addExternal(String user, String playlist, List<Map<String,Object>> rows, String sourceKey) {
        return addExternal(user,playlist,rows,sourceKey,Instant.now());
    }

    synchronized List<Map<String,Object>> addExternal(String user, String playlist, List<Map<String,Object>> rows, String sourceKey, Instant metadataCheckedAt) {
        ownedPlaylist(user,playlist);
        List<Map<String,Object>> snapshots = externalRows(rows,sourceKey,metadataCheckedAt);
        return tx.execute(status -> {
            List<Map<String,Object>> result = new ArrayList<>();
            for (int i=0;i<snapshots.size();i++) result.add(writeExternal(playlist,snapshots.get(i),externalKey(sourceKey,snapshots.get(i)),hash(sourceKey),false));
            return result;
        });
    }

    public synchronized List<Map<String,Object>> refreshExternal(String user, String playlist, List<Map<String,Object>> rows, String sourceKey) {
        return refreshExternal(user,playlist,rows,sourceKey,Instant.now());
    }

    synchronized List<Map<String,Object>> refreshExternal(String user, String playlist, List<Map<String,Object>> rows, String sourceKey, Instant metadataCheckedAt) {
        ownedPlaylist(user,playlist);
        List<Map<String,Object>> snapshots = externalRows(rows,sourceKey,metadataCheckedAt);
        return tx.execute(status -> {
            List<String> keys = snapshots.stream().map(row -> externalKey(sourceKey,row)).toList();
            for (Map<String,Object> item : db.query("SELECT id,external_key FROM playlist_items WHERE playlist_id=? AND external_source_key=?",(rs,row)->Map.<String,Object>of("id",rs.getString(1),"key",rs.getString(2)),playlist,hash(sourceKey)))
                if (!keys.contains(item.get("key"))) db.update("DELETE FROM playlist_items WHERE id=? AND playlist_id=?",item.get("id"),playlist);
            List<Map<String,Object>> result = new ArrayList<>();
            for (int i=0;i<snapshots.size();i++) result.add(writeExternal(playlist,snapshots.get(i),keys.get(i),hash(sourceKey),true));
            // Preserve other sources' relative order; the reviewed source follows the user's confirmed order.
            List<String> other = db.query("SELECT id FROM playlist_items WHERE playlist_id=? AND (external_source_key IS NULL OR external_source_key<>?) ORDER BY position,id",(rs,row)->rs.getString(1),playlist,hash(sourceKey));
            List<String> order = new ArrayList<>(other); result.forEach(item -> order.add((String)item.get("id")));
            reorder(user,playlist,order);
            for (int i=0;i<result.size();i++) result.get(i).put("position",other.size()+i);
            return result;
        });
    }

    public synchronized void unlinkExternal(String user, String provider) {
        if (provider==null || !List.of("spotify","youtube","youtube_music").contains(provider)) throw new IllegalArgumentException("음악 서비스 정보를 확인해 주세요.");
        tx.executeWithoutResult(status -> {
            for (Map<String,Object> row : db.query("SELECT i.id,i.track_json FROM playlist_items i JOIN playlists p ON p.id=i.playlist_id WHERE p.user_id=? AND i.track_id IS NULL AND i.track_json IS NOT NULL",(rs,index)->Map.<String,Object>of("id",rs.getString(1),"snapshot",rs.getString(2)),user)) {
                if (externalProvider(readMap((String)row.get("snapshot")),provider)) db.update("DELETE FROM playlist_items WHERE id=?",row.get("id"));
            }
            for (Map<String,Object> row : db.query("SELECT s.share_id,s.snapshot_json FROM public_playlists s JOIN playlists p ON p.id=s.playlist_id WHERE p.user_id=?",(rs,index)->Map.<String,Object>of("id",rs.getString(1),"snapshot",rs.getString(2)),user)) {
                Map<String,Object> snapshot = readMap((String)row.get("snapshot"));
                List<Map<String,Object>> items = itemList(snapshot);
                int before = items.size();
                items.removeIf(item -> item.get("track") instanceof Map<?,?> track && externalProvider(track,provider));
                if (items.size()!=before) {
                    for (int i=0;i<items.size();i++) items.get(i).put("position",i);
                    snapshot.put("size",items.size());
                    db.update("UPDATE public_playlists SET snapshot_json=? WHERE share_id=?",json.writeValueAsString(snapshot),row.get("id"));
                }
            }
        });
    }

    private static boolean externalProvider(Map<?,?> track, String provider) {
        Object source = track.containsKey("sourceProvider")?track.get("sourceProvider"):track.get("platform");
        return Boolean.TRUE.equals(track.get("external")) && (provider.equals(source) || List.of("youtube","youtube_music").contains(provider) && ("youtube".equals(source) || "youtube_music".equals(source)));
    }

    private Map<String,Object> writeExternal(String playlist, Map<String,Object> snapshot, String key, String source, boolean refresh) {
        List<Map<String,Object>> existing = db.query("SELECT id,position,track_json FROM playlist_items WHERE playlist_id=? AND external_key=?",(rs,row)->Map.of("id",rs.getString(1),"position",rs.getInt(2),"snapshot",rs.getString(3)),playlist,key);
        String id; int position;
        if (existing.isEmpty()) {
            int count = db.queryForObject("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?",Integer.class,playlist);
            if (count>=500) throw new ResponseStatusException(HttpStatus.CONFLICT,"한 목록에는 500곡까지 저장할 수 있어요.");
            id = UUID.randomUUID().toString();
            position = db.queryForObject("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_items WHERE playlist_id=?",Integer.class,playlist);
            db.update("INSERT INTO playlist_items (id,playlist_id,track_id,position,track_json,external_key,external_source_key) VALUES (?,?,NULL,?,?,?,?)",id,playlist,position,json.writeValueAsString(snapshot),key,source);
        } else {
            id = (String)existing.get(0).get("id"); position = (Integer)existing.get(0).get("position");
            Map<String,Object> old = readMap((String)existing.get(0).get("snapshot"));
            Map<String,Object> oldContent = new LinkedHashMap<>(old), newContent = new LinkedHashMap<>(snapshot);
            oldContent.remove("checkedAt"); newContent.remove("checkedAt");
            if (!refresh && !oldContent.equals(newContent))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"가져온 목록이 바뀌었어요. 변경 내용을 확인한 뒤 다시 반영해 주세요.");
            if (refresh) db.update("UPDATE playlist_items SET track_json=? WHERE id=?",json.writeValueAsString(snapshot),id);
            else snapshot = expireExternal(old);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id",id); result.put("position",position); result.put("trackId",null); result.put("track",snapshot);
        return result;
    }

    private List<Map<String,Object>> externalRows(List<Map<String,Object>> rows, String sourceKey, Instant metadataCheckedAt) {
        if (sourceKey==null || sourceKey.isBlank() || sourceKey.length()>200 || rows==null || rows.size()>500)
            throw new IllegalArgumentException("가져올 목록과 곡 정보를 확인해 주세요.");
        List<Map<String,Object>> result = new ArrayList<>(); HashSet<String> keys = new HashSet<>();
        for (int i=0;i<rows.size();i++) {
            Map<String,Object> row = rows.get(i);
            if (row==null) throw new IllegalArgumentException("곡 정보를 확인해 주세요.");
            Map<String,Object> snapshot = new LinkedHashMap<>();
            String provider = text(row.getOrDefault("sourceProvider",row.get("platform")),32,false);
            String id = text(row.getOrDefault("sourceId",row.get("externalId")),200,false);
            if (!List.of("spotify","youtube","youtube_music").contains(provider)) throw new IllegalArgumentException("음악 서비스 정보를 확인해 주세요.");
            snapshot.put("sourceProvider",provider); snapshot.put("platform",provider);
            snapshot.put("sourceId",id); snapshot.put("externalId",id);
            snapshot.put("title",text(row.get("title"),300,false)); snapshot.put("artist",text(row.get("artist"),300,true));
            for (String field:List.of("isrc","version","album","sourceRow"))
                if (row.get(field)!=null) snapshot.put(field,text(row.get(field),field.equals("sourceRow")?2048:300,true));
            String url = text(row.get("sourceUrl"),2048,true);
            if (!url.isEmpty() && !safeExternalUrl(provider,url)) throw new IllegalArgumentException("원본 곡 링크를 확인해 주세요.");
            snapshot.put("sourceUrl",url);
            Object index = row.containsKey("sourceIndex")?row.get("sourceIndex"):row.getOrDefault("position",i);
            snapshot.put("sourceIndex",integer(index,1_000_000));
            for (String field:List.of("durationMs","videoDurationSeconds","timestamp","sourceLine")) if (row.get(field)!=null) snapshot.put(field,integer(row.get(field),Integer.MAX_VALUE));
            if (row.get("sourceType")!=null) snapshot.put("sourceType",text(row.get("sourceType"),32,false));
            snapshot.put("external",true); snapshot.put("recommendationTrackId",null); snapshot.put("checkedAt",metadataCheckedAt.toString());
            if (!keys.add(externalKey(sourceKey,snapshot))) throw new IllegalArgumentException("원본 곡의 행 번호가 중복돼요.");
            result.add(snapshot);
        }
        return result;
    }

    private String externalKey(String sourceKey, Map<String,Object> row) {
        return hash(json.writeValueAsString(List.of(sourceKey,row.getOrDefault("sourceRow",""),row.get("sourceIndex"))));
    }

    private static String text(Object value, int max, boolean optional) {
        if (value==null && optional) return "";
        if (!(value instanceof String s) || s.length()>max || (!optional && s.strip().isEmpty())) throw new IllegalArgumentException("곡 정보를 확인해 주세요.");
        return s.strip();
    }

    private static int integer(Object value, int max) {
        if (!(value instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<0 || n.intValue()>max) throw new IllegalArgumentException("곡의 행 번호나 시간을 확인해 주세요.");
        return n.intValue();
    }

    static boolean safeExternalUrl(String provider, String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) return false;
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return switch(provider) {
                case "spotify" -> host.equals("open.spotify.com");
                case "youtube_music", "youtube" -> List.of("music.youtube.com","www.youtube.com","youtube.com","youtu.be").contains(host);
                default -> false;
            };
        } catch (IllegalArgumentException error) { return false; }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> readMap(String snapshot) { return new LinkedHashMap<>((Map<String,Object>)json.readValue(snapshot,Map.class)); }

    private Map<String,Object> externalSnapshot(String snapshot) { return expireExternal(readMap(snapshot)); }

    private static Map<String,Object> expireExternal(Map<String,Object> snapshot) {
        if (!Boolean.TRUE.equals(snapshot.get("external"))) return snapshot;
        boolean expired;
        try { expired = !Instant.parse((String)snapshot.get("checkedAt")).isAfter(Instant.now().minus(30,ChronoUnit.DAYS)); }
        catch (RuntimeException invalid) { expired = true; }
        if (!expired) return snapshot;
        Map<String,Object> reference = new LinkedHashMap<>();
        for (String key:List.of("platform","externalId","sourceProvider","sourceId","sourceUrl","sourceIndex","sourceLine","timestamp","checkedAt"))
            if (snapshot.containsKey(key)) reference.put(key,snapshot.get(key));
        reference.put("external",true); reference.put("recommendationTrackId",null); reference.put("needsRefresh",true);
        return reference;
    }

    @Scheduled(fixedDelay=3_600_000)
    public synchronized void purgeExpiredExternal() {
        tx.executeWithoutResult(status -> {
            for (Map<String,Object> row : db.query("SELECT id,track_json FROM playlist_items WHERE track_id IS NULL AND track_json IS NOT NULL",(rs,index)->Map.<String,Object>of("id",rs.getString(1),"snapshot",rs.getString(2)))) {
                Map<String,Object> original = readMap((String)row.get("snapshot")), expired = expireExternal(original);
                if (!expired.equals(original)) db.update("UPDATE playlist_items SET track_json=? WHERE id=?",json.writeValueAsString(expired),row.get("id"));
            }
            for (Map<String,Object> row : db.query("SELECT share_id,snapshot_json FROM public_playlists",(rs,index)->Map.<String,Object>of("id",rs.getString(1),"snapshot",rs.getString(2)))) {
                Map<String,Object> snapshot = readMap((String)row.get("snapshot"));
                expirePublic(snapshot);
                String encoded = json.writeValueAsString(snapshot);
                if (!encoded.equals(row.get("snapshot"))) db.update("UPDATE public_playlists SET snapshot_json=? WHERE share_id=?",encoded,row.get("id"));
            }
        });
    }

    public synchronized Map<String,Object> editExternal(String user, String playlist, String item, String title, String artist, Integer seconds) {
        ownedPlaylist(user,playlist);
        List<Map<String,Object>> rows = db.query("SELECT position,track_id,track_json FROM playlist_items WHERE playlist_id=? AND id=?",(rs,row)->{
            Map<String,Object> value = new LinkedHashMap<>(); value.put("position",rs.getInt(1)); value.put("trackId",rs.getString(2)); value.put("snapshot",rs.getString(3)); return value;
        },playlist,item);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"곡을 찾을 수 없어요.");
        if (rows.get(0).get("trackId")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"추천 곡의 원본 정보는 변경할 수 없어요.");
        Map<String,Object> snapshot = externalSnapshot((String)rows.get(0).get("snapshot"));
        if (Boolean.TRUE.equals(snapshot.get("needsRefresh"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"원본 정보를 다시 확인한 뒤 수정해 주세요.");
        snapshot.put("title",text(title,300,false)); snapshot.put("artist",text(artist,300,true));
        if (seconds!=null) {
            String provider = (String)snapshot.get("sourceProvider"), id = (String)snapshot.get("sourceId");
            int maximum = 21_600;
            if (snapshot.get("videoDurationSeconds") instanceof Number duration && duration.intValue()>0) maximum=duration.intValue()-1;
            else if (!"timeline".equals(snapshot.get("sourceType")) && snapshot.get("durationMs") instanceof Number duration && duration.intValue()>0) maximum=(duration.intValue()-1)/1000;
            if (!List.of("youtube","youtube_music").contains(provider) || id==null || !id.matches("[A-Za-z0-9_-]{11}") || seconds<0 || seconds>maximum)
                throw new IllegalArgumentException("YouTube 시작 시간을 영상 길이 안에서 지정해 주세요.");
            snapshot.put("timestamp",seconds); snapshot.put("sourceUrl","https://www.youtube.com/watch?v="+id+(seconds==0?"":"&t="+seconds+"s"));
        }
        db.update("UPDATE playlist_items SET track_json=? WHERE playlist_id=? AND id=?",json.writeValueAsString(snapshot),playlist,item);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("id",item); result.put("position",rows.get(0).get("position")); result.put("trackId",null); result.put("track",snapshot);
        return result;
    }

    public synchronized Map<String,Object> publication(String user, String playlist) {
        ownedPlaylist(user,playlist);
        List<String> ids = db.query("SELECT share_id FROM public_playlists WHERE playlist_id=?",(rs,row)->rs.getString(1),playlist);
        return ids.isEmpty()?Map.of("public",false,"shareId","","sharePath",""):Map.of("public",true,"shareId",ids.get(0),"sharePath","/share/"+ids.get(0));
    }

    public synchronized Map<String,Object> publish(String user, String playlist, boolean visible) {
        ownedPlaylist(user,playlist);
        return tx.execute(status -> {
            if (!visible) db.update("DELETE FROM public_playlists WHERE playlist_id=?",playlist);
            else {
                Map<String,Object> original = playlist(user,playlist);
                List<String> names = db.query("SELECT name FROM accounts WHERE user_id=?",(rs,row)->rs.getString(1),user);
                Map<String,Object> snapshot = new LinkedHashMap<>();
                snapshot.put("title",original.get("title")); snapshot.put("author",names.isEmpty()?"익명":names.get(0));
                List<Map<String,Object>> items = new ArrayList<>();
                for (Map<String,Object> item : itemList(original)) {
                    Object track = item.get("track");
                    if (track==null) track = catalogueSnapshot((String)item.get("trackId"));
                    items.add(Map.of("position",item.get("position"),"track",track));
                }
                snapshot.put("items",items); snapshot.put("size",items.size());
                List<String> ids = db.query("SELECT share_id FROM public_playlists WHERE playlist_id=?",(rs,row)->rs.getString(1),playlist);
                if (ids.isEmpty()) {
                    String shareId = UUID.randomUUID().toString().replace("-","");
                    db.update("INSERT INTO public_playlists (share_id,playlist_id,snapshot_json,published_at) VALUES (?,?,?,?)",shareId,playlist,json.writeValueAsString(snapshot),Instant.now().toString());
                } else db.update("UPDATE public_playlists SET snapshot_json=? WHERE playlist_id=?",json.writeValueAsString(snapshot),playlist);
            }
            return publication(user,playlist);
        });
    }

    public synchronized Map<String,Object> publicPlaylist(String shareId, String user) {
        if (shareId==null || !shareId.matches("[0-9a-f]{32}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공개 목록을 찾을 수 없어요.");
        List<Map<String,Object>> rows = db.query("SELECT snapshot_json,published_at FROM public_playlists WHERE share_id=?",(rs,row)->Map.of("snapshot",rs.getString(1),"at",rs.getString(2)),shareId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공개 목록을 찾을 수 없어요.");
        Map<String,Object> result = readMap((String)rows.get(0).get("snapshot")); expirePublic(result);
        result.put("shareId",shareId); result.put("sharePath","/share/"+shareId); result.put("publishedAt",rows.get(0).get("at"));
        result.put("favourites",favouriteCount(shareId));
        result.put("favourited",user!=null && db.queryForObject("SELECT COUNT(*) FROM playlist_favourites WHERE user_id=? AND share_id=?",Integer.class,user,shareId)>0);
        return result;
    }

    // ponytail: read the prototype's public snapshots to search; indexed summaries when the public library grows.
    public synchronized List<Map<String,Object>> publicPlaylists(String user, String query, String sort, boolean onlyFavourites) {
        if (query==null || query.length()>100 || !List.of("popular","recent").contains(sort)) throw new IllegalArgumentException("검색어나 정렬 방식을 확인해 주세요.");
        if (onlyFavourites && user==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"앱을 다시 연결해 주세요.");
        List<Map<String,Object>> result = new ArrayList<>();
        for (String id : db.query("SELECT share_id FROM public_playlists",(rs,row)->rs.getString(1))) {
            Map<String,Object> detail = publicPlaylist(id,user);
            if ((!onlyFavourites || Boolean.TRUE.equals(detail.get("favourited"))) && Track.matches(detail.get("title")+" "+detail.get("author"),query)) {
                detail.remove("items"); result.add(detail);
            }
        }
        Comparator<Map<String,Object>> newest = Comparator.comparing((Map<String,Object> value)->Instant.parse((String)value.get("publishedAt"))).reversed().thenComparing(value->(String)value.get("shareId"));
        result.sort(sort.equals("popular")?Comparator.comparingInt((Map<String,Object> value)->(Integer)value.get("favourites")).reversed().thenComparing(newest):newest);
        return result.stream().limit(100).toList();
    }

    public synchronized Map<String,Object> favourite(String user, String shareId, boolean favourite) {
        publicPlaylist(shareId,user);
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM playlist_favourites WHERE user_id=? AND share_id=?",user,shareId);
            if (favourite) db.update("INSERT INTO playlist_favourites (user_id,share_id,created_at) VALUES (?,?,?)",user,shareId,Instant.now().toString());
        });
        return Map.of("favourite",favourite,"favourites",favouriteCount(shareId));
    }

    private int favouriteCount(String shareId) { return db.queryForObject("SELECT COUNT(*) FROM playlist_favourites WHERE share_id=?",Integer.class,shareId); }

    public synchronized Map<String,Object> copyPublic(String user, String shareId, String title, String clientId) {
        Map<String,Object> snapshot = publicPlaylist(shareId,user);
        String label = title==null?(String)snapshot.get("title"):title;
        if (label==null || label.strip().isEmpty() || label.strip().length()>80) throw new IllegalArgumentException("목록 이름을 1~80자로 입력해 주세요.");
        String name = label.strip();
        return tx.execute(status -> {
            boolean replay = clientId!=null && db.queryForObject("SELECT COUNT(*) FROM playlists WHERE id=?",Integer.class,clientId)>0;
            Map<String,Object> created = createPlaylist(user,name,clientId); String id = (String)created.get("id");
            if (replay) return created;
            List<Map<String,Object>> items = itemList(snapshot);
            for (int i=0;i<items.size();i++) {
                Map<String,Object> track = readMap(json.writeValueAsString(items.get(i).get("track")));
                String trackId = Boolean.TRUE.equals(track.get("external"))?null:(String)track.get("id");
                if (trackId!=null && !json.readValue(json.writeValueAsString(track),Track.class).usable()) throw new IllegalArgumentException("공개 목록의 곡 정보를 확인해 주세요.");
                if (trackId==null && !Boolean.TRUE.equals(track.get("external"))) throw new IllegalArgumentException("공개 목록의 곡 정보를 확인해 주세요.");
                String key = trackId==null?hash(shareId+":"+i):null;
                db.update("INSERT INTO playlist_items (id,playlist_id,track_id,position,track_json,external_key,external_source_key) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,trackId,i,json.writeValueAsString(track),key,trackId==null?hash("public:"+shareId):null);
            }
            Map<String,Object> result = new LinkedHashMap<>(created); result.put("size",items.size()); return result;
        });
    }

    private Track catalogueSnapshot(String id) {
        try (var input = new org.springframework.core.io.ClassPathResource("tracks.json").getInputStream()) {
            Track[] catalogue = json.readerFor(Track[].class).without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(input);
            return Arrays.stream(catalogue).filter(track->track.id.equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"곡 정보를 찾을 수 없어요."));
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> itemList(Map<String,Object> playlist) { return (List<Map<String,Object>>)playlist.get("items"); }

    @SuppressWarnings("unchecked")
    private static void expirePublic(Map<String,Object> snapshot) {
        for (Map<String,Object> item : itemList(snapshot)) {
            Object track = item.get("track");
            if (track instanceof Map<?,?> map) item.put("track",expireExternal((Map<String,Object>)map));
        }
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
        return reserveAi(user,Instant.now());
    }

    private record AiUsage(String user, Instant at) {}

    synchronized boolean reserveAi(String user, Instant now) {
        db.update("DELETE FROM ai_usage WHERE used_at < ?", now.minus(2, ChronoUnit.DAYS).toString().substring(0,10));
        Instant day=now.truncatedTo(ChronoUnit.DAYS), hour=now.minus(1,ChronoUnit.HOURS);
        int daily=0, hourly=0;
        // ponytail: parse at most three days of capped usage; timestamp columns/SQL aggregates if limits grow.
        for(AiUsage usage:db.query("SELECT user_id,used_at FROM ai_usage",(rs,row)->new AiUsage(rs.getString(1),Instant.parse(rs.getString(2))))) {
            if(!usage.at().isBefore(day))daily++;
            if(usage.user().equals(user)&&!usage.at().isBefore(hour))hourly++;
        }
        if (daily >= dailyLimit || hourly >= hourlyLimit) return false;
        db.update("INSERT INTO ai_usage VALUES (?,?,?)", UUID.randomUUID().toString(), user, now.toString());
        return true;
    }
}
