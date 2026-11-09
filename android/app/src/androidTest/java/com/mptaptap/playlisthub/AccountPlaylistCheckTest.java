package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.lifecycle.Lifecycle;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

/** Exercises account playlist selection with disposable HTTP data, never real OAuth. */
@RunWith(AndroidJUnit4.class)
public class AccountPlaylistCheckTest {
    @Test public void pagedSelectionsSurviveRotationAndRetryOnlyTheFailedImport() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        String titleA="공부 모음",titleB="비 오는 밤",titleC="선택하지 않은 목록",urlA="https://www.youtube.com/playlist?list=PLaaaaaaaaaa1",urlB="https://www.youtube.com/playlist?list=PLbbbbbbbbbb2";
        AtomicBoolean running=new AtomicBoolean(true),connected=new AtomicBoolean();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        AtomicInteger firstImports=new AtomicInteger(),secondImports=new AtomicInteger(),secondPages=new AtomicInteger(),libraryReads=new AtomicInteger(),accountReads=new AtomicInteger(),authorizations=new AtomicInteger();AtomicReference<String> firstId=new AtomicReference<>(),secondId=new AtomicReference<>();
        CountDownLatch firstReceived=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort(),token=String.join("",java.util.Collections.nCopies(43,"Q"));
            var replies=worker.submit(()->{
                Map<String,JSONObject> documents=new HashMap<>();
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());String[] request=httpLine(input).split(" ");
                    String header;int length=0;boolean authenticated=false;
                    while(!(header=httpLine(input)).isEmpty()) {
                        String lower=header.toLowerCase(java.util.Locale.ROOT);if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        if(lower.startsWith("authorization:")&&header.endsWith("Bearer "+token))authenticated=true;
                    }
                    assertTrue("Keep the selected disposable session",authenticated);assertTrue("Bound the fixture request",length>=0&&length<=100000);
                    byte[] bodyBytes=new byte[length];input.readFully(bodyBytes);JSONObject body=length==0?new JSONObject():new JSONObject(new String(bodyBytes,StandardCharsets.UTF_8));
                    Uri address=Uri.parse("http://fixture"+request[1]);String path=address.getPath();Object response;int code=200;
                    if(path.equals("/health")){assertEquals("GET",request[0]);response=new JSONObject().put("status","ok");}
                    else if(path.equals("/connections/youtube/start")){assertEquals("POST",request[0]);assertEquals(1,authorizations.incrementAndGet());response=new JSONObject().put("authorizationUrl","https://accounts.google.com/o/oauth2/v2/auth?state=native-fixture");}
                    else if(path.equals("/accounts/me")){assertEquals("GET",request[0]);accountReads.incrementAndGet();response=new JSONObject().put("name","목록 검사").put("email","playlist-check@example.invalid");}
                    else if(path.equals("/connections/config")){assertEquals("GET",request[0]);response=new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("provider","youtube").put("configured",true)).put(new JSONObject().put("provider","spotify").put("configured",false)));}
                    else if(path.equals("/connections")){assertEquals("GET",request[0]);response=connected.get()?new JSONArray().put(new JSONObject().put("provider","youtube").put("connected",true)):new JSONArray();}
                    else if(path.equals("/connections/youtube/playlists")) {
                        assertEquals("GET",request[0]);libraryReads.incrementAndGet();String cursor=address.getQueryParameter("cursor");JSONArray rows=new JSONArray();
                        if(cursor==null||cursor.isEmpty()) {
                            rows.put(new JSONObject().put("id","PLaaaaaaaaaa1").put("title",titleA).put("itemCount",1).put("privacyStatus","private").put("sourceUrl",urlA));
                            rows.put(new JSONObject().put("id","PLcccccccccc3").put("title",titleC).put("itemCount",1).put("privacyStatus","unlisted").put("sourceUrl","https://www.youtube.com/playlist?list=PLcccccccccc3"));
                        } else {assertEquals("page2",cursor);secondPages.incrementAndGet();rows.put(new JSONObject().put("id","PLbbbbbbbbbb2").put("title",titleB).put("itemCount",1).put("privacyStatus","private").put("sourceUrl",urlB));}
                        if(connected.get())response=new JSONObject().put("provider","youtube").put("items",rows).put("nextCursor",cursor==null||cursor.isEmpty()?"page2":"");
                        else{code=409;response=new JSONObject().put("message","YouTube Music 계정을 연결해 주세요.");}
                    } else if(path.equals("/imports/playlists")) {
                        assertEquals("POST",request[0]);assertEquals("youtube",body.getString("provider"));String source=body.getString("url"),id=body.getString("id");assertEquals(id,UUID.fromString(id).toString());
                        boolean first=source.equals(urlA);assertTrue("Only checked playlists may be imported",first||source.equals(urlB));assertEquals(first?titleA:titleB,body.getString("title"));
                        int attempt;
                        if(first){attempt=firstImports.incrementAndGet();assertEquals("A successful import must not be resent",1,attempt);firstId.set(id);firstReceived.countDown();assertTrue("Release the first queued import",releaseFirst.await(15,TimeUnit.SECONDS));}
                        else{attempt=secondImports.incrementAndGet();assertTrue(attempt<=2);if(attempt==1){secondId.set(id);assertNotEquals(firstId.get(),id);}else assertEquals("Retry the failed playlist with its original ID",secondId.get(),id);}
                        if(!first&&attempt==1){code=503;response=new JSONObject().put("message","두 번째 목록 검사 오류");}
                        else {
                            String video=first?"aaaaaaaaaa1":"bbbbbbbbbb2",song=first?"가져온 공부 곡":"가져온 비 오는 곡";
                            JSONObject track=new JSONObject().put("platform","youtube").put("externalId",video).put("title",song).put("artist","검사 가수").put("sourceUrl","https://www.youtube.com/watch?v="+video).put("external",true).put("recommendationTrackId",JSONObject.NULL);
                            JSONArray items=new JSONArray().put(new JSONObject().put("sourceIndex",0).put("track",track).put("recommendationTrackId",JSONObject.NULL));
                            JSONObject document=new JSONObject().put("id",id).put("provider","youtube").put("externalPlaylistId",first?"PLaaaaaaaaaa1":"PLbbbbbbbbbb2").put("title",first?titleA:titleB).put("sourceUrl",source).put("items",items).put("reviewedItems",items).put("localPlaylistId",id).put("saved",true).put("notice","앱 목록에 저장했어요.");
                            documents.put(id,document);response=document;
                        }
                    } else if(path.startsWith("/imports/playlists/")) {assertEquals("GET",request[0]);response=documents.get(path.substring("/imports/playlists/".length()));assertNotNull("Read a successfully imported snapshot",response);}
                    else throw new AssertionError("Unexpected test request: "+request[0]+" "+request[1]);
                    byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Service Unavailable")+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                }catch(SocketTimeoutException timeout){
                    // Wait for the next explicit user selection or retry.
                }catch(Throwable error){if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                assertTrue(settings.edit().putString("server",base).commit());assertTrue(credentials.edit().putString(base,token).commit());
                try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","create"))) {
                    ready(app);onView(withText("내 플레이리스트 선택")).perform(closeSoftKeyboard(),scrollTo(),click());ready(app);
                    onView(withText("내 플레이리스트")).check(matches(isDisplayed()));
                    onView(withText("YouTube Music 계정을 연결해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));assertEquals(0,firstImports.get());assertEquals(0,secondImports.get());
                    IntentFilter browser=new IntentFilter(Intent.ACTION_VIEW);browser.addDataScheme("https");browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    var blocked=instrumentation.addMonitor(browser,new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true);
                    try {
                        onView(withText("YouTube Music 계정 연결")).perform(scrollTo(),click());ready(app);
                        assertNull("Test server failure",serverFailure.get());assertEquals(1,authorizations.get());assertEquals("Keep OAuth in the fixture instead of opening a real browser",1,blocked.getHits());
                        // Returning from the browser also refreshes connections without a deep link.
                        app.moveToState(Lifecycle.State.CREATED);connected.set(true);app.recreate();app.moveToState(Lifecycle.State.RESUMED);
                        waitUntil(()->accountReads.get()>0&&libraryReads.get()>1);ready(app);focused(app);
                        onView(withText("내 플레이리스트")).perform(closeSoftKeyboard()).check(matches(isDisplayed()));
                        assertNull("Test server failure",serverFailure.get());assertEquals(1,authorizations.get());assertEquals(token,credentials.getString(base,""));
                    } finally {instrumentation.removeMonitor(blocked);}
                    onView(withContentDescription("선택: "+titleA)).perform(scrollTo(),click());
                    onView(withText("다음 목록 불러오기")).perform(scrollTo(),click());ready(app);
                    assertTrue(secondPages.get()>0);onView(withContentDescription("선택: "+titleB)).perform(scrollTo(),click());app.recreate();ready(app);focused(app);
                    onView(withContentDescription("선택: "+titleA)).check(matches(isChecked()));onView(withContentDescription("선택: "+titleB)).check(matches(isChecked()));onView(withContentDescription("선택: "+titleC)).check(matches(not(isChecked())));
                    onView(withText("선택한 목록 가져오기")).perform(closeSoftKeyboard(),scrollTo(),click());assertTrue(firstReceived.await(5,TimeUnit.SECONDS));
                    app.recreate();focused(app);onView(isAssignableFrom(LinearProgressIndicator.class)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
                    assertEquals(1,firstImports.get());assertEquals(0,secondImports.get());releaseFirst.countDown();waitUntil(()->secondImports.get()==1);ready(app);
                    assertNull("Test server failure",serverFailure.get());onView(withText("가져오기 결과")).perform(closeSoftKeyboard(),scrollTo()).check(matches(isDisplayed()));
                    onView(withText("2개 중 1개 저장 완료")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withText(containsString("두 번째 목록 검사 오류"))).perform(scrollTo()).check(matches(isDisplayed()));
                    app.recreate();ready(app);focused(app);assertEquals(1,firstImports.get());assertEquals(1,secondImports.get());
                    onView(withText("남은 목록 다시 가져오기")).perform(closeSoftKeyboard(),scrollTo(),click());waitUntil(()->secondImports.get()==2);ready(app);
                    assertNull("Test server failure",serverFailure.get());assertEquals(1,firstImports.get());assertEquals(2,secondImports.get());
                    onView(withText("2개 중 2개 저장 완료")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withText("남은 목록 다시 가져오기")).check(doesNotExist());
                    onView(allOf(withText("가져온 곡 확인"),hasSibling(withText(titleA)))).perform(scrollTo(),click());ready(app);
                    onView(withHint("곡명")).check(matches(withText("가져온 공부 곡")));app.recreate();ready(app);focused(app);onView(withHint("곡명")).check(matches(withText("가져온 공부 곡")));
                    onView(withContentDescription("돌아가기")).perform(closeSoftKeyboard(),click());ready(app);onView(withText("내 플레이리스트 선택")).perform(scrollTo(),click());ready(app);
                    onView(withContentDescription("선택: "+titleA)).check(matches(not(isChecked())));onView(withText("다음 목록 불러오기")).perform(scrollTo(),click());ready(app);onView(withContentDescription("선택: "+titleB)).check(matches(not(isChecked())));
                    assertNull("Test server failure",serverFailure.get());assertEquals(1,firstImports.get());assertEquals(2,secondImports.get());assertEquals(token,credentials.getString(base,""));
                }
            } finally {running.set(false);releaseFirst.countDown();server.close();replies.get(2,TimeUnit.SECONDS);}
        } finally {running.set(false);releaseFirst.countDown();worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);}
    }

    private static String httpLine(DataInputStream input) throws java.io.IOException {
        StringBuilder line=new StringBuilder();int next;
        while((next=input.read())!=-1){if(next=='\n')return line.toString();if(next!='\r')line.append((char)next);if(line.length()>8192)throw new java.io.IOException("Test HTTP header is too long");}
        throw new java.io.EOFException("Incomplete test HTTP header");
    }
    private static ViewAction closeSoftKeyboard() {
        ViewAction close=androidx.test.espresso.action.ViewActions.closeSoftKeyboard();return new ViewAction(){
            public org.hamcrest.Matcher<View> getConstraints(){return close.getConstraints();}
            public String getDescription(){return "close the keyboard using the current OS visibility check";}
            public void perform(UiController ui,View view){ui.loopMainThreadUntilIdle();if(android.os.Build.VERSION.SDK_INT<30)close.perform(ui,view);else{var insets=androidx.core.view.ViewCompat.getRootWindowInsets(view);assertNotNull(insets);if(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))close.perform(ui,view);}ui.loopMainThreadUntilIdle();}
        };
    }
    private static void ready(ActivityScenario<HubActivity> app){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->{try{var field=HubActivity.class.getDeclaredField("requests");field.setAccessible(true);ready.set(!((HubRequest)field.get(a)).busy());}catch(Exception error){throw new AssertionError(error);}});return ready.get();});}
    private static void focused(ActivityScenario<HubActivity> app){waitUntil(()->{AtomicBoolean focused=new AtomicBoolean();app.onActivity(a->focused.set(a.hasWindowFocus()));return focused.get();});}
    private static void waitUntil(java.util.function.BooleanSupplier condition){long until=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<until){if(condition.getAsBoolean()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}throw new AssertionError("The app request did not finish.");}
    private static void restore(SharedPreferences prefs,Map<String,?> previous){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> row:previous.entrySet()){
        Object value=row.getValue();if(value instanceof String)edit.putString(row.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)value);
        else if(value instanceof Integer)edit.putInt(row.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(row.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(row.getKey(),(Float)value);
        else if(value instanceof java.util.Set)edit.putStringSet(row.getKey(),(java.util.Set<String>)value);
    }edit.commit();}
}
