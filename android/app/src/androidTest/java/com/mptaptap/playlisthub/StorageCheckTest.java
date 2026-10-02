package com.mptaptap.playlisthub;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Track;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StorageCheckTest {
    @Test public void duplicateSaveReactionUndoAndRestart() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="storage-check.db";context.deleteDatabase(name);
        Track track=TrackJson.read(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json"))).getJSONObject(0));
        LocalStore store=new LocalStore(context,name);
        try {
            String list=store.create("비 오는 날").getString("id");
            store.save(list,track);store.save(list,track);
            assertEquals(1,store.playlist(list).getJSONArray("items").length());
            store.react(track.id,1);store.react(track.id,-1);
            assertEquals(Integer.valueOf(-1),store.feedback().get(track.id));
            assertEquals(1,store.feedback().size());
            store.close();store=new LocalStore(context,name);
            assertEquals(Integer.valueOf(-1),store.feedback().get(track.id));
            JSONObject item=store.playlist(list).getJSONArray("items").getJSONObject(0);
            assertEquals(track.id,item.getJSONObject("track").getString("id"));
            assertEquals(track.sourceUrl,item.getJSONObject("track").getString("sourceUrl"));
            assertTrue(store.saved(track.id));
            store.react(track.id,0);assertTrue(store.feedback().isEmpty());
            store.remove(list,item.getString("id"));assertEquals(0,store.playlist(list).getJSONArray("items").length());
            assertFalse(store.saved(track.id));
            try {store.create(" ");fail("Empty title accepted");}catch(IllegalArgumentException expected){}
            track.source="Spotify";
            try {store.save(list,track);fail("Unapproved source accepted");}catch(IllegalArgumentException expected){}
            assertEquals("https://example.com",ApiClient.validate("https://example.com/"));
            assertEquals("http://10.0.2.2:8080",ApiClient.validate("http://10.0.2.2:8080"));
            assertEquals("http://172.16.1.2:8080",ApiClient.validate("http://172.16.1.2:8080"));
            try{ApiClient.validate("https://example.com@evil.com/path");fail("Embedded credentials accepted");}catch(IllegalArgumentException expected){}
            try{ApiClient.validate("http://public.example.com");fail("Public cleartext accepted");}catch(IllegalArgumentException expected){}
            try{ApiClient.validate("http://10.evil.example");fail("Hostname disguised as private IP accepted");}catch(IllegalArgumentException expected){}
            try{ApiClient.validate("http://192.168.0.999");fail("Invalid private IP accepted");}catch(IllegalArgumentException expected){}
        }finally{store.close();context.deleteDatabase(name);}
    }
}
