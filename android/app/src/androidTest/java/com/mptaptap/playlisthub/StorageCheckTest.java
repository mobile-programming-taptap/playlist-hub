package com.mptaptap.playlisthub;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Recommender;
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
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);
            String base="http://127.0.0.1:"+server.getLocalPort();
            android.content.SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
            credentials.edit().putString(base,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit();
            android.content.SharedPreferences policy=context.getSharedPreferences("settings",Context.MODE_PRIVATE);String previousConsent=policy.getString("serviceConsent:"+base,null);ApiClient.acceptConsent(context,base);
            String fresh="BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
            java.util.concurrent.Future<?> replies=worker.submit(()->{
                try{
                    String[] paths={"/me/feedback","/health","/sessions","/playlists/already-gone"};
                    String[] codes={"401 Unauthorized","200 OK","201 Created","404 Not Found"};
                    for(int i=0;i<paths.length;i++)try(java.net.Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                        String request=input.readLine();
                        assertTrue(request.contains(paths[i]));
                        boolean authenticated=false;String line;
                        while(!(line=input.readLine()).isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:"))authenticated=line.endsWith("Bearer "+fresh);
                        if(i==3){assertTrue(request.startsWith("DELETE "));assertTrue("A 404 request must keep the restored session",authenticated);}
                        String body=i==0?"{\"message\":\"앱을 다시 연결해 주세요.\"}":i==1?"{\"status\":\"ok\"}":i==2?"{\"token\":\""+fresh+"\"}":"{\"message\":\"목록을 찾을 수 없어요.\"}";
                        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        String header="HTTP/1.1 "+codes[i]+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                    }
                }catch(Exception error){throw new RuntimeException(error);}
            });
            try{
                try{new ApiClient(context,base).object("GET","/me/feedback",null);fail("Invalid session accepted");}
                catch(ApiClient.ApiError expected){assertEquals(401,expected.statusCode);assertEquals("앱을 다시 연결해 주세요.",expected.getMessage());}
                assertFalse(credentials.contains(base));
                ApiClient restored=new ApiClient(context,base);restored.connect();
                try{restored.request("DELETE","/playlists/already-gone",null);fail("A missing playlist must preserve its HTTP status");}
                catch(ApiClient.ApiError expected){assertEquals(404,expected.statusCode);assertEquals("목록을 찾을 수 없어요.",expected.getMessage());}
                assertEquals(fresh,credentials.getString(base,""));
                replies.get(10,java.util.concurrent.TimeUnit.SECONDS);
            }finally{credentials.edit().remove(base).commit();if(previousConsent==null)policy.edit().remove("serviceConsent:"+base).commit();else policy.edit().putString("serviceConsent:"+base,previousConsent).commit();}
        }finally{worker.shutdownNow();}
    }

    @Test public void duplicateSaveReactionUndoAndRestart() throws Exception {
        Recommender.Conditions conditions=Recommender.parse("영어 잔잔한 팝 음악, 신나는 곡은 빼고");
        JSONObject encoded=TrackJson.conditions(conditions);
        Recommender.Conditions restored=TrackJson.conditions(new JSONObject(encoded.toString()));
        assertEquals(conditions.tags,restored.tags);assertEquals(conditions.excludedTags,restored.excludedTags);assertEquals("en",restored.language);
        for(String field:new String[]{"tags","excludedTags"}) {
            JSONObject invalid=new JSONObject(encoded.toString()).put(field,new JSONArray().put("unsupported-tag"));
            try{TrackJson.conditions(invalid);fail("Unknown condition tags must be rejected");}catch(IllegalArgumentException expected){}
        }
        try{TrackJson.conditions(new JSONObject(encoded.toString()).put("tags","calm"));fail("Malformed tag arrays must be rejected");}catch(org.json.JSONException expected){}
        assertEquals("fr",TrackJson.conditions(new JSONObject(encoded.toString()).put("language","fr")).language);
        assertEquals("genre:afrobeat",TrackJson.conditions(new JSONObject(encoded.toString()).put("tags",new JSONArray().put("genre:afrobeat"))).tags.get(0));
        try{TrackJson.conditions(new JSONObject(encoded.toString()).put("language","french$$"));fail("Malformed condition language codes must be rejected");}catch(IllegalArgumentException expected){}
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
            store.saveAll(list,java.util.Arrays.asList(track,second));store.saveAll(list,java.util.Arrays.asList(track,second));
            assertEquals(2,store.playlist(list).getJSONArray("items").length());
            JSONArray source=new JSONArray(ApiClient.read(context.getAssets().open("tracks.json")));
            Track third=TrackJson.read(source.getJSONObject(2)),unapproved=TrackJson.read(source.getJSONObject(3));unapproved.source="Spotify";
            try {store.saveAll(list,java.util.Arrays.asList(third,unapproved));fail("Invalid batch accepted");}catch(IllegalArgumentException expected){}
            assertEquals(2,store.playlist(list).getJSONArray("items").length());assertFalse(store.saved(third.id));
            try {store.saveAll(list,java.util.Collections.emptyList());fail("Empty batch accepted");}catch(IllegalArgumentException expected){}
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
