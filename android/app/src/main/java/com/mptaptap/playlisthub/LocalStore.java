package com.mptaptap.playlisthub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.mptaptap.core.Track;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LocalStore extends SQLiteOpenHelper {
    LocalStore(Context context) { this(context, "taptap.db"); }
    LocalStore(Context context, String name) { super(context, name, null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE feedback(track_id TEXT PRIMARY KEY,reaction INTEGER NOT NULL CHECK(reaction IN(-1,1)))");
        db.execSQL("CREATE TABLE playlists(id TEXT PRIMARY KEY,title TEXT NOT NULL,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,track_id TEXT NOT NULL,track TEXT NOT NULL,position INTEGER NOT NULL,UNIQUE(playlist_id,track_id))");
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported database upgrade"); }
    Map<String, Integer> feedback() {
        Map<String, Integer> result = new HashMap<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT track_id,reaction FROM feedback", null)) {
            while (rows.moveToNext()) result.put(rows.getString(0), rows.getInt(1));
        }
        return result;
    }
    void react(String track, int reaction) {
        SQLiteDatabase db = getWritableDatabase();
        if (reaction == 0) { db.delete("feedback", "track_id=?", new String[]{track}); return; }
        ContentValues values = new ContentValues(); values.put("track_id", track); values.put("reaction", reaction);
        if (db.insertWithOnConflict("feedback", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IllegalStateException("반응을 저장하지 못했어요.");
    }
    void clearFeedback() { getWritableDatabase().delete("feedback", null, null); }
    JSONArray playlists() throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT p.id,p.title,COUNT(i.id) FROM playlists p LEFT JOIN items i ON p.id=i.playlist_id GROUP BY p.id ORDER BY p.created_at DESC,p.id", null)) {
            while (rows.moveToNext()) result.put(new JSONObject().put("id", rows.getString(0)).put("title", rows.getString(1)).put("size", rows.getInt(2)));
        }
        return result;
    }
    JSONObject create(String title) throws JSONException {
        title = title(title);
        if (playlists().length() >= 100) throw new IllegalArgumentException("목록은 최대 100개까지 만들 수 있어요.");
        String id = UUID.randomUUID().toString();
        ContentValues values = new ContentValues(); values.put("id", id); values.put("title", title.trim()); values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("playlists", null, values);
        return new JSONObject().put("id", id).put("title", title.trim()).put("size", 0);
    }
    JSONObject playlist(String id) throws JSONException {
        JSONObject result;
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT title FROM playlists WHERE id=?", new String[]{id})) {
            if (!rows.moveToFirst()) throw new IllegalArgumentException("목록을 찾을 수 없어요.");
            result = new JSONObject().put("id", id).put("title", rows.getString(0));
        }
        JSONArray items = new JSONArray();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id,track FROM items WHERE playlist_id=? ORDER BY position,id", new String[]{id})) {
            while (rows.moveToNext()) items.put(new JSONObject().put("id", rows.getString(0)).put("track", new JSONObject(rows.getString(1))));
        }
        return result.put("items", items);
    }
    void save(String playlist, Track track) throws JSONException {
        if (!track.usable()) throw new IllegalArgumentException("곡 출처를 확인할 수 없어요.");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            playlist(playlist);
            try (Cursor existing = db.rawQuery("SELECT id FROM items WHERE playlist_id=? AND track_id=?", new String[]{playlist, track.id})) {
                if (existing.moveToFirst()) { db.setTransactionSuccessful(); return; }
            }
            int position;
            try (Cursor count = db.rawQuery("SELECT COUNT(*),COALESCE(MAX(position),-1)+1 FROM items WHERE playlist_id=?", new String[]{playlist})) {
                count.moveToFirst();
                if (count.getInt(0) >= 500) throw new IllegalArgumentException("한 목록에는 500곡까지 저장할 수 있어요.");
                position = count.getInt(1);
            }
            ContentValues values = new ContentValues(); values.put("id", UUID.randomUUID().toString());
            values.put("playlist_id", playlist); values.put("track_id", track.id); values.put("track", TrackJson.write(track).toString()); values.put("position", position);
            db.insertOrThrow("items", null, values); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void remove(String playlist, String item) { getWritableDatabase().delete("items", "playlist_id=? AND id=?", new String[]{playlist, item}); }

    void rename(String id, String title) throws JSONException {
        playlist(id);
        ContentValues values = new ContentValues(); values.put("title", title(title));
        getWritableDatabase().update("playlists", values, "id=?", new String[]{id});
    }
    void delete(String id) throws JSONException { playlist(id); getWritableDatabase().delete("playlists", "id=?", new String[]{id}); }
    void reorder(String id, List<String> ids) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            JSONArray items = playlist(id).getJSONArray("items");
            HashSet<String> current = new HashSet<>();
            for (int i=0;i<items.length();i++) current.add(items.getJSONObject(i).getString("id"));
            if (ids == null || ids.size() > 500 || ids.size() != items.length() || !new HashSet<>(ids).equals(current))
                throw new IllegalArgumentException("목록의 모든 곡을 중복 없이 지정해 주세요. 목록이 바뀌었다면 다시 열어주세요.");
            for (int i=0;i<ids.size();i++) {
                ContentValues values = new ContentValues(); values.put("position", i);
                db.update("items", values, "playlist_id=? AND id=?", new String[]{id, ids.get(i)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static String title(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 80) throw new IllegalArgumentException("목록 이름을 1~80자로 입력해 주세요.");
        return value.trim();
    }
    boolean saved(String track) {
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id FROM items WHERE track_id=? LIMIT 1", new String[]{track})) { return rows.moveToFirst(); }
    }
}
