package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.espresso.matcher.ViewMatchers;
import com.google.android.material.button.MaterialButton;
import com.kakao.sdk.common.model.ClientError;
import com.kakao.sdk.common.model.ClientErrorCause;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** SDK callback fixtures verify app behavior, never authenticate a real Kakao user. */
@RunWith(AndroidJUnit4.class)
public class KakaoLoginCheckTest {
    @Test public void launcherShowsLoginAndGuestAndCancellationStaysOnWelcome() throws Exception {
        try(Fixture fixture=new Fixture()){
            Intent launcher=fixture.context.getPackageManager().getLaunchIntentForPackage(fixture.context.getPackageName());
            assertNotNull(launcher);assertEquals("com.mptaptap.playlisthub.Launcher",launcher.getComponent().getClassName());
            android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
            android.app.Instrumentation.ActivityMonitor mainMonitor=instrumentation.addMonitor(MainActivity.class.getName(),new android.app.Instrumentation.ActivityResult(0,null),true);
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(fixture.context,HubActivity.class).setAction(Intent.ACTION_MAIN))){
                onView(withText("카카오 로그인")).check(matches(isDisplayed()));
                onView(withText("게스트로 시작하기")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
                app.recreate();
                app.onActivity(activity->{HubRequest requests=requests(activity);Object login=attempt(requests,fixture,false);callback(requests,fixture,login,null,new ClientError(ClientErrorCause.Cancelled,"fixture"),true);});
                ready(app);
                assertEquals(0,mainMonitor.getHits());assertEquals(0,fixture.logins.get());
                onView(withText("게스트로 시작하기")).perform(scrollTo(),click());
                await(()->mainMonitor.getHits()==1);assertEquals(1,mainMonitor.getHits());
                assertEquals(fixture.originalOwner,fixture.credentials.getString(fixture.base,""));
            }finally{instrumentation.removeMonitor(mainMonitor);}
        }
    }

    @Test public void guestEntryDoesNotReuseARegisteredAccountOrDeleteItsData() throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.signedIn.set(true);fixture.credentials.edit().putString(fixture.base,fixture.kakaoOwner).commit();
            var instrumentation=InstrumentationRegistry.getInstrumentation();
            var monitor=instrumentation.addMonitor(MainActivity.class.getName(),new android.app.Instrumentation.ActivityResult(0,null),true);
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(fixture.context,HubActivity.class).setAction(Intent.ACTION_MAIN))){
                onView(withText("게스트로 시작하기")).perform(scrollTo(),click());await(()->monitor.getHits()==1);
                assertEquals(fixture.freshAnonymous,fixture.credentials.getString(fixture.base,""));assertTrue("The registered account remains intact",fixture.signedIn.get());assertEquals(0,fixture.deletes.get());assertEquals(0,fixture.logins.get());
            }finally{instrumentation.removeMonitor(monitor);}
        }
    }

    @Test public void welcomeKakaoSuccessEntersTheAppWithTheAuthenticatedOwner() throws Exception {
        try(Fixture fixture=new Fixture()){
            android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
            android.app.Instrumentation.ActivityMonitor mainMonitor=instrumentation.addMonitor(MainActivity.class.getName(),new android.app.Instrumentation.ActivityResult(0,null),true);
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(fixture.context,HubActivity.class).setAction(Intent.ACTION_MAIN))){
                app.onActivity(activity->{HubRequest requests=requests(activity);Object login=attempt(requests,fixture,false);callback(requests,fixture,login,"fixture-success-token",null,true);});
                await(()->mainMonitor.getHits()==1);
                assertEquals(1,fixture.logins.get());assertEquals(fixture.kakaoOwner,fixture.credentials.getString(fixture.base,""));
            }finally{instrumentation.removeMonitor(mainMonitor);}
        }
    }

    @Test public void primaryKakaoButtonAndMissingConfigurationPreserveTheCurrentOwner() throws Exception {
        assumeTrue("Missing-key build only",!TapTapApplication.kakaoConfigured());
        try(Fixture fixture=new Fixture();ActivityScenario<HubActivity> app=fixture.launch()){
            ready(app);
            onView(withText("카카오 로그인")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withHint("비밀번호 (15자 이상)")).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
            for(int i=0;i<3;i++){
                onView(withText("카카오 로그인")).perform(click());
                onView(withText("카카오 로그인 설정이 필요해요")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("확인")).inRoot(isDialog()).perform(click());
                assertEquals(fixture.originalOwner,fixture.credentials.getString(fixture.base,""));
            }
            app.onActivity(activity->{
                HubRequest requests=requests(activity);assertFalse(requests.kakaoPending());assertFalse(requests.busy());
                for(View view:children((View)get(activity,"content")))if(view instanceof MaterialButton&&((MaterialButton)view).getText().toString().equals("카카오 로그인")){
                    MaterialButton button=(MaterialButton)view;assertEquals(0xFFFEE500,button.getBackgroundTintList().getDefaultColor());assertNotNull(button.getIcon());assertTrue(button.getHeight()>=TapTapUi.dp(activity,48));
                }
                requests.send(activity,fixture.base,"kakaoConfiguration","GET","/accounts/kakao/config",null);
            });
            ready(app);onView(withText("Kakao 서버 앱 ID 설정이 필요해요.")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("확인")).inRoot(isDialog()).perform(click());
            onView(withText("같은 요청 다시 시도")).check(doesNotExist());
            assertEquals(0,fixture.logins.get());assertEquals(fixture.originalOwner,fixture.credentials.getString(fixture.base,""));
        }
    }

    @Test public void cancellationStopsFallbackAndLateCallbacksCannotChangeOwners() throws Exception {
        try(Fixture fixture=new Fixture()){
            HubRequest requests=new HubRequest();AtomicReference<Object> attempt=new AtomicReference<>();
            main(()->{requests.synchronize(fixture.context,fixture.base);attempt.set(attempt(requests,fixture,false));callback(requests,fixture,attempt.get(),null,new ClientError(ClientErrorCause.Cancelled,"fixture"),true);});
            await(()->requests.current()!=null&&!requests.busy());
            main(()->{assertEquals("kakaoAuthorization",requests.current().route);assertFalse(requests.kakaoPending());assertTrue(requests.current().error.getMessage().contains("취소"));assertNull(requests.current().value);});
            assertEquals(fixture.originalOwner,fixture.credentials.getString(fixture.base,""));
            main(()->callback(requests,fixture,attempt.get(),"fixture-late-token",null,true));
            SystemClock.sleep(100);assertEquals(0,fixture.logins.get());
            main(()->{requests.consume(requests.current());attempt.set(attempt(requests,fixture,false));callback(requests,fixture,attempt.get(),null,new IllegalStateException("fixture failure"),true);});
            await(()->requests.current()!=null&&!requests.busy());
            main(()->{assertEquals("kakaoFallback",requests.current().route);assertTrue(requests.kakaoPending());assertNull(requests.current().error);requests.cancelKakao(fixture.context);assertFalse(requests.busy());assertFalse(requests.kakaoPending());callback(requests,fixture,attempt.get(),"fixture-late-token",null,false);requests.onCleared();});
            assertEquals(0,fixture.logins.get());
        }
    }

    @Test public void oneTokenExchangeSurvivesRotationAndClearsPreviousAccountDrafts() throws Exception {
        try(Fixture fixture=new Fixture();ActivityScenario<HubActivity> app=fixture.launch()){
            ready(app);AtomicReference<HubRequest> held=new AtomicReference<>();AtomicReference<Object> login=new AtomicReference<>();
            app.onActivity(activity->{HubRequest requests=requests(activity);held.set(requests);put(requests.data,"importId","previous-import");put(requests.data,"libraryItems","previous-library");put(requests.data,"batchImports","previous-batch");login.set(attempt(requests,fixture,false));});
            app.recreate();app.onActivity(activity->assertSame("Rotation keeps the auth attempt",held.get(),requests(activity)));
            fixture.holdLogin.set(true);
            main(()->callback(held.get(),fixture,login.get(),"fixture-success-token",null,true));
            assertTrue("One exchange reached the server",fixture.loginArrived.await(5,TimeUnit.SECONDS));
            app.recreate();fixture.releaseLogin.countDown();ready(app);
            assertEquals(fixture.kakaoOwner,fixture.credentials.getString(fixture.base,""));assertEquals(1,fixture.logins.get());
            onView(withText("카카오 검사 사용자")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("카카오 계정으로 로그인됨")).perform(scrollTo()).check(matches(isDisplayed()));
            app.onActivity(activity->{HubRequest requests=requests(activity);assertFalse(requests.data.has("importId"));assertFalse(requests.data.has("libraryItems"));assertFalse(requests.data.has("batchImports"));assertFalse(requests.data.toString().contains("fixture-success-token"));assertNull(get(requests,"lastBody"));});
            for(Object value:fixture.credentials.getAll().values())assertFalse(value.toString().contains("fixture-success-token"));
            main(()->callback(held.get(),fixture,login.get(),"fixture-late-token",null,true));SystemClock.sleep(100);assertEquals(1,fixture.logins.get());
        }
    }

    @Test public void rejectedKakaoTokenKeepsTheAppSessionAndDoesNotRetainTheSecret() throws Exception {
        try(Fixture fixture=new Fixture()){
            ApiClient client=new ApiClient(fixture.context,fixture.base);client.connect();
            try{client.object("POST","/accounts/kakao",new JSONObject().put("accessToken","fixture-rejected-token"));fail("Reject the fixture token");}
            catch(ApiClient.ApiError expected){assertEquals(401,expected.statusCode);}
            assertEquals(fixture.originalOwner,client.identity());
            HubRequest requests=new HubRequest();
            main(()->requests.send(fixture.context,fixture.base,"signedIn","POST","/accounts/kakao",json("accessToken","fixture-rejected-token")));
            await(()->requests.current()!=null&&!requests.busy());
            main(()->{assertNotNull(requests.current().error);assertNull(get(requests,"lastBody"));assertNull(get(requests,"lastRoute"));assertFalse(requests.data.toString().contains("fixture-rejected-token"));requests.retry(fixture.context,fixture.base);requests.onCleared();});
            assertEquals(fixture.originalOwner,fixture.credentials.getString(fixture.base,""));assertEquals(2,fixture.logins.get());
        }
    }

    @Test public void kakaoDeletionSendsFreshProofWithoutAPassword() throws Exception {
        assumeTrue("Fixture logout needs no real SDK request",!TapTapApplication.kakaoConfigured());
        try(Fixture fixture=new Fixture();ActivityScenario<HubActivity> app=fixture.launch()){
            ready(app);fixture.signedIn.set(true);fixture.credentials.edit().putString(fixture.base,fixture.kakaoOwner).commit();
            app.onActivity(activity->{HubRequest requests=requests(activity);requests.synchronize(activity,fixture.base);Object deletion=attempt(requests,fixture,true);callback(requests,fixture,deletion,"fixture-delete-proof",null,false);});
            ready(app);assertEquals(1,fixture.deletes.get());assertFalse(fixture.credentials.getString(fixture.base,"").equals(fixture.kakaoOwner));
            onView(withText("카카오 로그인")).perform(scrollTo()).check(matches(isDisplayed()));
            assertEquals("fixture-delete-proof",fixture.deletionBody.get().optString("kakaoAccessToken"));assertFalse(fixture.deletionBody.get().has("password"));
        }
    }

    private static Object attempt(HubRequest requests,Fixture fixture,boolean delete){
        try{
            Class<?> type=Class.forName(HubRequest.class.getName()+"$KakaoAttempt");var constructor=type.getDeclaredConstructor(String.class,String.class,boolean.class);constructor.setAccessible(true);
            String owner=fixture.credentials.getString(fixture.base,"");Object attempt=constructor.newInstance(fixture.base,owner,delete);Field field=HubRequest.class.getDeclaredField("kakaoAttempt");field.setAccessible(true);field.set(requests,attempt);
            ((MutableLiveData<HubRequest.State>)get(requests,"state")).setValue(new HubRequest.State("kakaoAuthorization",true,null,null,fixture.base,owner));return attempt;
        }catch(Exception error){throw new AssertionError(error);}
    }
    private static void callback(HubRequest requests,Fixture fixture,Object attempt,String token,Throwable error,boolean talk){
        try{Method method=HubRequest.class.getDeclaredMethod("kakaoResult",Context.class,attempt.getClass(),String.class,Throwable.class,boolean.class);method.setAccessible(true);method.invoke(requests,fixture.context,attempt,token,error,talk);}catch(Exception failure){throw new AssertionError(failure);}
    }
    private static HubRequest requests(HubActivity activity){return (HubRequest)get(activity,"requests");}
    private static Object get(Object object,String name){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception error){throw new AssertionError(error);}}
    private static void put(JSONObject object,String name,Object value){try{object.put(name,value);}catch(Exception error){throw new AssertionError(error);}}
    private static JSONObject json(String name,Object value){JSONObject object=new JSONObject();put(object,name,value);return object;}
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private static void ready(ActivityScenario<HubActivity> app){await(()->{AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->done.set(!requests(activity).busy()));return done.get();});}
    private static void await(java.util.function.BooleanSupplier done){long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(done.getAsBoolean()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(20);}fail("Callback/request did not finish");}
    private static java.util.List<View> children(View root){java.util.List<View> result=new java.util.ArrayList<>();result.add(root);if(root instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)root).getChildCount();i++)result.addAll(children(((android.view.ViewGroup)root).getChildAt(i)));return result;}

    private static final class Fixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        final Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        final String originalOwner=repeat('A'),kakaoOwner=repeat('K'),freshAnonymous=repeat('N'),base;
        final AtomicBoolean running=new AtomicBoolean(true),signedIn=new AtomicBoolean(),holdLogin=new AtomicBoolean();
        final AtomicInteger logins=new AtomicInteger(),deletes=new AtomicInteger();
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicReference<JSONObject> deletionBody=new AtomicReference<>();
        final CountDownLatch loginArrived=new CountDownLatch(1),releaseLogin=new CountDownLatch(1);
        final ServerSocket server;final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();final java.util.concurrent.Future<?> task;
        Fixture() throws Exception {
            if(android.os.Build.VERSION.SDK_INT>=37)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
            server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(500);base="http://127.0.0.1:"+server.getLocalPort();
            assertTrue(settings.edit().putString("server",base).putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).commit());assertTrue(credentials.edit().putString(base,originalOwner).commit());
            task=worker.submit(()->{
                while(running.get())try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());String[] request=line(input).split(" ");String header;int length=0;String owner="";
                    while(!(header=line(input)).isEmpty()){if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(header.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:"))owner=header.substring(header.indexOf("Bearer ")+7).trim();}
                    assertTrue(length>=0&&length<10000);byte[] payload=new byte[length];input.readFully(payload);JSONObject body=length==0?new JSONObject():new JSONObject(new String(payload,StandardCharsets.UTF_8));int code=200;String response;
                    switch(request[1]){
                        case "/health":response="{\"status\":\"ok\"}";break;
                        case "/sessions":response=json("token",freshAnonymous).toString();break;
                        case "/accounts/kakao/config":response="{\"configured\":false,\"setupMessage\":\"Kakao 서버 앱 ID 설정이 필요해요.\"}";break;
                        case "/accounts/kakao":
                            assertEquals("POST",request[0]);logins.incrementAndGet();loginArrived.countDown();if(holdLogin.get())assertTrue(releaseLogin.await(5,TimeUnit.SECONDS));
                            assertEquals(originalOwner,owner);assertTrue(body.has("accessToken"));
                            if(body.optString("accessToken").equals("fixture-rejected-token")){code=401;response="{\"message\":\"카카오 인증을 확인하지 못했어요.\"}";}
                            else{assertEquals("fixture-success-token",body.getString("accessToken"));signedIn.set(true);response="{\"token\":\""+kakaoOwner+"\",\"userId\":\"fixture-user\",\"name\":\"카카오 검사 사용자\",\"email\":\"\",\"authProvider\":\"kakao\"}";}break;
                        case "/accounts/me":
                            if(request[0].equals("DELETE")){assertEquals(kakaoOwner,owner);deletes.incrementAndGet();deletionBody.set(body);signedIn.set(false);response="{}";}
                            else if(signedIn.get()&&owner.equals(kakaoOwner))response="{\"userId\":\"fixture-user\",\"name\":\"카카오 검사 사용자\",\"email\":\"\",\"authProvider\":\"kakao\"}";
                            else{code=404;response="{\"message\":\"익명 세션\"}";}break;
                        case "/connections/config":response="{\"providers\":[]}";break;
                        case "/connections":response="[]";break;
                        default:throw new AssertionError("Unexpected fixture route: "+request[0]+" "+request[1]);
                    }
                    byte[] bytes=response.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+code+" OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                }catch(SocketTimeoutException timeout){}catch(Throwable error){if(running.get())failure.compareAndSet(null,error);return;}
            });
        }
        ActivityScenario<HubActivity> launch(){return ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","account"));}
        @Override public void close() throws Exception {releaseLogin.countDown();running.set(false);server.close();task.get(2,TimeUnit.SECONDS);worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);assertNull("Fixture server failure",failure.get());}
        private static String repeat(char value){return String.join("",java.util.Collections.nCopies(43,String.valueOf(value)));}
        private static String line(DataInputStream input) throws Exception {StringBuilder line=new StringBuilder();int c;while((c=input.read())!=-1&&c!='\n')if(c!='\r')line.append((char)c);if(c==-1)throw new java.io.EOFException();return line.toString();}
        private static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object value=entry.getValue();if(value instanceof String)edit.putString(entry.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Integer)edit.putInt(entry.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(entry.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(entry.getKey(),(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(entry.getKey(),(java.util.Set<String>)value);}assertTrue(edit.commit());}
    }
}
