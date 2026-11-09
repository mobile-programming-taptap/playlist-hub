package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

/** Disposable app-owner and loopback fixtures; never grants a real provider permission. */
@RunWith(AndroidJUnit4.class)
public class OAuthReturnCheckTest {
    @Test public void rejectsUnmatchedMalformedAndCrossOwnerCallbacksWithoutLosingTheRealPendingFlow() throws Exception {
        try(Fixture fixture=new Fixture()){
            HubRequest request=new HubRequest();
            assertFalse(request.receiveAuthorization(fixture.context,fixture.base,Uri.parse(callback("connected",""))));
            request.rememberAuthorization(fixture.context,fixture.base,"youtube",true);
            for(String invalid:new String[]{"https://connected?provider=youtube&status=connected","taptap://connected/path?provider=youtube&status=connected",
                    "taptap://connected?provider=youtube&provider=spotify&status=connected","taptap://connected?provider=youtube&status=connected#fragment",
                    "taptap://connected?provider=youtube&status=error&reason=raw-secret","taptap://connected?provider=spotify&status=connected",
                    "taptap://connected?provider=youtube&status=connected&code=secret","taptap://connected?provider=youtube&status=connected&reason=access_denied"}){
                assertFalse(invalid,request.receiveAuthorization(fixture.context,fixture.base,Uri.parse(invalid)));
                assertEquals("youtube",fixture.settings.getString("authorizationProvider",""));
            }
            assertTrue(request.receiveAuthorization(fixture.context,fixture.base,Uri.parse(callback("cancelled","access_denied"))));
            assertEquals("cancelled",request.data.optString("authorizationStatus"));assertEquals("youtube",request.data.optString("libraryProvider"));
            fixture.credentials.edit().putString(fixture.base,repeat('B')).commit();
            assertFalse(request.receiveAuthorization(fixture.context,fixture.base,Uri.parse(callback("connected",""))));
            assertFalse(fixture.settings.contains("authorizationProvider"));assertEquals(0,fixture.reads);request.onCleared();
        }
    }

