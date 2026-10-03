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
    @Test public void invalidServerSessionCanReconnect() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,3,java.net.InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);
            String base="http://127.0.0.1:"+server.getLocalPort();
            android.content.SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
            credentials.edit().putString(base,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit();
            String fresh="BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
            java.util.concurrent.Future<?> replies=worker.submit(()->{
                try{
                    for(int i=0;i<3;i++)try(java.net.Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                        String request=input.readLine();
                        assertTrue(request.contains(i==0?"/me/feedback":i==1?"/health":"/sessions"));
                        while(!input.readLine().isEmpty()){}
                        String body=i==0?"{\"message\":\"앱을 다시 연결해 주세요.\"}":i==1?"{\"status\":\"ok\"}":"{\"token\":\""+fresh+"\"}";
                        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        String header="HTTP/1.1 "+(i==0?"401 Unauthorized":i==1?"200 OK":"201 Created")+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                    }
                }catch(Exception error){throw new RuntimeException(error);}
            });
            try{
                try{new ApiClient(context,base).object("GET","/me/feedback",null);fail("Invalid session accepted");}
                catch(IllegalStateException expected){assertEquals("앱을 다시 연결해 주세요.",expected.getMessage());}
                assertFalse(credentials.contains(base));
                new ApiClient(context,base).connect();
                assertEquals(fresh,credentials.getString(base,""));
                replies.get(10,java.util.concurrent.TimeUnit.SECONDS);
            }finally{credentials.edit().remove(base).commit();}
        }finally{worker.shutdownNow();}
    }

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
            Track second=TrackJson.read(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json"))).getJSONObject(1));
            store.save(list,track);store.save(list,second);
            JSONArray before=store.playlist(list).getJSONArray("items");
            String a=before.getJSONObject(0).getString("id"),b=before.getJSONObject(1).getString("id");
            try {store.reorder(list,java.util.Arrays.asList(a,a));fail("Duplicate order accepted");}catch(IllegalArgumentException expected){}
            try {store.reorder(list,java.util.Arrays.asList(a,"foreign"));fail("Foreign item accepted");}catch(IllegalArgumentException expected){}
            assertEquals(a,store.playlist(list).getJSONArray("items").getJSONObject(0).getString("id"));
            store.reorder(list,java.util.Arrays.asList(b,a));store.rename(list,"저장 순서 검사");
            store.close();store=new LocalStore(context,name);
            assertEquals("저장 순서 검사",store.playlist(list).getString("title"));
            assertEquals(b,store.playlist(list).getJSONArray("items").getJSONObject(0).getString("id"));
            assertEquals(track.sourceUrl,store.playlist(list).getJSONArray("items").getJSONObject(1).getJSONObject("track").getString("sourceUrl"));
            store.delete(list);assertEquals(0,store.playlists().length());assertFalse(store.saved(track.id));
            try(android.database.Cursor rows=store.getReadableDatabase().rawQuery("SELECT id FROM items",null)){assertEquals(0,rows.getCount());}
            list=store.create("권한 검사").getString("id");
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
