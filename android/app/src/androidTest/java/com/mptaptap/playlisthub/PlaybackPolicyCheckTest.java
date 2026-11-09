package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Loopback fixtures verify gates only; no real provider authorization or content mutation. */
@RunWith(AndroidJUnit4.class)
public class PlaybackPolicyCheckTest {
    @Test public void unacceptedApiCannotSendPrivateRequestsAndDeclineKeepsTheLocalLibraryUsable() throws Exception {
        try(Fixture fixture=new Fixture(false)){
            ApiClient client=new ApiClient(fixture.context,fixture.base);
            try{client.object("GET","/catalogue",null);fail("Block the API before consent");}catch(ApiClient.ConsentRequired expected){}
            assertEquals(0,fixture.requests.get());assertFalse(ApiClient.consentAccepted(fixture.context,fixture.base));
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                onView(withText("서비스 이용 안내")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("YouTube 서비스 약관")).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("Google 개인정보처리방침")).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
                assertEquals("https://www.youtube.com/t/terms",link("YouTube 서비스 약관","https"));
                assertEquals("https://policies.google.com/privacy",link("Google 개인정보처리방침","https"));
                assertEquals(fixture.base+"/privacy",link("TapTap 개인정보 처리 안내","http"));
                assertEquals(fixture.base+"/account-deletion",link("계정·데이터 삭제 안내","http"));
                onView(withText("동의하지 않음")).inRoot(isDialog()).perform(click());ready(app);
                assertEquals(0,fixture.requests.get());assertFalse(fixture.settings.contains("server"));assertFalse(ApiClient.consentAccepted(fixture.context,fixture.base));
                onView(withId(R.id.nav_library)).perform(click());ready(app);onView(withText("곡명·아티스트로 찾기")).perform(scrollTo()).check(matches(isDisplayed()));
            }
        }
    }

    @Test public void deepLinkedHubRequiresAnExplicitVersionedAgreementBeforeAccountData() throws Exception {
        try(Fixture fixture=new Fixture(false)){
            fixture.settings.edit().putString("serviceConsent:"+fixture.base,"older-version").commit();
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(fixture.context,HubActivity.class).putExtra("mode","account"))){
                onView(withText("서비스 이용 안내를 확인해 주세요")).check(matches(isDisplayed()));assertEquals(0,fixture.requests.get());
                onView(withText("이용 안내 확인")).perform(click());onView(withText("TapTap 개인정보 처리 안내")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("동의하고 계속")).inRoot(isDialog()).perform(click());waitUntil(()->fixture.accountReads.get()==1);idle();
                assertTrue(ApiClient.consentAccepted(fixture.context,fixture.base));assertFalse(ApiClient.consentAccepted(fixture.context,"https://other.example.invalid"));
                onView(withText("음악 서비스 연결")).perform(scrollTo()).check(matches(isDisplayed()));
            }
        }
    }

    @Test public void onlyExplicitVerifiedStatusEmbedsAndEveryAttemptRechecksBeforeLoading() throws Exception {
        try(Fixture fixture=new Fixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
            ready(app);fixture.response.set(allowed());play(app);ready(app);assertNotNull(player(app));assertEquals(1,fixture.playbackReads.get());
            app.moveToState(Lifecycle.State.CREATED);assertNull(player(app));app.moveToState(Lifecycle.State.RESUMED);ready(app);
            fixture.response.set(new JSONObject(allowed().toString()).put("madeForKids",true).put("playbackAllowed",false));play(app);ready(app);
            assertNull(player(app));onView(withText("이 영상은 YouTube에서 재생할 수 있어요.")).inRoot(isDialog()).check(matches(isDisplayed()));closeDialog();
            fixture.response.set(new JSONObject(allowed().toString()).put("madeForKids",JSONObject.NULL));play(app);ready(app);assertNull(player(app));closeDialog();
            fixture.response.set(new JSONObject(allowed().toString()).put("videoId","another-id1"));play(app);ready(app);assertNull(player(app));closeDialog();
            fixture.status.set(503);play(app);ready(app);assertNull(player(app));onView(withText("영상의 재생 정보를 확인하지 못했어요. YouTube에서 열 수 있어요.")).inRoot(isDialog()).check(matches(isDisplayed()));closeDialog();
            assertEquals(5,fixture.playbackReads.get());
        }
    }

    @Test public void delayedPlaybackCannotReappearAfterRecreationOrAnOwnerChange() throws Exception {
        try(Fixture fixture=new Fixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
            ready(app);fixture.response.set(allowed());fixture.hold.set(true);play(app);assertTrue(fixture.started.await(5,TimeUnit.SECONDS));
            app.recreate();fixture.release.countDown();ready(app);assertNull(player(app));assertEquals(1,fixture.playbackReads.get());
            fixture.hold.set(false);play(app);ready(app);assertNotNull(player(app));assertEquals(2,fixture.playbackReads.get());
        }
        try(Fixture fixture=new Fixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
            ready(app);fixture.response.set(allowed());fixture.hold.set(true);play(app);assertTrue(fixture.started.await(5,TimeUnit.SECONDS));
            fixture.credentials.edit().putString(fixture.base,repeat('B')).commit();fixture.release.countDown();ready(app);assertNull(player(app));assertEquals(1,fixture.playbackReads.get());
        }
    }

    @Test public void songsWithoutVideoIdsSearchBeforeShowingAPlayerOrAnAccurateFailure() throws Exception {
        try(Fixture fixture=new Fixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
            ready(app);fixture.response.set(allowed());playUnlinked(app);ready(app);
            assertNotNull(player(app));assertEquals(1,fixture.playbackReads.get());
            fixture.status.set(429);fixture.response.set(new JSONObject().put("message","플랫폼 요청 한도에 도달했어요."));
            playUnlinked(app);ready(app);assertNull(player(app));
            onView(withText("플랫폼 요청 한도에 도달했어요.")).inRoot(isDialog()).check(matches(isDisplayed()));
            fixture.status.set(200);fixture.response.set(allowed());
            onView(withText("다시 시도")).inRoot(isDialog()).perform(click());ready(app);
            assertNotNull(player(app));assertEquals(3,fixture.playbackReads.get());
        }
    }

    @Test public void unverifiedChannelNeedsConfirmationBeforeCreatingPlayer() throws Exception {
        try(Fixture fixture=new Fixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
            ready(app);fixture.response.set(allowed().put("requiresConfirmation",true).put("candidateTitle","추천 곡 공식 영상").put("candidateChannel","음반사 채널"));
            playUnlinked(app);ready(app);assertNull(player(app));
            onView(withText("이 영상이 맞나요?")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("영상 재생")).inRoot(isDialog()).perform(click());idle();assertNotNull(player(app));
        }
    }

    @Test public void unverifiedAlternativesCanBeSelectedInsideTheApp() throws Exception {
        try(Fixture fixture=new Fixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
            ready(app);JSONObject other=allowed().put("videoId","abcdefghij2").put("requiresConfirmation",true).put("candidateTitle","두 번째 영상").put("candidateChannel","다른 채널");
            fixture.response.set(allowed().put("requiresConfirmation",true).put("candidateTitle","첫 번째 영상").put("candidateChannel","첫 채널").put("alternatives",new org.json.JSONArray().put(other)));
            playUnlinked(app);ready(app);assertNull(player(app));
            onView(withText("다른 영상")).inRoot(isDialog()).perform(click());
            onView(withText("두 번째 영상\n다른 채널")).inRoot(isDialog()).perform(click());idle();assertNotNull(player(app));
        }
    }

    private static JSONObject allowed() throws Exception{return new JSONObject().put("videoId","abcdefghijk").put("madeForKids",false).put("embeddable",true).put("playbackAllowed",true).put("checkedAt","2026-10-19T00:00:00Z");}
    private static void playUnlinked(ActivityScenario<MainActivity> app){app.onActivity(activity->{try{
        var track=new com.mptaptap.core.Track();track.id="11111111-1111-4111-8111-111111111111";track.title="연결 없는 추천곡";track.artist="검사 아티스트";
        var method=MainActivity.class.getDeclaredMethod("listen",com.mptaptap.core.Track.class);method.setAccessible(true);method.invoke(activity,track);
    }catch(Exception error){throw new AssertionError(error);}});}
    private static String repeat(char value){return String.join("",java.util.Collections.nCopies(43,String.valueOf(value)));}
    private static void play(ActivityScenario<MainActivity> app){app.onActivity(activity->{try{var method=MainActivity.class.getDeclaredMethod("listenVideo",String.class,String.class,int.class,Runnable.class);method.setAccessible(true);method.invoke(activity,"재생 정책 검사","abcdefghijk",73,(Runnable)()->{});}catch(Exception error){throw new AssertionError(error);}});}
    private static Object player(ActivityScenario<MainActivity> app){AtomicReference<Object> result=new AtomicReference<>();app.onActivity(activity->{try{var field=MainActivity.class.getDeclaredField("player");field.setAccessible(true);result.set(field.get(activity));}catch(Exception error){throw new AssertionError(error);}});return result.get();}
    private static void closeDialog(){onView(withText("닫기")).inRoot(isDialog()).perform(click());idle();}
    private static String link(String title,String scheme){
        var instrumentation=InstrumentationRegistry.getInstrumentation();AtomicReference<Intent> opened=new AtomicReference<>();IntentFilter filter=new IntentFilter(Intent.ACTION_VIEW);filter.addDataScheme(scheme);filter.addCategory(Intent.CATEGORY_BROWSABLE);
        var monitor=instrumentation.addMonitor(filter,new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true);
        androidx.test.runner.intent.IntentCallback callback=intent->{if(Intent.ACTION_VIEW.equals(intent.getAction()))opened.set(new Intent(intent));};var registry=androidx.test.runner.intent.IntentMonitorRegistry.getInstance();registry.addIntentCallback(callback);
        try{onView(withText(title)).inRoot(isDialog()).perform(scrollTo(),click());idle();assertEquals(1,monitor.getHits());assertNotNull(opened.get());return opened.get().getDataString();}
        finally{registry.removeIntentCallback(callback);instrumentation.removeMonitor(monitor);}
    }
    private static void ready(ActivityScenario<MainActivity> app){waitUntil(()->{AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->{try{var field=MainActivity.class.getDeclaredField("busy");field.setAccessible(true);done.set(!field.getBoolean(activity));}catch(Exception error){throw new AssertionError(error);}});return done.get();});}
    private static void waitUntil(java.util.function.BooleanSupplier condition){long until=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean()){idle();return;}SystemClock.sleep(20);}fail("Policy fixture did not settle");}
    private static void idle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();}

    private static final class Fixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        final Map<String,?> previousSettings=settings.getAll(),previousCredentials=credentials.getAll();
        final AtomicBoolean running=new AtomicBoolean(true),hold=new AtomicBoolean();final AtomicInteger requests=new AtomicInteger(),playbackReads=new AtomicInteger(),accountReads=new AtomicInteger(),status=new AtomicInteger(200);
        final AtomicReference<JSONObject> response=new AtomicReference<>();final AtomicReference<Throwable> failure=new AtomicReference<>();final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final ServerSocket server;final String base,token=repeat('A');final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();final java.util.concurrent.Future<?> task;
        Fixture(boolean consent) throws Exception {
            if(android.os.Build.VERSION.SDK_INT>=37)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
            server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(200);base="http://127.0.0.1:"+server.getLocalPort();
            settings.edit().putString("server",base).remove("serviceConsent:"+base).remove("authorizationProvider").commit();credentials.edit().putString(base,token).commit();if(consent)ApiClient.acceptConsent(context,base);
            task=worker.submit(()->{while(running.get())try(Socket socket=server.accept()){
                socket.setSoTimeout(5000);requests.incrementAndGet();BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String[] line=input.readLine().split(" ");assertEquals("GET",line[0]);String header;boolean authorized=false;
                while(!(header=input.readLine()).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:")&&header.endsWith("Bearer "+token))authorized=true;assertTrue(authorized);int code=200;String body;
                switch(line[1]){
                    case "/health":body="{\"status\":\"ok\"}";break;
                    case "/catalogue":case "/me/saved-tracks":case "/playlists":case "/connections":body="[]";break;
                    case "/me/feedback":body="{}";break;
                    case "/accounts/me":accountReads.incrementAndGet();code=404;body="{\"message\":\"로그인이 필요해요.\"}";break;
                    case "/connections/config":body="{\"providers\":[]}";break;
                    case "/playback/tracks/11111111-1111-4111-8111-111111111111?includeCandidates=true":
                    case "/playback/youtube/abcdefghijk":playbackReads.incrementAndGet();started.countDown();if(hold.get())assertTrue(release.await(10,TimeUnit.SECONDS));code=status.get();body=response.get()==null?"{}":response.get().toString();break;
                    default:throw new AssertionError("Unexpected playback fixture path "+line[1]);
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
            }catch(SocketTimeoutException idle){}catch(Throwable error){if(running.get())failure.compareAndSet(null,error);return;}});
        }
        @Override public void close() throws Exception{release.countDown();running.set(false);server.close();task.get(2,TimeUnit.SECONDS);worker.shutdownNow();restore(settings,previousSettings);restore(credentials,previousCredentials);assertNull(failure.get());}
    }
    private static void restore(SharedPreferences preferences,Map<String,?> original){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){
        String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,(java.util.Set<String>)value);
    }assertTrue(edit.commit());}
}