    @Test public void cancellationAndExpiredReturnRemainVisibleAfterRecreationAndColdStart() throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.pending(false);
            try(ActivityScenario<HubActivity> app=fixture.launch("cancelled","access_denied")){
                ready(app);onView(withText("음악 계정 연결을 취소했어요. 다시 연결할 수 있어요.")).perform(scrollTo()).check(matches(isDisplayed()));
                app.recreate();ready(app);onView(withText("음악 계정 연결을 취소했어요. 다시 연결할 수 있어요.")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("연결됨")).check(doesNotExist());
            }
            fixture.pending(true);fixture.settings.edit().putLong("authorizationStarted",System.currentTimeMillis()-601_000).commit();
            try(ActivityScenario<HubActivity> app=fixture.launch("connected","")){
                ready(app);onView(withText("내 플레이리스트")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("음악 계정 연결 요청이 만료됐어요. 앱에서 연결을 다시 시작해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));
                app.recreate();ready(app);onView(withText("음악 계정 연결 요청이 만료됐어요. 앱에서 연결을 다시 시작해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            assertTrue(fixture.reads>=2);assertEquals(fixture.token,fixture.credentials.getString(fixture.base,""));
        }
    }

    @Test public void callbackSuccessCannotInventAConnectionAndFailureCannotEraseAnExistingConnection() throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.pending(false);
            try(ActivityScenario<HubActivity> app=fixture.launch("connected","")){
                ready(app);onView(withText("음악 계정 연결을 확인하지 못했어요. 다시 연결해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("연결됨")).check(doesNotExist());
            }
            fixture.connected.set(true);fixture.pending(false);
            try(ActivityScenario<HubActivity> app=fixture.launch("error","provider_forbidden")){
                ready(app);onView(withText("연결됨")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("음악 서비스가 연결을 허용하지 않았어요. 계정의 권한과 YouTube 채널 설정을 확인해 주세요. 기존 연결은 유지돼요.")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            assertEquals(fixture.token,fixture.credentials.getString(fixture.base,""));assertNull(fixture.failure.get());
        }
    }

    @Test public void anonymousDeletionUsesNoProofAndFailuresCannotDeleteAReplacementOwner() throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.anonymous.set(true);fixture.rejectedDeletion.set(true);
            try(ActivityScenario<HubActivity> app=fixture.account()){
                ready(app);onView(withText("이 기기의 서버 데이터 삭제")).perform(scrollTo(),click());onView(withText("서버 데이터 삭제")).perform(click());ready(app);
                assertEquals(fixture.token,fixture.credentials.getString(fixture.base,""));assertEquals(0,fixture.deletions);assertEquals(0,fixture.sessions);
                fixture.rejectedDeletion.set(false);onView(withText("이 기기의 서버 데이터 삭제")).perform(scrollTo(),click());onView(withText("서버 데이터 삭제")).perform(click());ready(app);
                assertEquals(1,fixture.deletions);assertEquals(1,fixture.sessions);assertEquals(repeat('N'),fixture.credentials.getString(fixture.base,""));
                onView(withText("이 기기의 서버 데이터 삭제")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            assertNull(fixture.failure.get());
        }
    }

    @Test public void expiredRegisteredSessionShowsPersistentReloginHintAndKeepsNewOwnerIsolated() throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.connected.set(true);fixture.pending(true);fixture.expired.set(true);
            try(ActivityScenario<HubActivity> app=fixture.account()){
                ready(app);onView(withText("로그인이 만료됐어요. 다시 로그인하면 내 목록을 이어서 사용할 수 있어요.")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("연결됨")).check(doesNotExist());assertEquals(repeat('N'),fixture.credentials.getString(fixture.base,""));assertFalse(fixture.settings.contains("authorizationProvider"));
                app.recreate();ready(app);onView(withText("로그인이 만료됐어요. 다시 로그인하면 내 목록을 이어서 사용할 수 있어요.")).perform(scrollTo()).check(matches(isDisplayed()));
                assertEquals(1,fixture.sessions);assertEquals(0,fixture.deletions);
            }
            ApiClient client=new ApiClient(fixture.context,fixture.base);assertTrue(client.loginExpired());client.replaceSession(repeat('K'));assertFalse(client.loginExpired());
        }
    }

    private static String callback(String status,String reason){return "taptap://connected?provider=youtube&status="+status+(reason.isEmpty()?"":"&reason="+reason);}
    private static String repeat(char value){return String.join("",java.util.Collections.nCopies(43,String.valueOf(value)));}
    private static void ready(ActivityScenario<HubActivity> app){
        long deadline=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<deadline){AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->{try{var field=HubActivity.class.getDeclaredField("requests");field.setAccessible(true);done.set(!((HubRequest)field.get(activity)).busy()&&activity.hasWindowFocus());}catch(Exception error){throw new AssertionError(error);}});if(done.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(20);}
        fail("OAuth account verification did not settle");
    }
    private static final class Fixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        final Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        final AtomicBoolean connected=new AtomicBoolean(),running=new AtomicBoolean(true),anonymous=new AtomicBoolean(),expired=new AtomicBoolean(),rejectedDeletion=new AtomicBoolean();final AtomicReference<Throwable> failure=new AtomicReference<>();
        final ServerSocket server;final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();final java.util.concurrent.Future<?> task;
        final String base,token=repeat('A');volatile String activeToken=token;volatile int reads,deletions,sessions;
        Fixture() throws Exception {
            if(android.os.Build.VERSION.SDK_INT>=37)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
            server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(200);base="http://127.0.0.1:"+server.getLocalPort();
            assertTrue(settings.edit().putString("server",base).putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).remove("authorizationProvider").commit());assertTrue(credentials.edit().putString(base,token).commit());
            task=worker.submit(()->{while(running.get())try(Socket socket=server.accept()){
                socket.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String[] line=input.readLine().split(" ");String header;boolean authenticated=false;int length=0;
                while(!(header=input.readLine()).isEmpty()){if(header.equals("Authorization: Bearer "+activeToken))authenticated=true;if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());}
                if(!line[1].equals("/health")&&!line[1].equals("/sessions"))assertTrue(authenticated);assertEquals(0,length);String body;int status=200;
                switch(line[1]){
                    case "/health":body="{\"status\":\"ok\"}";break;
                    case "/sessions":assertEquals("POST",line[0]);assertEquals("",activeToken);sessions++;activeToken=repeat('N');anonymous.set(true);body="{\"token\":\""+activeToken+"\"}";break;
                    case "/accounts/me":
                        if(line[0].equals("DELETE")){
                            assertTrue(anonymous.get());if(rejectedDeletion.get()){status=401;body="{\"code\":\"UNAUTHORIZED\",\"message\":\"연결 정보를 확인해 주세요.\"}";}
                            else{deletions++;activeToken="";connected.set(false);body="{}";}
                        }else{
                            assertEquals("GET",line[0]);
                            if(expired.getAndSet(false)){status=401;activeToken="";body="{\"code\":\"SESSION_EXPIRED\",\"message\":\"로그인이 만료됐어요. 다시 로그인해 주세요.\"}";}
                            else if(anonymous.get()){status=404;body="{\"message\":\"로그인이 필요해요.\"}";}
                            else body="{\"name\":\"OAuth 검사\",\"email\":\"fixture@example.invalid\"}";
                        }break;
                    case "/connections/config":body="{\"providers\":[{\"provider\":\"youtube\",\"configured\":true}]}";break;
                    case "/connections":reads++;body=connected.get()&&!anonymous.get()?"[{\"provider\":\"youtube\",\"connected\":true}]":"[]";break;
                    default:throw new AssertionError("Unexpected OAuth fixture path: "+line[1]);
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
            }catch(SocketTimeoutException idle){}catch(Throwable error){if(running.get())failure.compareAndSet(null,error);return;}});
        }
        void pending(boolean library){HubRequest request=new HubRequest();request.rememberAuthorization(context,base,"youtube",library);request.onCleared();}
        ActivityScenario<HubActivity> launch(String status,String reason){return ActivityScenario.launch(new Intent(context,HubActivity.class).setData(Uri.parse(callback(status,reason))));}
        ActivityScenario<HubActivity> account(){return ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","account"));}
        @Override public void close() throws Exception {running.set(false);server.close();task.get(2,TimeUnit.SECONDS);worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);assertNull(failure.get());}
    }
    private static void restore(SharedPreferences preferences,Map<String,?> original){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){
        Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,(java.util.Set<String>)value);
    }assertTrue(edit.commit());}
}
