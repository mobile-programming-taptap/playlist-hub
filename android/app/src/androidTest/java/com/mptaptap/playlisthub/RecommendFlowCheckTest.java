package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Recommender;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

/** 추천 탭: 진행 안내와 지난 실패 정리, 뒤로가기 후 결과 유지, 공식 영상이 없는 카드 빼기. */
@RunWith(AndroidJUnit4.class)
public class RecommendFlowCheckTest {
    private static final String FAILURE="실제 곡 정보를 확인하는 서비스가 응답하지 않아요. 잠시 뒤 같은 요청으로 다시 시도해 주세요.";
    private static final String PROMPT="공부할 때 집중할 수 있는 가사 없는 피아노 음악";

    /** Answers one request (path includes any query): returns {status, body}, or null for the default metadata answer. */
    private interface Route { Object[] answer(String method,String path) throws Exception; }

    @Test public void loadingReplacesTheOldFailureAndBackKeepsResults() throws Exception {
        Recommender.Result result=recommendation(8);
        CountDownLatch secondReceived=new CountDownLatch(1),secondRelease=new CountDownLatch(1);AtomicInteger posts=new AtomicInteger();
        run((method,path)->{
            if(path.equals("/recommendations")) {
                if(posts.incrementAndGet()==1)return new Object[]{503,new JSONObject().put("code","REQUEST_FAILED").put("message",FAILURE).toString()};
                secondReceived.countDown();assertTrue(secondRelease.await(15,TimeUnit.SECONDS));return new Object[]{200,body(result)};
            }
            if(path.startsWith("/playback/tracks/"))return new Object[]{200,new JSONObject().put("videoId","abcdefghijk").put("playbackAllowed",true).toString()};
            return null;
        },secondRelease,app->{
            onView(withId(R.id.prompt)).perform(replaceText(PROMPT),closeSoftKeyboard());
            onView(withId(R.id.recommend)).perform(click());
            waitFor(()->posts.get()==1);ready(app);
            onView(withText(FAILURE)).check(matches(isDisplayed()));

            onView(withId(R.id.recommend)).perform(click());
            assertTrue(secondReceived.await(10,TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withText(FAILURE)).check(doesNotExist());
            onView(withText(containsString("음악을 찾고 있어요 · 보통"))).check(matches(isDisplayed()));
            onView(withId(R.id.recommend)).check(matches(withText("음악을 찾고 있어요…")));

            secondRelease.countDown();ready(app);
            // The notice slides out, so give its dismiss animation a moment.
            waitFor(()->{try{onView(withText(containsString("음악을 찾고 있어요 · 보통"))).check(doesNotExist());return true;}catch(AssertionError stillThere){return false;}});
            onView(withId(R.id.counter)).check(matches(withText("1 / "+result.tracks.size())));

            Espresso.pressBackUnconditionally();
            waitFor(()->app.getState()!=Lifecycle.State.RESUMED);
            assertNotEquals("Leaving the results must not throw them away",Lifecycle.State.DESTROYED,app.getState());
            // The task only moved to the background, so the shown results are still in place for the user's return.
            app.onActivity(a->{assertEquals(View.VISIBLE,a.findViewById(R.id.results).getVisibility());
                assertEquals("1 / "+result.tracks.size(),((TextView)a.findViewById(R.id.counter)).getText().toString());});
        });
    }

    @Test public void cardsWithoutAnOfficialVideoAreDroppedAndFoundVideosPlayDirectly() throws Exception {
        Recommender.Result result=recommendation(3);
        String kept=result.tracks.get(1).track.id;List<String> checked=new CopyOnWriteArrayList<>();
        run((method,path)->{
            if(path.equals("/recommendations"))return new Object[]{200,body(result)};
            if(path.startsWith("/playback/tracks/")) {
                String id=path.substring("/playback/tracks/".length()).replaceAll("\\?.*","");checked.add(path);
                return id.equals(kept)?new Object[]{200,new JSONObject().put("videoId","abcdefghijk").put("playbackAllowed",true).toString()}
                    :new Object[]{404,new JSONObject().put("code","PLAYBACK_UNAVAILABLE").put("message","공식 채널 영상을 찾지 못했어요.").toString()};
            }
            return null;
        },null,app->{
            onView(withId(R.id.prompt)).perform(replaceText(PROMPT),closeSoftKeyboard());
            onView(withId(R.id.recommend)).perform(click());
            waitFor(()->checked.size()==3);ready(app);
            waitFor(()->{AtomicBoolean done=new AtomicBoolean();app.onActivity(a->done.set(((TextView)a.findViewById(R.id.counter)).getText().toString().equals("1 / 1")));return done.get();});
            for(String path:checked)assertFalse("Background checks must not ask for unverified candidates",path.contains("includeCandidates"));
            onView(withText("공식 영상을 찾지 못한 2곡은 뺐어요.")).check(matches(isDisplayed()));
            onView(withText(result.tracks.get(1).track.title)).check(matches(isDisplayed()));
        });
    }

    private static Recommender.Result recommendation(int size) throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Recommender.Result full=Recommender.recommend(TrackJson.list(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json")))),Recommender.parse(PROMPT),Collections.emptyMap(),"openai");
        List<Recommender.Pick> picks=new java.util.ArrayList<>(full.tracks.subList(0,size));
        for(Recommender.Pick pick:picks)pick.track.youtubeVideoId="";
        return new Recommender.Result(picks,full.conditions,full.mode,full.notice);
    }
    private static String body(Recommender.Result result) throws Exception {
        JSONArray picks=new JSONArray();
        for(Recommender.Pick pick:result.tracks)picks.put(new JSONObject().put("track",TrackJson.write(pick.track)).put("reason",pick.reason));
        return new JSONObject().put("tracks",picks).put("mode",result.mode).put("notice",result.notice).put("conditions",TrackJson.conditions(result.conditions)).toString();
    }

    private interface Steps { void run(ActivityScenario<MainActivity> app) throws Exception; }

    /** Serves the app's metadata requests plus {@code route}, signs the app in, launches it and runs {@code steps}. */
    private static void run(Route route,CountDownLatch releaseOnExit,Steps steps) throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        String catalogue=ApiClient.read(context.getAssets().open("tracks.json"));
        AtomicBoolean running=new AtomicBoolean(true);AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        ExecutorService workers=Executors.newCachedThreadPool();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);
            String base="http://127.0.0.1:"+server.getLocalPort(),token="EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE";
            workers.submit(()->{
                while(running.get())try {
                    Socket socket=server.accept();
                    workers.submit(()->{
                        try(Socket connection=socket) {
                            connection.setSoTimeout(5000);
                            DataInputStream input=new DataInputStream(connection.getInputStream());
                            String[] request=line(input).split(" ");String header;int length=0;
                            while(!(header=line(input)).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                            input.readFully(new byte[length]);
                            String path=request[1].contains("?")?request[1].substring(0,request[1].indexOf('?')):request[1];
                            Object[] answer=route.answer(request[0],request[1]);
                            if(answer==null)answer=new Object[]{200,path.equals("/health")?"{\"status\":\"ok\"}":path.equals("/catalogue")?catalogue:path.equals("/me/feedback")?"{}":"[]"};
                            int status=(Integer)answer[0];byte[] bytes=((String)answer[1]).getBytes(StandardCharsets.UTF_8);
                            connection.getOutputStream().write(("HTTP/1.1 "+status+(status==200?" OK":" Error")+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            connection.getOutputStream().write(bytes);connection.getOutputStream().flush();
                        } catch(Throwable error){if(running.get())serverFailure.compareAndSet(null,error);}
                    });
                } catch(SocketTimeoutException ignored) {
                } catch(Exception error){if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            assertTrue(credentials.edit().putString(base,token).commit());
            assertTrue(settings.edit().putString("server",base).putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).remove("prompt").commit());
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {ready(app);steps.run(app);}
            assertNull(serverFailure.get());
            credentials.edit().remove(base).commit();
        } finally {
            running.set(false);if(releaseOnExit!=null)releaseOnExit.countDown();workers.shutdownNow();
            SharedPreferences.Editor edit=settings.edit().clear();for(Map.Entry<String,?> row:previousSettings.entrySet())if(row.getValue() instanceof String)edit.putString(row.getKey(),(String)row.getValue());else if(row.getValue() instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)row.getValue());edit.commit();
        }
    }

    private static String line(DataInputStream input) throws java.io.IOException {
        StringBuilder text=new StringBuilder();int value;
        while((value=input.read())!=-1){if(value=='\n')break;if(value!='\r')text.append((char)value);}
        return text.toString();
    }
    private static void waitFor(java.util.function.BooleanSupplier condition){long until=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean())return;SystemClock.sleep(50);}fail("Condition was not met within ten seconds");}
    private static void ready(ActivityScenario<MainActivity> app){
        long until=SystemClock.uptimeMillis()+10000;AtomicBoolean ready=new AtomicBoolean();
        do{InstrumentationRegistry.getInstrumentation().waitForIdleSync();app.onActivity(activity->ready.set(activity.findViewById(R.id.progress).getVisibility()!=View.VISIBLE));if(ready.get())return;SystemClock.sleep(25);}while(SystemClock.uptimeMillis()<until);
        fail("App operation did not finish within ten seconds");
    }
}
