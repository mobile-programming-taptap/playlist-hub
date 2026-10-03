package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
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
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
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
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ErrorRecoveryCheckTest {
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
        ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            String base="http://127.0.0.1:"+server.getLocalPort();
            String previousCredential=credentials.getString(base,null);
            String token="CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
            AtomicInteger feedbackWrites=new AtomicInteger();
            settings.edit().remove("server").remove("prompt").commit();data.clearFeedback();
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(app);
                onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                Future<?> replies=serverWorker.submit(()->{
                    try {
                        String[] paths={"/health","/catalogue","/me/feedback","/me/saved-tracks","/me/feedback/"};
                        for(int i=0;i<paths.length;i++)try(Socket socket=server.accept()) {
                            socket.setSoTimeout(5000);
                            BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                            String request=input.readLine();
                            assertTrue(request,request.startsWith((i==4?"PUT ":"GET ")+paths[i]));
                            String header;int length=0;boolean authenticated=false;
                            while((header=input.readLine())!=null&&!header.isEmpty()) {
                                String lower=header.toLowerCase(java.util.Locale.ROOT);
                                if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                                if(lower.startsWith("authorization:"))authenticated=header.endsWith("Bearer "+token);
                            }
                            assertTrue("Restored results must reuse the seeded server session",authenticated);
                            assertTrue(length<=1000);
                            char[] body=new char[length];int read=0;
                            while(read<length){int count=input.read(body,read,length-read);if(count<0)throw new java.io.IOException("Incomplete test request");read+=count;}
                            if(i==4){assertEquals(1,new JSONObject(new String(body)).getInt("reaction"));feedbackWrites.incrementAndGet();}
                            String content=i==0?"{\"status\":\"ok\"}":i==1?catalogue:i==2?"{}":i==3?"[]":"{\"message\":\"재시도 검사 오류\"}";
                            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                            String response="HTTP/1.1 "+(i==4?"503 Service Unavailable":"200 OK")+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                            socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                        }
                    } catch(Exception error){throw new RuntimeException(error);}
                });
                credentials.edit().putString(base,token).commit();settings.edit().putString("server",base).commit();
                app.recreate();ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(allOf(withId(R.id.like),isDisplayed())).perform(click());ready(app);
                onView(withText("재시도 검사 오류")).check(matches(isDisplayed()));
                replies.get(10,TimeUnit.SECONDS);
                onView(withId(R.id.settings)).perform(click());
                onView(withText("이 기기의 기본 추천 사용")).perform(scrollTo(),click());ready(app);
                assertFalse(settings.contains("server"));
                onView(withText("재시도")).perform(click());ready(app);
                assertTrue("A failed server reaction must not be retried into local storage",data.feedback().isEmpty());
                assertEquals("The stale action must not repeat its server request",1,feedbackWrites.get());
            } finally {
                data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
                SharedPreferences.Editor restore=settings.edit();
                for(String key:new String[]{"server","prompt"}) {
                    if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
                }
                restore.commit();
                SharedPreferences.Editor restoreCredential=credentials.edit();
                if(previousCredential==null)restoreCredential.remove(base);else restoreCredential.putString(base,previousCredential);
                restoreCredential.commit();
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
