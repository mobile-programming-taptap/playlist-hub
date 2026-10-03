package com.mptaptap.playlisthub;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityOptionsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.mptaptap.core.Recommender;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ErrorRecoveryCheckTest {
    @Test public void pendingServerFeedbackAndSaveSurviveRecreationWithoutDuplicateWrites() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);Map<String,Integer> previousFeedback=data.feedback();
        String catalogue=ApiClient.read(context.getAssets().open("tracks.json"));
        String prompt="공부할 때 집중할 수 있는 가사 없는 피아노 음악";
        Recommender.Result result=Recommender.recommend(TrackJson.list(new JSONArray(catalogue)),Recommender.parse(prompt),Collections.emptyMap(),"openai");
        assertEquals(8,result.tracks.size());
        JSONArray picks=new JSONArray();
        for(Recommender.Pick pick:result.tracks)picks.put(new JSONObject().put("track",TrackJson.write(pick.track)).put("reason",pick.reason));
        String recommendation=new JSONObject().put("tracks",picks).put("mode",result.mode).put("notice",result.notice)
            .put("conditions",TrackJson.conditions(result.conditions)).toString();
        String first=result.tracks.get(0).track.id,second=result.tracks.get(1).track.id;
        String third=result.tracks.get(2).track.id,fourth=result.tracks.get(3).track.id;
        String playlist="22222222-2222-4222-8222-222222222222",title="회전 중 저장 검사";
        String createdId="44444444-4444-4444-8444-444444444444",createdTitle="회전 중 만든 목록";
        String retryId="55555555-5555-4555-8555-555555555555",retryTitle="첫 곡 저장 재시도";
        CountDownLatch feedbackReceived=new CountDownLatch(1),feedbackRelease=new CountDownLatch(1);
        CountDownLatch failedFeedbackReceived=new CountDownLatch(1),failedFeedbackRelease=new CountDownLatch(1);
        CountDownLatch saveReceived=new CountDownLatch(1),saveRelease=new CountDownLatch(1);
        CountDownLatch createReceived=new CountDownLatch(1),createRelease=new CountDownLatch(1);
        AtomicBoolean running=new AtomicBoolean(true),feedbackDone=new AtomicBoolean(),saveDone=new AtomicBoolean();
        AtomicBoolean created=new AtomicBoolean(),createdSaved=new AtomicBoolean(),retryCreated=new AtomicBoolean(),retrySaved=new AtomicBoolean();
        AtomicInteger feedbackWrites=new AtomicInteger(),batchWrites=new AtomicInteger();
        AtomicInteger createWrites=new AtomicInteger(),createdBatchWrites=new AtomicInteger(),retryCreates=new AtomicInteger(),retryBatchWrites=new AtomicInteger();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        ExecutorService serverWorkers=Executors.newCachedThreadPool();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);
            String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null);
            String token="FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";
            Future<?> accepting=serverWorkers.submit(()->{
                while(running.get())try {
                    Socket socket=server.accept();
                    serverWorkers.submit(()->{
                        try(Socket connection=socket) {
                            connection.setSoTimeout(5000);
                            DataInputStream input=new DataInputStream(connection.getInputStream());
                            String[] request=httpLine(input).split(" ");
                            String header;int length=0;boolean authenticated=false;
                            while(!(header=httpLine(input)).isEmpty()) {
                                String lower=header.toLowerCase(java.util.Locale.ROOT);
                                if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                                if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                            }
                            assertTrue("The test must reuse its seeded session",authenticated);assertTrue(length>=0&&length<=4096);
                            byte[] body=new byte[length];input.readFully(body);
                            String content,status="200 OK";
                            if(request[1].equals("/me/feedback/"+first)) {
                                assertEquals("PUT",request[0]);int write=feedbackWrites.incrementAndGet();
                                assertEquals(write==1?1:-1,new JSONObject(new String(body,StandardCharsets.UTF_8)).getInt("reaction"));
                                if(write==1) {
                                    feedbackReceived.countDown();assertTrue(feedbackRelease.await(15,TimeUnit.SECONDS));
                                    feedbackDone.set(true);content="{\"reaction\":1}";
                                } else {
                                    assertEquals(2,write);failedFeedbackReceived.countDown();assertTrue(failedFeedbackRelease.await(15,TimeUnit.SECONDS));
                                    status="500 Internal Server Error";content="{\"message\":\"회전 중 반응 검사 오류\"}";
                                }
                            } else if(request[1].equals("/playlists/"+playlist+"/items/batch")) {
                                assertEquals("POST",request[0]);assertEquals(1,batchWrites.incrementAndGet());
                                JSONArray ids=new JSONObject(new String(body,StandardCharsets.UTF_8)).getJSONArray("trackIds");
                                assertEquals(1,ids.length());assertEquals(second,ids.getString(0));
                                saveReceived.countDown();assertTrue(saveRelease.await(15,TimeUnit.SECONDS));
                                saveDone.set(true);content=new JSONArray().put(new JSONObject().put("id","33333333-3333-4333-8333-333333333333")
                                    .put("trackId",second).put("position",0)).toString();
                            } else if(request[0].equals("POST")&&request[1].equals("/playlists")) {
                                String name=new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("title");
                                String id;
                                if(name.equals(createdTitle)) {
                                    assertEquals(1,createWrites.incrementAndGet());createReceived.countDown();
                                    assertTrue(createRelease.await(15,TimeUnit.SECONDS));created.set(true);id=createdId;
                                } else {
                                    assertEquals(retryTitle,name);assertEquals(1,retryCreates.incrementAndGet());retryCreated.set(true);id=retryId;
                                }
                                status="201 Created";content=new JSONObject().put("id",id).put("title",name).put("size",0)
                                    .put("createdAt","2026-10-03T10:00:00Z").toString();
                            } else if(request[1].equals("/playlists/"+createdId+"/items/batch")||request[1].equals("/playlists/"+retryId+"/items/batch")) {
                                assertEquals("POST",request[0]);
                                JSONArray ids=new JSONObject(new String(body,StandardCharsets.UTF_8)).getJSONArray("trackIds");assertEquals(1,ids.length());
                                boolean retry=request[1].contains(retryId);assertEquals(retry?fourth:third,ids.getString(0));
                                if(retry&&retryBatchWrites.incrementAndGet()==1) {
                                    status="500 Internal Server Error";content="{\"message\":\"첫 곡 저장 검사 오류\"}";
                                } else {
                                    if(retry){assertEquals(2,retryBatchWrites.get());retrySaved.set(true);}
                                    else{assertEquals(1,createdBatchWrites.incrementAndGet());createdSaved.set(true);}
                                    content=new JSONArray().put(new JSONObject().put("id","66666666-6666-4666-8666-666666666666")
                                        .put("trackId",retry?fourth:third).put("position",0)).toString();
                                }
                            } else if(request[1].equals("/recommendations")) {
                                assertEquals("POST",request[0]);
                                assertEquals(prompt,new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("prompt"));
                                content=recommendation;
                            } else {
                                assertEquals("GET",request[0]);
                                switch(request[1]) {
                                    case "/health": content="{\"status\":\"ok\"}";break;
                                    case "/catalogue": content=catalogue;break;
                                    case "/me/feedback": content=feedbackDone.get()?new JSONObject().put(first,1).toString():"{}";break;
                                    case "/me/saved-tracks":
                                        JSONArray saved=new JSONArray();if(saveDone.get())saved.put(second);if(createdSaved.get())saved.put(third);if(retrySaved.get())saved.put(fourth);
                                        content=saved.toString();break;
                                    case "/playlists":
                                        JSONArray lists=new JSONArray().put(new JSONObject().put("id",playlist).put("title",title)
                                            .put("size",saveDone.get()?1:0).put("createdAt","2026-10-03T10:00:00Z"));
                                        if(created.get())lists.put(new JSONObject().put("id",createdId).put("title",createdTitle).put("size",createdSaved.get()?1:0).put("createdAt","2026-10-03T10:00:00Z"));
                                        if(retryCreated.get())lists.put(new JSONObject().put("id",retryId).put("title",retryTitle).put("size",retrySaved.get()?1:0).put("createdAt","2026-10-03T10:00:00Z"));
                                        content=lists.toString();break;
                                    default: throw new AssertionError("Unexpected test request: "+request[1]);
                                }
                            }
                            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                            String response="HTTP/1.1 "+status+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                            connection.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));connection.getOutputStream().write(bytes);connection.getOutputStream().flush();
                        } catch(Throwable error) { if(running.get())serverFailure.compareAndSet(null,error); }
                    });
                } catch(SocketTimeoutException timeout) {
                    // Held write sockets must not prevent independent reads.
                } catch(Exception error) { if(running.get())serverFailure.compareAndSet(null,error);return; }
            });
            try {
                assertTrue(credentials.edit().putString(base,token).commit());
                assertTrue(settings.edit().putString("server",base).remove("prompt").commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    try {
                        ready(app);onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                        onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                        onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                        onView(withId(R.id.like)).perform(click());
                        assertTrue("The first feedback write must reach the server",feedbackReceived.await(5,TimeUnit.SECONDS));
                        app.recreate();
                        pendingWriteVisible(app);
                        onView(withId(R.id.like)).check(matches(not(isEnabled())));
                        assertEquals(1,feedbackWrites.get());assertNull(serverFailure.get());
                        feedbackRelease.countDown();ready(app);
                        onView(withId(R.id.like)).check(matches(withText("좋아요 취소")));
                        onView(withId(R.id.next)).perform(click());ready(app);
                        org.hamcrest.Matcher<View> save=allOf(withId(R.id.save),isDescendantOfA(allOf(isAssignableFrom(android.widget.ScrollView.class),isDisplayed())));
                        onView(save).perform(scrollTo(),click());ready(app);
                        onView(withText(title+" · 0곡")).inRoot(isDialog()).perform(click());
                        assertTrue("The batch save must reach the server",saveReceived.await(5,TimeUnit.SECONDS));
                        app.recreate();
                        pendingWriteVisible(app);
                        onView(save).check(matches(not(isEnabled())));
                        assertEquals(1,batchWrites.get());assertNull(serverFailure.get());
                        saveRelease.countDown();ready(app);
                        onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                        onView(save).check(matches(withText("저장됨 · 추가")));
                        onView(withId(R.id.next)).perform(click());ready(app);
                        onView(save).perform(scrollTo(),click());ready(app);
                        onView(withText("+ 새 플레이리스트")).inRoot(isDialog()).perform(click());
                        onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(createdTitle),closeSoftKeyboard());
                        onView(withText("만들기")).inRoot(isDialog()).perform(click());
                        assertTrue("The playlist creation must reach the server",createReceived.await(5,TimeUnit.SECONDS));
                        app.recreate();pendingWriteVisible(app);onView(save).check(matches(not(isEnabled())));
                        assertEquals(1,createWrites.get());assertEquals(0,createdBatchWrites.get());assertNull(serverFailure.get());
                        createRelease.countDown();ready(app);
                        onView(withId(R.id.counter)).check(matches(withText("3 / 8")));
                        onView(save).check(matches(withText("저장됨 · 추가")));
                        assertEquals(1,createWrites.get());assertEquals(1,createdBatchWrites.get());
                        onView(withId(R.id.next)).perform(click());ready(app);
                        onView(save).perform(scrollTo(),click());ready(app);
                        onView(withText("+ 새 플레이리스트")).inRoot(isDialog()).perform(click());
                        onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(retryTitle),closeSoftKeyboard());
                        onView(withText("만들기")).inRoot(isDialog()).perform(click());ready(app);
                        onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).check((view,error)->{
                            if(error!=null)throw error;
                            android.widget.EditText name=(android.widget.EditText)view;
                            assertEquals(retryTitle,name.getText().toString());assertEquals("첫 곡 저장 검사 오류",name.getError());assertFalse(name.isEnabled());
                        });
                        assertEquals(1,retryCreates.get());assertEquals(1,retryBatchWrites.get());
                        onView(withText("저장 다시 시도")).inRoot(isDialog()).check(matches(isEnabled())).perform(click());ready(app);
                        onView(withId(R.id.counter)).check(matches(withText("4 / 8")));
                        onView(save).check(matches(withText("저장됨 · 추가")));
                        assertEquals(1,retryCreates.get());assertEquals(2,retryBatchWrites.get());
                        for(int i=0;i<3;i++){onView(withId(R.id.previous)).perform(click());ready(app);}
                        onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                        onView(withId(R.id.like)).check(matches(withText("좋아요 취소")));
                        onView(withId(R.id.dislike)).perform(click());
                        assertTrue("The failing feedback write must reach the server",failedFeedbackReceived.await(5,TimeUnit.SECONDS));
                        app.recreate();pendingWriteVisible(app);onView(withId(R.id.dislike)).check(matches(not(isEnabled())));
                        assertEquals(2,feedbackWrites.get());failedFeedbackRelease.countDown();ready(app);
                        onView(withId(R.id.like)).check(matches(withText("좋아요 취소")));
                        onView(withText("회전 중 반응 검사 오류")).check(matches(isDisplayed()));
                        assertEquals(2,feedbackWrites.get());assertEquals(1,batchWrites.get());
                        assertNull(serverFailure.get());assertEquals(previousFeedback,data.feedback());
                    } finally {feedbackRelease.countDown();failedFeedbackRelease.countDown();saveRelease.countDown();createRelease.countDown();}
                }
            } finally {
                running.set(false);feedbackRelease.countDown();failedFeedbackRelease.countDown();saveRelease.countDown();createRelease.countDown();server.close();
                if(!data.feedback().equals(previousFeedback)) {
                    data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                }
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());
                SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());accepting.get(2,TimeUnit.SECONDS);
            }
        } finally {running.set(false);feedbackRelease.countDown();failedFeedbackRelease.countDown();saveRelease.countDown();createRelease.countDown();serverWorkers.shutdownNow();data.close();}
    }

    @Test public void playlistCreateRetryKeepsRequestIdAfterLostResponse() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);Map<String,Integer> previousFeedback=data.feedback();
        String catalogue=ApiClient.read(context.getAssets().open("tracks.json"));
        String prompt="공부할 때 집중할 수 있는 가사 없는 피아노 음악",title="응답 유실 후 같은 목록";
        Recommender.Result result=Recommender.recommend(TrackJson.list(new JSONArray(catalogue)),Recommender.parse(prompt),Collections.emptyMap(),"openai");
        assertEquals(8,result.tracks.size());JSONArray picks=new JSONArray();
        for(Recommender.Pick pick:result.tracks)picks.put(new JSONObject().put("track",TrackJson.write(pick.track)).put("reason",pick.reason));
        String recommendation=new JSONObject().put("tracks",picks).put("mode",result.mode).put("notice",result.notice)
            .put("conditions",TrackJson.conditions(result.conditions)).toString();
        String trackId=result.tracks.get(0).track.id;
        Map<String,JSONObject> rows=new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger creates=new AtomicInteger(),saves=new AtomicInteger();
        AtomicReference<String> firstRequestId=new AtomicReference<>(),secondRequestId=new AtomicReference<>();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>();AtomicBoolean running=new AtomicBoolean(true);
        ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null),token="GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG";
            Future<?> replies=serverWorker.submit(()->{
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());
                    String[] request=httpLine(input).split(" ");String header;int length=0;boolean authenticated=false;
                    while(!(header=httpLine(input)).isEmpty()) {
                        String lower=header.toLowerCase(java.util.Locale.ROOT);
                        if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                    }
                    assertTrue("The test must reuse its seeded session",authenticated);assertTrue(length>=0&&length<=4096);
                    byte[] body=new byte[length];input.readFully(body);String content,status="200 OK";boolean loseBody=false;
                    if(request[0].equals("POST")&&request[1].equals("/playlists")) {
                        JSONObject submitted=new JSONObject(new String(body,StandardCharsets.UTF_8));assertEquals(title,submitted.getString("title"));
                        int attempt=creates.incrementAndGet();assertTrue(attempt<=2);String requestedId=submitted.optString("id","");
                        if(attempt==1)firstRequestId.set(requestedId);else secondRequestId.set(requestedId);
                        String id=requestedId.isEmpty()?java.util.UUID.randomUUID().toString():java.util.UUID.fromString(requestedId).toString();
                        JSONObject created=rows.get(id);
                        if(created==null) {
                            created=new JSONObject().put("id",id).put("title",title).put("size",0).put("createdAt","2026-10-03T10:00:00Z");rows.put(id,created);
                        }
                        status="201 Created";content=created.toString();loseBody=attempt==1;
                    } else if(request[1].startsWith("/playlists/")&&request[1].endsWith("/items/batch")) {
                        assertEquals("POST",request[0]);assertEquals(1,saves.incrementAndGet());
                        String id=request[1].substring("/playlists/".length(),request[1].length()-"/items/batch".length());
                        assertTrue(rows.containsKey(id));JSONArray ids=new JSONObject(new String(body,StandardCharsets.UTF_8)).getJSONArray("trackIds");
                        assertEquals(1,ids.length());assertEquals(trackId,ids.getString(0));rows.get(id).put("size",1);
                        content=new JSONArray().put(new JSONObject().put("id","77777777-7777-4777-8777-777777777777").put("trackId",trackId).put("position",0)).toString();
                    } else if(request[1].equals("/recommendations")) {
                        assertEquals("POST",request[0]);assertEquals(prompt,new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("prompt"));content=recommendation;
                    } else {
                        assertEquals("GET",request[0]);
                        switch(request[1]) {
                            case "/health": content="{\"status\":\"ok\"}";break;
                            case "/catalogue": content=catalogue;break;
                            case "/me/feedback": content="{}";break;
                            case "/me/saved-tracks": content=saves.get()==0?"[]":new JSONArray().put(trackId).toString();break;
                            case "/playlists": content=new JSONArray(rows.values()).toString();break;
                            default: throw new AssertionError("Unexpected test request: "+request[1]);
                        }
                    }
                    byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                    String response="HTTP/1.1 "+status+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    // The row exists, but the first successful response body is lost in transit.
                    if(!loseBody)socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                } catch(SocketTimeoutException timeout) {
                    // Allow the user-facing retry to happen after the response was lost.
                } catch(Throwable error) {if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                assertTrue(credentials.edit().putString(base,token).commit());assertTrue(settings.edit().putString("server",base).remove("prompt").commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    ready(app);onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                    onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                    org.hamcrest.Matcher<View> save=allOf(withId(R.id.save),isDescendantOfA(allOf(isAssignableFrom(android.widget.ScrollView.class),isDisplayed())));
                    onView(save).perform(scrollTo(),click());ready(app);onView(withText("+ 새 플레이리스트")).inRoot(isDialog()).perform(click());
                    onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(title),closeSoftKeyboard());
                    onView(withText("만들기")).inRoot(isDialog()).perform(click());ready(app);
                    onView(withText("새 플레이리스트")).inRoot(isDialog()).check(matches(isDisplayed()));
                    AtomicReference<String> displayedError=new AtomicReference<>();
                    onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).check((view,error)->{
                        if(error!=null)throw error;android.widget.EditText name=(android.widget.EditText)view;
                        assertEquals(title,name.getText().toString());assertNotNull(name.getError());assertTrue(name.getError().length()>0);assertTrue(name.isEnabled());
                        displayedError.set(name.getError().toString());
                    });
                    onView(withText(displayedError.get())).inRoot(isDialog()).check(matches(isDisplayed()));
                    assertEquals(1,creates.get());assertEquals(1,rows.size());assertEquals(0,saves.get());assertNull(serverFailure.get());
                    onView(withText("만들기")).inRoot(isDialog()).check(matches(isEnabled())).perform(click());ready(app);
                    assertNull("Test server failure",serverFailure.get());
                    assertEquals(2,creates.get());assertEquals("Retry must reuse the same playlist row",1,rows.size());assertEquals(1,saves.get());
                    onView(save).check(matches(withText("저장됨 · 추가")));
                    assertNotNull(firstRequestId.get());assertFalse(firstRequestId.get().isEmpty());assertEquals(firstRequestId.get(),secondRequestId.get());
                    assertNull(serverFailure.get());assertEquals(previousFeedback,data.feedback());
                }
            } finally {
                running.set(false);server.close();
                if(!data.feedback().equals(previousFeedback)) {
                    data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                }
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());replies.get(2,TimeUnit.SECONDS);
            }
        } finally {running.set(false);serverWorker.shutdownNow();data.close();}
    }

    @Test public void failedServerMetadataKeepsLocalStoreAndRetryRestoresBundledCatalogue() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();LocalStore data=new LocalStore(context);Map<String,Integer> previousFeedback=data.feedback();
        String prompt="공부할 때 집중할 수 있는 가사 없는 피아노 음악";
        java.util.List<com.mptaptap.core.Track> bundled=TrackJson.list(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json"))));
        assertEquals(40,bundled.size());com.mptaptap.core.Track track=Recommender.recommend(bundled,Recommender.parse(prompt),Collections.emptyMap(),"basic").tracks.get(0).track;
        String catalogue=new JSONArray().put(TrackJson.write(track)).toString(),title="연결 실패에도 유지할 목록 "+java.util.UUID.randomUUID().toString().substring(0,8);
        String[] localId=new String[1];AtomicBoolean running=new AtomicBoolean(true),failMetadata=new AtomicBoolean(true);
        AtomicInteger savedReads=new AtomicInteger(),serverWrites=new AtomicInteger();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null),token="HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH";
            Future<?> replies=serverWorker.submit(()->{
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());
                    String[] request=httpLine(input).split(" ");String header;int length=0;boolean authenticated=false;
                    while(!(header=httpLine(input)).isEmpty()) {
                        String lower=header.toLowerCase(java.util.Locale.ROOT);
                        if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                    }
                    assertTrue("The test must reuse its seeded session",authenticated);assertTrue(length>=0&&length<=4096);
                    byte[] body=new byte[length];input.readFully(body);
                    if(!request[0].equals("GET")){serverWrites.incrementAndGet();throw new AssertionError("Unexpected server write: "+request[0]+" "+request[1]);}
                    String content,status="200 OK";
                    switch(request[1]) {
                        case "/health": content="{\"status\":\"ok\"}";break;
                        case "/catalogue": content=catalogue;break;
                        case "/me/feedback": content=new JSONObject().put(track.id,-1).toString();break;
                        case "/me/saved-tracks":
                            savedReads.incrementAndGet();
                            if(failMetadata.get()){status="500 Internal Server Error";content="{\"message\":\"저장 상태 검사 오류\"}";}else content="[]";
                            break;
                        case "/playlists": content="[]";break;
                        default: throw new AssertionError("Unexpected test request: "+request[1]);
                    }
                    byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                    String response="HTTP/1.1 "+status+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                } catch(SocketTimeoutException timeout) {
                    // The connection dialog stays open for a deliberate retry.
                } catch(Throwable error) {if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                data.clearFeedback();data.react(track.id,1);localId[0]=data.create(title).getString("id");data.save(localId[0],track);
                assertTrue(credentials.edit().putString(base,token).commit());assertTrue(settings.edit().remove("server").remove("prompt").commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    ready(app);onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());ready(app);
                    onView(withText(title+"\n1곡 · 앱 추천에서 저장")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withId(R.id.settings)).perform(click());
                    onView(withHint("https://서버주소")).inRoot(isDialog()).perform(scrollTo(),replaceText(base),closeSoftKeyboard());
                    onView(withText("서버에 연결")).inRoot(isDialog()).perform(scrollTo(),click());ready(app);
                    assertNull("Test server failure",serverFailure.get());assertEquals(1,savedReads.get());
                    assertEquals("A failed snapshot must preserve the active local store","",settings.getString("server",""));
                    assertEquals(Collections.singletonMap(track.id,1),data.feedback());
                    assertEquals(title,data.playlist(localId[0]).getString("title"));assertEquals(1,data.playlist(localId[0]).getJSONArray("items").length());
                    onView(withText("서버에 연결하지 못했어요. 주소와 서버 실행 상태를 확인해 주세요.")).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withHint("https://서버주소")).inRoot(isDialog()).check(matches(withText(base)));
                    onView(withText("곡 데이터와 이용 안내")).inRoot(isDialog()).perform(scrollTo(),click());
                    onView(withText(containsString("검증한 40곡"))).inRoot(isDialog()).check(matches(isDisplayed()));
                    androidx.test.espresso.Espresso.pressBack();
                    onView(withText("좋아요·싫어요 관리 (1)")).inRoot(isDialog()).perform(scrollTo(),click());
                    onView(withText("좋아요 · "+track.title+" — "+track.artist)).inRoot(isDialog()).check(matches(isDisplayed()));
                    androidx.test.espresso.Espresso.pressBack();
                    failMetadata.set(false);
                    onView(withText("서버에 연결")).inRoot(isDialog()).check(matches(isEnabled())).perform(scrollTo(),click());ready(app);
                    assertNull("Test server failure",serverFailure.get());assertTrue(savedReads.get()>=2);assertEquals(base,settings.getString("server",""));
                    onView(withId(R.id.mode_label)).check(matches(withText(startsWith("온라인 추천"))));
                    assertEquals(Collections.singletonMap(track.id,1),data.feedback());assertEquals(1,data.playlist(localId[0]).getJSONArray("items").length());
                    onView(withId(R.id.settings)).perform(click());
                    onView(withText("이 기기의 기본 추천 사용")).inRoot(isDialog()).perform(scrollTo(),click());ready(app);
                    assertEquals("",settings.getString("server",""));
                    onView(withText(title+"\n1곡 · 앱 추천에서 저장")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withId(R.id.settings)).perform(click());
                    onView(withText("곡 데이터와 이용 안내")).inRoot(isDialog()).perform(scrollTo(),click());
                    onView(withText(containsString("검증한 40곡"))).inRoot(isDialog()).check(matches(isDisplayed()));
                    androidx.test.espresso.Espresso.pressBack();androidx.test.espresso.Espresso.pressBack();
                    onView(withId(R.id.nav_recommend)).perform(click());ready(app);
                    onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                    onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                    onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                    onView(withId(R.id.mode_label)).check(matches(withText(startsWith("기본 추천"))));
                    Application application=(Application)context.getApplicationContext();AtomicBoolean changedStore=new AtomicBoolean();
                    Application.ActivityLifecycleCallbacks switchAfterSave=new Application.ActivityLifecycleCallbacks(){
                        @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){
                            if(activity instanceof MainActivity&&changedStore.compareAndSet(false,true))
                                assertTrue(settings.edit().putString("server",base).commit());
                        }
                        @Override public void onActivityCreated(Activity activity,Bundle state){}
                        @Override public void onActivityStarted(Activity activity){}
                        @Override public void onActivityResumed(Activity activity){}
                        @Override public void onActivityPaused(Activity activity){}
                        @Override public void onActivityStopped(Activity activity){}
                        @Override public void onActivityDestroyed(Activity activity){}
                    };
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->application.registerActivityLifecycleCallbacks(switchAfterSave));
                    failMetadata.set(true);
                    try{app.recreate();}finally{
                        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->application.unregisterActivityLifecycleCallbacks(switchAfterSave));
                    }
                    assertTrue("The active store must change after the old screen saves its state",changedStore.get());ready(app);
                    onView(withId(R.id.mode_label)).check(matches(withText(startsWith("온라인 추천"))));
                    onView(withId(R.id.results)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                    onView(withId(R.id.card_navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                    onView(withId(R.id.request_panel)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
                    app.onActivity(activity->assertEquals(0,((androidx.viewpager2.widget.ViewPager2)activity.findViewById(R.id.pager)).getAdapter().getItemCount()));
                    onView(withId(R.id.recommend)).check(matches(not(isEnabled())));onView(withId(R.id.settings)).check(matches(isEnabled()));
                    failMetadata.set(false);onView(withId(R.id.settings)).perform(click());
                    onView(withHint("https://서버주소")).inRoot(isDialog()).check(matches(withText(base)));
                    onView(withText("서버에 연결")).inRoot(isDialog()).perform(scrollTo(),click());ready(app);
                    onView(withId(R.id.mode_label)).check(matches(withText(startsWith("온라인 추천"))));
                    onView(withId(R.id.recommend)).check(matches(isEnabled()));
                    onView(withId(R.id.settings)).perform(click());onView(withText("곡 데이터와 이용 안내")).inRoot(isDialog()).perform(scrollTo(),click());
                    onView(withText(containsString("검증한 1곡"))).inRoot(isDialog()).check(matches(isDisplayed()));
                    androidx.test.espresso.Espresso.pressBack();androidx.test.espresso.Espresso.pressBack();
                    assertEquals(0,serverWrites.get());assertNull("Test server failure",serverFailure.get());
                    assertEquals(Collections.singletonMap(track.id,1),data.feedback());assertEquals(1,data.playlist(localId[0]).getJSONArray("items").length());
                }
            } finally {
                running.set(false);server.close();
                if(localId[0]!=null)try{data.delete(localId[0]);}catch(IllegalArgumentException alreadyRemoved){}
                data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());replies.get(2,TimeUnit.SECONDS);
            }
        } finally {running.set(false);serverWorker.shutdownNow();data.close();}
    }

    @Test @SdkSuppress(minSdkVersion=37)
    public void restoredDeniedResultCannotOverrideCurrentlyGrantedLocalPermission() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();LocalStore data=new LocalStore(context);Map<String,Integer> previousFeedback=data.feedback();
        com.mptaptap.core.Track track=TrackJson.list(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json")))).get(0);
        String catalogue=new JSONArray().put(TrackJson.write(track)).toString();
        AtomicBoolean running=new AtomicBoolean(true),replayed=new AtomicBoolean();
        AtomicInteger savedReads=new AtomicInteger(),serverWrites=new AtomicInteger();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        CountDownLatch metadataReceived=new CountDownLatch(1),metadataRelease=new CountDownLatch(1);
        ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null),token="IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII";
            Future<?> replies=serverWorker.submit(()->{
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());
                    String[] request=httpLine(input).split(" ");String header;int length=0;boolean authenticated=false;
                    while(!(header=httpLine(input)).isEmpty()) {
                        String lower=header.toLowerCase(java.util.Locale.ROOT);
                        if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                    }
                    assertTrue("The test must reuse its seeded session",authenticated);assertTrue(length>=0&&length<=4096);
                    byte[] body=new byte[length];input.readFully(body);
                    if(!request[0].equals("GET")){serverWrites.incrementAndGet();throw new AssertionError("Unexpected server write: "+request[0]+" "+request[1]);}
                    String content;
                    switch(request[1]) {
                        case "/health": content="{\"status\":\"ok\"}";break;
                        case "/catalogue": content=catalogue;break;
                        case "/me/feedback": content=new JSONObject().put(track.id,-1).toString();break;
                        case "/me/saved-tracks":
                            if(savedReads.incrementAndGet()==2){metadataReceived.countDown();assertTrue(metadataRelease.await(15,TimeUnit.SECONDS));}
                            content="[]";break;
                        default: throw new AssertionError("Unexpected test request: "+request[1]);
                    }
                    byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                    String response="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                } catch(SocketTimeoutException timeout) {
                    // The last metadata response waits while the historical permission result arrives.
                } catch(Throwable error) {if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                data.clearFeedback();data.react(track.id,1);
                assertTrue(credentials.edit().putString(base,token).commit());assertTrue(settings.edit().putString("server",base).remove("prompt").commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    ready(app);assertEquals(1,savedReads.get());AtomicReference<Bundle> historical=new AtomicReference<>();
                    app.onActivity(activity->{
                        Bundle registered=new Bundle();activity.getActivityResultRegistry().onSaveInstanceState(registered);
                        java.util.ArrayList<String> keys=registered.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS");
                        java.util.ArrayList<Integer> codes=registered.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS");
                        assertNotNull(keys);assertNotNull(codes);int index=keys.indexOf("activity_rq#0");
                        assertTrue("The local-permission launcher must be registered",index>=0);
                        ActivityResultRegistry stopped=new ActivityResultRegistry(){
                            @Override public <I,O> void onLaunch(int requestCode,ActivityResultContract<I,O> contract,I input,ActivityOptionsCompat options){
                                throw new AssertionError("A historical result must not launch a permission request");
                            }
                        };
                        registered.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",new java.util.ArrayList<>(Collections.singletonList(keys.get(index))));
                        stopped.onRestoreInstanceState(registered);
                        Intent denied=new Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
                            new String[]{android.Manifest.permission.ACCESS_LOCAL_NETWORK})
                            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                                new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
                        assertFalse(new ActivityResultContracts.RequestPermission().parseResult(Activity.RESULT_OK,denied));
                        // Raw results survive the Bundle round trip; the parsed Boolean overload does not.
                        assertTrue(stopped.dispatchResult(codes.get(index),Activity.RESULT_OK,denied));
                        Bundle saved=new Bundle();stopped.onSaveInstanceState(saved);
                        assertTrue(saved.getBundle("KEY_COMPONENT_ACTIVITY_PENDING_RESULT").containsKey(keys.get(index)));historical.set(saved);
                    });
                    ActivityLifecycleCallback replay=(activity,stage)->{
                        if(activity instanceof MainActivity&&stage==Stage.CREATED&&replayed.compareAndSet(false,true)) {
                            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                                activity.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK));
                            ((MainActivity)activity).getActivityResultRegistry().onRestoreInstanceState(historical.get());
                        }
                    };
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(replay));
                    try {
                        app.recreate();assertTrue("The historical result must be restored before START",replayed.get());
                        assertTrue("The recreated screen must be reading server metadata",metadataReceived.await(5,TimeUnit.SECONDS));
                        assertNull("Test server failure",serverFailure.get());
                        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK));
                        assertEquals("A historical denial must not remove a currently granted server",base,settings.getString("server",""));
                        onView(withText("서버에 연결하지 않았어요")).check(doesNotExist());
                        onView(withId(R.id.mode_label)).check(matches(withText(startsWith("온라인 추천"))));
                        onView(withId(R.id.progress)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
                        metadataRelease.countDown();ready(app);
                        assertEquals(base,settings.getString("server",""));onView(withId(R.id.mode_label)).check(matches(withText(startsWith("온라인 추천"))));
                        onView(withId(R.id.recommend)).check(matches(isEnabled()));onView(withText("서버에 연결하지 않았어요")).check(doesNotExist());
                        onView(withId(R.id.settings)).perform(click());onView(withText("곡 데이터와 이용 안내")).inRoot(isDialog()).perform(scrollTo(),click());
                        onView(withText(containsString("검증한 1곡"))).inRoot(isDialog()).check(matches(isDisplayed()));
                        androidx.test.espresso.Espresso.pressBack();androidx.test.espresso.Espresso.pressBack();
                        assertEquals(Collections.singletonMap(track.id,1),data.feedback());assertEquals(0,serverWrites.get());assertEquals(2,savedReads.get());assertNull("Test server failure",serverFailure.get());
                    } finally {
                        metadataRelease.countDown();
                        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(replay));
                    }
                }
            } finally {
                running.set(false);metadataRelease.countDown();server.close();
                data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());replies.get(2,TimeUnit.SECONDS);
            }
        } finally {running.set(false);metadataRelease.countDown();serverWorker.shutdownNow();data.close();}
    }

    @Test public void playlistNameFailuresKeepDraftAndAllowRetry() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);Map<String,Integer> previousFeedback=data.feedback();
        String catalogue=ApiClient.read(context.getAssets().open("tracks.json"));
        String id="11111111-1111-4111-8111-111111111111",title="다시 시도해 만든 목록",renamed="다시 시도해 바꾼 이름";
        JSONObject created=new JSONObject().put("id",id).put("title",title).put("size",0).put("createdAt","2026-10-03T10:00:00Z");
        JSONObject changed=new JSONObject().put("id",id).put("title",renamed).put("size",0).put("createdAt","2026-10-03T10:00:00Z");
        String createdList=new JSONArray().put(created).toString(),changedList=new JSONArray().put(changed).toString();
        String createdDetail=new JSONObject(created.toString()).put("items",new JSONArray()).toString();
        String changedDetail=new JSONObject(changed.toString()).put("items",new JSONArray()).toString();
        AtomicInteger creates=new AtomicInteger(),renames=new AtomicInteger();
        ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null);
            String token="EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE";
            Future<?> replies=serverWorker.submit(()->{
                String[] methods={"GET","GET","GET","GET","GET","POST","POST","GET","GET","PUT","PUT","GET","GET"};
                String[] paths={"/health","/catalogue","/me/feedback","/me/saved-tracks","/playlists","/playlists","/playlists","/playlists","/playlists/"+id,"/playlists/"+id,"/playlists/"+id,"/playlists/"+id,"/playlists"};
                try {
                    for(int i=0;i<paths.length;i++)try(Socket socket=server.accept()) {
                        socket.setSoTimeout(5000);
                        DataInputStream input=new DataInputStream(socket.getInputStream());
                        String request=httpLine(input);assertTrue(request,request.startsWith(methods[i]+" "+paths[i]+" HTTP/"));
                        String header;int length=0;boolean authenticated=false;
                        while(!(header=httpLine(input)).isEmpty()) {
                            String lower=header.toLowerCase(java.util.Locale.ROOT);
                            if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                            if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                        }
                        assertTrue("The test must reuse its seeded session",authenticated);assertTrue(length>=0&&length<=4096);
                        byte[] body=new byte[length];input.readFully(body);
                        if(i==5||i==6){assertEquals(title,new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("title"));creates.incrementAndGet();}
                        if(i==9||i==10){assertEquals(renamed,new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("title"));renames.incrementAndGet();}
                        String content;
                        switch(i) {
                            case 0: content="{\"status\":\"ok\"}";break;
                            case 1: content=catalogue;break;
                            case 2: content="{}";break;
                            case 5: content="{\"message\":\"생성 검사 오류\"}";break;
                            case 6: content=created.toString();break;
                            case 7: content=createdList;break;
                            case 8: content=createdDetail;break;
                            case 9: content="{\"message\":\"이름 변경 검사 오류\"}";break;
                            case 10: content="";break;
                            case 11: content=changedDetail;break;
                            case 12: content=changedList;break;
                            default: content="[]";
                        }
                        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                        String status=i==5||i==9?"500 Internal Server Error":i==6?"201 Created":i==10?"204 No Content":"200 OK";
                        String response="HTTP/1.1 "+status+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                    }
                } catch(Exception error){throw new RuntimeException(error);}
            });
            try {
                assertTrue(credentials.edit().putString(base,token).commit());
                assertTrue(settings.edit().putString("server",base).remove("prompt").commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    ready(app);onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());ready(app);
                    onView(withText("+ 새 플레이리스트")).perform(scrollTo(),click());
                    onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(title),closeSoftKeyboard());
                    onView(withText("만들기")).inRoot(isDialog()).perform(click());ready(app);
                    onView(withText("새 플레이리스트")).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).check((view,error)->{
                        if(error!=null)throw error;
                        android.widget.EditText name=(android.widget.EditText)view;
                        assertEquals(title,name.getText().toString());assertEquals("생성 검사 오류",name.getError());
                    });
                    onView(withText("만들기")).inRoot(isDialog()).check(matches(isEnabled())).perform(click());ready(app);
                    onView(withText(title+"\n0곡 · 앱 추천에서 저장")).perform(scrollTo(),click());ready(app);
                    onView(withText(title)).check(matches(isDisplayed()));
                    onView(withText("이름 변경")).perform(scrollTo(),click());
                    onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(renamed),closeSoftKeyboard());
                    onView(withText("변경")).inRoot(isDialog()).perform(click());ready(app);
                    onView(withText("목록 이름 변경")).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).check((view,error)->{
                        if(error!=null)throw error;
                        android.widget.EditText name=(android.widget.EditText)view;
                        assertEquals(renamed,name.getText().toString());assertEquals("이름 변경 검사 오류",name.getError());
                    });
                    onView(withText("변경")).inRoot(isDialog()).check(matches(isEnabled())).perform(click());ready(app);
                    onView(withText(renamed)).check(matches(isDisplayed()));
                    onView(withText("‹ 내 목록으로")).perform(scrollTo(),click());ready(app);
                    onView(withText(renamed+"\n0곡 · 앱 추천에서 저장")).perform(scrollTo()).check(matches(isDisplayed()));
                    replies.get(10,TimeUnit.SECONDS);assertEquals(2,creates.get());assertEquals(2,renames.get());
                    assertEquals(previousFeedback,data.feedback());
                }
            } finally {
                if(!data.feedback().equals(previousFeedback)) {
                    data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                }
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());
                SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());
            }
        } finally {serverWorker.shutdownNow();data.close();}
    }

    @Test public void pendingServerRecommendationSurvivesRecreationWithoutDuplicatePost() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);Map<String,Integer> previousFeedback=data.feedback();
        String catalogue=ApiClient.read(context.getAssets().open("tracks.json"));
        String prompt="공부할 때 집중할 수 있는 가사 없는 피아노 음악";
        Recommender.Result result=Recommender.recommend(TrackJson.list(new JSONArray(catalogue)),Recommender.parse(prompt),Collections.emptyMap(),"openai");
        assertEquals(8,result.tracks.size());
        JSONArray picks=new JSONArray();
        for(Recommender.Pick pick:result.tracks)picks.put(new JSONObject().put("track",TrackJson.write(pick.track)).put("reason",pick.reason));
        String recommendation=new JSONObject().put("tracks",picks).put("mode",result.mode).put("notice",result.notice)
            .put("conditions",TrackJson.conditions(result.conditions)).toString();
        CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1),refreshed=new CountDownLatch(1);
        CountDownLatch secondReceived=new CountDownLatch(1),secondRelease=new CountDownLatch(1);
        AtomicBoolean running=new AtomicBoolean(true);
        AtomicInteger posts=new AtomicInteger(),savedReads=new AtomicInteger();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        ExecutorService serverWorkers=Executors.newCachedThreadPool();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);
            String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null);
            String token="DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
            Future<?> accepting=serverWorkers.submit(()->{
                while(running.get())try {
                    Socket socket=server.accept();
                    serverWorkers.submit(()->{
                        try(Socket connection=socket) {
                            connection.setSoTimeout(5000);
                            DataInputStream input=new DataInputStream(connection.getInputStream());
                            String[] request=httpLine(input).split(" ");
                            String header;int length=0;boolean authenticated=false;
                            while(!(header=httpLine(input)).isEmpty()) {
                                String lower=header.toLowerCase(java.util.Locale.ROOT);
                                if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                                if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                            }
                            assertTrue("Recreated activities must reuse the seeded session",authenticated);
                            assertTrue(length>=0&&length<=4096);
                            byte[] body=new byte[length];input.readFully(body);
                            String content;
                            if(request[1].equals("/recommendations")) {
                                assertEquals("POST",request[0]);int number=posts.incrementAndGet();assertTrue(number<=2);
                                assertEquals(prompt,new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("prompt"));
                                (number==1?received:secondReceived).countDown();
                                assertTrue("The test must release the delayed response",(number==1?release:secondRelease).await(15,TimeUnit.SECONDS));
                                content=recommendation;
                            } else {
                                assertEquals("GET",request[0]);
                                switch(request[1]) {
                                    case "/health": content="{\"status\":\"ok\"}";break;
                                    case "/catalogue": content=catalogue;break;
                                    case "/me/feedback": content="{}";break;
                                    case "/me/saved-tracks": content="[]";break;
                                    default: throw new AssertionError("Unexpected test request: "+request[1]);
                                }
                            }
                            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                            String response="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                            connection.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                            connection.getOutputStream().write(bytes);connection.getOutputStream().flush();
                            if(request[1].equals("/me/saved-tracks")&&savedReads.incrementAndGet()>=2)refreshed.countDown();
                        } catch(Throwable error) { if(running.get())serverFailure.compareAndSet(null,error); }
                    });
                } catch(SocketTimeoutException timeout) {
                    // Keep accepting metadata requests while the recommendation response is held.
                } catch(Exception error) { if(running.get())serverFailure.compareAndSet(null,error);return; }
            });
            try {
                assertTrue(credentials.edit().putString(base,token).commit());
                assertTrue(settings.edit().putString("server",base).remove("prompt").commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    try {
                        ready(app);
                        onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                        onView(withId(R.id.recommend)).perform(scrollTo(),click());
                        assertTrue("The server must receive the recommendation",received.await(5,TimeUnit.SECONDS));
                        app.recreate();
                        assertTrue("The recreated activity must refresh server metadata",refreshed.await(5,TimeUnit.SECONDS));
                        app.onActivity(activity->{
                            assertEquals(View.VISIBLE,activity.findViewById(R.id.progress).getVisibility());
                            assertFalse(activity.findViewById(R.id.recommend).isEnabled());
                        });
                        assertNull(serverFailure.get());assertEquals(1,posts.get());
                        release.countDown();ready(app);
                        onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                        onView(withId(R.id.notice)).check(matches(withText(startsWith("AI 추천"))));
                        onView(allOf(withId(R.id.track_title),isDescendantOfA(allOf(isAssignableFrom(android.widget.ScrollView.class),isDisplayed()))))
                            .perform(scrollTo()).check(matches(withText(result.tracks.get(0).track.title)));
                        onView(withId(R.id.next)).perform(click());ready(app);
                        app.recreate();ready(app);
                        onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                        assertEquals("A consumed result must not start or replay another request",1,posts.get());
                        assertNull(serverFailure.get());assertEquals(previousFeedback,data.feedback());

                        onView(withId(R.id.edit_request)).perform(scrollTo(),click());
                        onView(withId(R.id.recommend)).perform(closeSoftKeyboard(),scrollTo(),click());
                        assertTrue("The second request must reach the server",secondReceived.await(5,TimeUnit.SECONDS));
                        AtomicReference<RecommendationRequest> held=new AtomicReference<>();
                        app.onActivity(activity->{
                            held.set(new ViewModelProvider(activity).get(RecommendationRequest.class));
                            assertTrue(held.get().current().loading);
                            assertEquals(View.VISIBLE,activity.findViewById(R.id.progress).getVisibility());
                        });
                        assertTrue("The preceding recreation must finish its metadata refresh",savedReads.get()>=3);
                        app.moveToState(Lifecycle.State.CREATED);
                        secondRelease.countDown();
                        AtomicBoolean completed=new AtomicBoolean();long until=SystemClock.uptimeMillis()+5000;
                        do {
                            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                                RecommendationRequest.State pending=held.get().current();
                                completed.set(!pending.loading&&pending.result!=null);
                            });
                            if(completed.get())break;SystemClock.sleep(25);
                        } while(SystemClock.uptimeMillis()<until);
                        assertTrue("A stopped activity must retain its completed recommendation",completed.get());
                        assertNull(serverFailure.get());
                        CountDownLatch recreated=new CountDownLatch(1);
                        ActivityLifecycleCallback created=(activity,stage)->{
                            if(activity instanceof MainActivity&&stage==Stage.CREATED)recreated.countDown();
                        };
                        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(created));
                        try {
                            // Scenario.recreate resumes the old activity first, which would consume this result.
                            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                                boolean found=false;
                                for(android.app.Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED)) {
                                    if(activity instanceof MainActivity){activity.recreate();found=true;break;}
                                }
                                assertTrue("The owned activity must be stopped before recreation",found);
                            });
                            assertTrue("The stopped activity must be recreated",recreated.await(5,TimeUnit.SECONDS));
                            app.moveToState(Lifecycle.State.RESUMED);ready(app);
                            onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                            onView(withId(R.id.notice)).check(matches(withText(startsWith("AI 추천"))));
                            assertEquals("Each deliberate recommendation must send exactly one POST",2,posts.get());
                            assertNull(serverFailure.get());assertEquals(previousFeedback,data.feedback());
                        } finally {
                            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(created));
                        }
                    } finally { release.countDown();secondRelease.countDown(); }
                }
            } finally {
                running.set(false);release.countDown();secondRelease.countDown();server.close();
                if(!data.feedback().equals(previousFeedback)) {
                    data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                }
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());
                SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());
                accepting.get(2,TimeUnit.SECONDS);
            }
        } finally {running.set(false);release.countDown();secondRelease.countDown();serverWorkers.shutdownNow();data.close();}
    }

    @Test public void serverRetryCannotWriteIntoLocalFeedbackAfterModeSwitch() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);
        Map<String,Integer> previousFeedback=data.feedback();
        String catalogue=ApiClient.read(context.getAssets().open("tracks.json"));
        String prompt="공부할 때 집중할 수 있는 가사 없는 피아노 음악";
        Recommender.Result result=Recommender.recommend(TrackJson.list(new JSONArray(catalogue)),Recommender.parse(prompt),Collections.emptyMap(),"openai");
        assertEquals(8,result.tracks.size());JSONArray picks=new JSONArray();
        for(Recommender.Pick pick:result.tracks)picks.put(new JSONObject().put("track",TrackJson.write(pick.track)).put("reason",pick.reason));
        String recommendation=new JSONObject().put("tracks",picks).put("mode",result.mode).put("notice",result.notice)
            .put("conditions",TrackJson.conditions(result.conditions)).toString();
        String first=result.tracks.get(0).track.id;
        ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null);
            String token="CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
            AtomicInteger feedbackWrites=new AtomicInteger(),recommendationPosts=new AtomicInteger();
            try {
                assertTrue(credentials.edit().putString(base,token).commit());assertTrue(settings.edit().putString("server",base).remove("prompt").commit());data.clearFeedback();
                Future<?> replies=serverWorker.submit(()->{
                    try {
                        String[] methods={"GET","GET","GET","GET","POST","PUT"};
                        String[] paths={"/health","/catalogue","/me/feedback","/me/saved-tracks","/recommendations","/me/feedback/"+first};
                        for(int i=0;i<paths.length;i++)try(Socket socket=server.accept()) {
                            socket.setSoTimeout(5000);
                            DataInputStream input=new DataInputStream(socket.getInputStream());
                            String request=httpLine(input);assertTrue(request,request.startsWith(methods[i]+" "+paths[i]+" HTTP/"));
                            String header;int length=0;boolean authenticated=false;
                            while(!(header=httpLine(input)).isEmpty()) {
                                String lower=header.toLowerCase(java.util.Locale.ROOT);
                                if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                                if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                            }
                            assertTrue("Recommendations must reuse the seeded server session",authenticated);assertTrue(length>=0&&length<=4096);
                            byte[] body=new byte[length];input.readFully(body);
                            if(i==4){assertEquals(prompt,new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("prompt"));recommendationPosts.incrementAndGet();}
                            if(i==5){assertEquals(1,new JSONObject(new String(body,StandardCharsets.UTF_8)).getInt("reaction"));feedbackWrites.incrementAndGet();}
                            String content=i==0?"{\"status\":\"ok\"}":i==1?catalogue:i==2?"{}":i==3?"[]":i==4?recommendation:"{\"message\":\"재시도 검사 오류\"}";
                            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                            String response="HTTP/1.1 "+(i==5?"503 Service Unavailable":"200 OK")+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                            socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                        }
                    } catch(Exception error){throw new RuntimeException(error);}
                });
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    ready(app);onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                    onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                    onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                    onView(withId(R.id.notice)).check(matches(withText(startsWith("AI 추천"))));
                    onView(allOf(withId(R.id.like),isDisplayed())).perform(click());ready(app);
                    onView(withText("재시도 검사 오류")).check(matches(isDisplayed()));replies.get(10,TimeUnit.SECONDS);
                    onView(withId(R.id.settings)).perform(click());
                    onView(withText("이 기기의 기본 추천 사용")).inRoot(isDialog()).perform(scrollTo(),click());ready(app);
                    assertFalse(settings.contains("server"));onView(withText("재시도")).perform(click());ready(app);
                    assertTrue("A failed server reaction must not be retried into local storage",data.feedback().isEmpty());
                    assertEquals("The stale action must not repeat its server request",1,feedbackWrites.get());assertEquals(1,recommendationPosts.get());
                }
            } finally {
                server.close();
                data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                assertTrue(restore.commit());
                SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                assertTrue(restoreCredential.commit());
            }
        } finally {serverWorker.shutdownNow();data.close();}
    }

    @Test @SdkSuppress(minSdkVersion=28)
    public void webViewInitializationFailureOffersExternalPlayback() throws Exception {
        WebView.disableWebView();
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);
        Map<String,Integer> previousFeedback=data.feedback();
        try {
            settings.edit().remove("server").remove("prompt").commit();data.clearFeedback();
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(app);
                onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                app.onActivity(activity->{
                    // The earlier dialog's deferred dismiss must not close the new dialog.
                    View listen=activity.findViewById(R.id.listen);
                    listen.performClick();listen.performClick();
                });
                onView(withText("플레이어를 준비하지 못했어요. 외부에서 열어보세요.")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("외부에서 열기")).inRoot(isDialog()).perform(click());
                onView(withText("Spotify · 곡 검색")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("YouTube Music · 영상으로 연결")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("취소")).inRoot(isDialog()).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                assertTrue("Playback failure must not create recommendation feedback",data.feedback().isEmpty());
            }
        } finally {
            data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
            SharedPreferences.Editor restore=settings.edit();
            for(String key:new String[]{"server","prompt"}) {
                if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
            }
            restore.commit();data.close();
        }
    }

    private String httpLine(DataInputStream input) throws java.io.IOException {
        StringBuilder line=new StringBuilder();int next;
        while((next=input.read())!=-1) {
            if(next=='\n')return line.toString();
            if(next!='\r')line.append((char)next);
            if(line.length()>8192)throw new java.io.IOException("Test HTTP header is too long");
        }
        throw new java.io.EOFException("Incomplete test HTTP header");
    }

    private void pendingWriteVisible(ActivityScenario<MainActivity> app) {
        // Keep checking after native UI idle so a brief startup refresh cannot hide a pending write.
        long until=SystemClock.uptimeMillis()+250;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            app.onActivity(activity->assertEquals(View.VISIBLE,activity.findViewById(R.id.progress).getVisibility()));
            onView(withId(R.id.progress)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
            SystemClock.sleep(25);
        } while(SystemClock.uptimeMillis()<until);
    }

    private void ready(ActivityScenario<MainActivity> app) {
        long until=SystemClock.uptimeMillis()+5000;AtomicBoolean idle=new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            app.onActivity(activity->idle.set(activity.findViewById(R.id.progress).getVisibility()!=View.VISIBLE
                &&((androidx.viewpager2.widget.ViewPager2)activity.findViewById(R.id.pager)).getScrollState()==androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE));
            if(idle.get())return;SystemClock.sleep(25);
        } while(SystemClock.uptimeMillis()<until);
        fail("App operation did not finish within five seconds");
    }
}
