package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ErrorRecoveryCheckTest {
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
