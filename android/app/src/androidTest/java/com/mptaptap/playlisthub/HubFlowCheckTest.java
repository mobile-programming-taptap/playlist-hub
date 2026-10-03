package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses only the dedicated disposable test server passed by the runner. */
@RunWith(AndroidJUnit4.class)
public class HubFlowCheckTest {
    @Test public void accountPreservesAnonymousListsAndTimelineSavesSeparateSnapshots() throws Exception {
        String base=InstrumentationRegistry.getArguments().getString("fullServer","");assumeTrue("A dedicated test server is required",!base.isEmpty());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        String suffix=UUID.randomUUID().toString(),email="check-"+suffix+"@example.invalid",password="native-check-only-"+suffix,title="영상 검사 "+suffix.substring(0,8);
        settings.edit().putString("server",base).commit();credentials.edit().remove(base).commit();ApiClient client=new ApiClient(context,base);client.connect();
        String anonymous=client.identity();String list=client.object("POST","/playlists",new JSONObject().put("title","익명 목록 "+suffix).put("id",UUID.randomUUID().toString())).getString("id");
        String track=client.array("/catalogue").getJSONObject(0).getString("id");client.object("POST","/playlists/"+list+"/items",new JSONObject().put("trackId",track));
        boolean registered=false;
        try {
            try(ActivityScenario<HubActivity> app=launch(context,"account")) {
                ready(app);assertEquals("Reading an anonymous account screen must preserve its owner",anonymous,client.identity());
                onView(withHint("이메일")).perform(scrollTo(),replaceText(email),closeSoftKeyboard());
                onView(withHint("비밀번호 (15자 이상)")).perform(scrollTo(),replaceText(password),closeSoftKeyboard());
                onView(withHint("이름 (회원가입)")).perform(scrollTo(),replaceText("기기 검사"),closeSoftKeyboard());
                onView(withText("회원가입")).perform(scrollTo(),click());ready(app);
                registered=true;assertNotEquals(anonymous,client.identity());client.connect();
                assertEquals(1,client.object("GET","/playlists/"+list,null).getJSONArray("items").length());
                onView(withText("로그아웃")).perform(scrollTo(),click());ready(app);client.connect();
                try{client.object("GET","/playlists/"+list,null);fail("A logged-out owner cannot read the account's lists");}catch(ApiClient.ApiError expected){assertEquals(404,expected.statusCode);}
                onView(withHint("이메일")).perform(scrollTo(),replaceText(email),closeSoftKeyboard());
                onView(withHint("비밀번호 (15자 이상)")).perform(scrollTo(),replaceText(password),closeSoftKeyboard());
                onView(withText("로그인")).perform(scrollTo(),click());ready(app);client.connect();
                assertEquals(email,client.object("GET","/accounts/me",null).getString("email"));
                assertEquals(1,client.object("GET","/playlists/"+list,null).getJSONArray("items").length());
            }
            try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                ready(app);onView(withHint("YouTube 영상 주소")).perform(scrollTo(),replaceText("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),closeSoftKeyboard());
                onView(withHint("영상 설명의 시간표 붙여넣기 (선택)")).perform(scrollTo(),replaceText("00:00 Sample artist - Sample song\n01:00 Sample artist - Sample song"),closeSoftKeyboard());
                app.recreate();ready(app);focused(app);
                onView(withHint("YouTube 영상 주소")).check(matches(withText("https://www.youtube.com/watch?v=dQw4w9WgXcQ")));
                onView(withText("영상 시간표 확인")).perform(closeSoftKeyboard(),scrollTo(),click());ready(app);
                onView(withHint("새 목록 이름")).perform(scrollTo(),replaceText(title),closeSoftKeyboard());
                onView(allOf(withHint("곡명"),hasSibling(withText("1. 00:00")))).perform(scrollTo(),replaceText("Reviewed Sample"),closeSoftKeyboard());
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 00:00")))).perform(scrollTo(),replaceText("10"),closeSoftKeyboard());
                onView(withText("선택한 곡을 앱 목록에 저장")).perform(scrollTo(),click());ready(app);
            }
            JSONArray lists=client.array("/playlists");String converted=null;
            for(int i=0;i<lists.length();i++)if(lists.getJSONObject(i).getString("title").equals(title))converted=lists.getJSONObject(i).getString("id");
            assertNotNull("The edited timeline must create a real app playlist",converted);
            JSONArray items=client.object("GET","/playlists/"+converted,null).getJSONArray("items");assertEquals(2,items.length());
            assertTrue(items.getJSONObject(0).isNull("trackId"));assertTrue(items.getJSONObject(1).isNull("trackId"));
            assertNotEquals(items.getJSONObject(0).getString("id"),items.getJSONObject(1).getString("id"));
            assertEquals("Reviewed Sample",items.getJSONObject(0).getJSONObject("track").getString("title"));
            try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                ready(app);onView(withText("이전 변환 · 가져오기 · 전송 기록")).perform(scrollTo(),click());ready(app);
                onView(withText("목록 검토 · 변경 확인")).perform(scrollTo(),click());ready(app);
                onView(allOf(withHint("곡명"),hasSibling(withText("1. 00:00")))).check(matches(withText("Reviewed Sample")));
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 00:00")))).check(matches(withText("10")));
                onView(withHint("새 목록 이름")).perform(scrollTo(),replaceText("미제출 기록 초안"),closeSoftKeyboard());app.recreate();ready(app);
                onView(withHint("새 목록 이름")).check(matches(withText("미제출 기록 초안")));
            }
            assertEquals(40,client.array("/catalogue").length());
            assertEquals(1,client.array("/me/saved-tracks").length());
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                readyMain(main);onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());readyMain(main);
                onView(withText(title+"\n2곡")).perform(scrollTo(),click());readyMain(main);
                onView(withText("1. Reviewed Sample\nSample artist · 가져온 곡")).check(matches(isDisplayed()));
                onView(withText("1. Reviewed Sample\nSample artist · 가져온 곡")).perform(scrollTo(),click());
                onView(withText("곡 정보 수정")).perform(click());
                onView(withHint("곡명")).perform(replaceText("Edited Sample"),closeSoftKeyboard());
                onView(withHint("영상 시작 위치 · 초")).perform(replaceText("999999"),closeSoftKeyboard());
                onView(withText("저장")).perform(click());readyMain(main);
                onView(withHint("곡명")).check(matches(withText("Edited Sample")));
                onView(withHint("영상 시작 위치 · 초")).perform(replaceText("20"),closeSoftKeyboard());
                onView(withText("저장")).perform(click());readyMain(main);
                assertEquals("Edited Sample",client.object("GET","/playlists/"+converted,null).getJSONArray("items").getJSONObject(0).getJSONObject("track").getString("title"));
                onView(withText("공개 설정 · 공유 링크")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            String shareId;
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","community").putExtra("playlistId",converted))) {
                ready(app);onView(withText("커뮤니티에 공개")).perform(scrollTo(),click());onView(withText("공개")).perform(click());ready(app);
                JSONObject publication=client.object("GET","/playlists/"+converted+"/publication",null);assertTrue(publication.getBoolean("public"));shareId=publication.getString("shareId");
                onView(withText("공개 링크 공유")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            try(ActivityScenario<HubActivity> app=launch(context,"community")) {
                ready(app);onView(withHint("공개 목록 검색")).perform(scrollTo(),replaceText(title),closeSoftKeyboard());onView(withText("검색")).perform(scrollTo(),click());ready(app);
                onView(withText("목록 열기")).perform(scrollTo(),click());ready(app);
                onView(withText("즐겨찾기")).perform(scrollTo(),click());ready(app);
                assertTrue(client.object("GET","/public/playlists/"+shareId,null).getBoolean("favourited"));
                onView(withText("즐겨찾기 해제")).check(matches(isDisplayed()));
            }
            String accountOwner=client.identity();
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                readyMain(main);credentials.edit().remove(base).commit();
                onView(withId(R.id.settings)).perform(click());
                onView(withText("서버에 연결")).perform(closeSoftKeyboard(),scrollTo(),click());readyMain(main);
                onView(withHint("https://서버주소")).check(doesNotExist());
                assertFalse(client.identity().isEmpty());assertNotEquals(accountOwner,client.identity());
            } finally {credentials.edit().putString(base,accountOwner).commit();client.connect();}
            HubRequest stale=new HubRequest();var instrumentation=InstrumentationRegistry.getInstrumentation();
            instrumentation.runOnMainSync(()->stale.send(context,base,"owner-check","POST","/playlists",new JSONObject()));
            waitUntil(()->{AtomicBoolean idle=new AtomicBoolean();instrumentation.runOnMainSync(()->idle.set(!stale.busy()));return idle.get();});
            String owner=client.identity();credentials.edit().putString(base,String.join("",java.util.Collections.nCopies(43,"B"))).commit();
            try{instrumentation.runOnMainSync(()->{stale.retry(context,base);assertEquals("identity",stale.current().route);assertFalse(stale.busy());stale.onCleared();});}
            finally{credentials.edit().putString(base,owner).commit();client.connect();}
        } catch(Exception|AssertionError failure) {
            android.graphics.Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(screen!=null)try(var output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"hub-check-failure.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}catch(Exception ignored){}
            throw failure;
        } finally {
            try{if(registered)client.request("DELETE","/accounts/me",new JSONObject().put("password",password));else client.request("DELETE","/playlists/"+list,null);}catch(Exception ignored){}
            restore(settings,oldSettings);restore(credentials,oldCredentials);
        }
    }
    private static ActivityScenario<HubActivity> launch(Context context,String mode){return ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode",mode));}
    private static void ready(ActivityScenario<HubActivity> app){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->{try{var f=HubActivity.class.getDeclaredField("requests");f.setAccessible(true);ready.set(!((HubRequest)f.get(a)).busy());}catch(Exception e){throw new AssertionError(e);}});return ready.get();});}
    private static void focused(ActivityScenario<HubActivity> app){waitUntil(()->{AtomicBoolean focused=new AtomicBoolean();app.onActivity(a->focused.set(a.hasWindowFocus()));return focused.get();});}
    private static void readyMain(ActivityScenario<MainActivity> app){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->{try{var f=MainActivity.class.getDeclaredField("busy");f.setAccessible(true);ready.set(!f.getBoolean(a));}catch(Exception e){throw new AssertionError(e);}});return ready.get();});}
    private static void waitUntil(java.util.function.BooleanSupplier condition){long until=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<until){if(condition.getAsBoolean()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}throw new AssertionError("The app request did not finish.");}
    private static void restore(SharedPreferences prefs,Map<String,?> previous){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> row:previous.entrySet()){
        Object value=row.getValue();if(value instanceof String)edit.putString(row.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)value);
        else if(value instanceof Integer)edit.putInt(row.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(row.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(row.getKey(),(Float)value);
        else if(value instanceof java.util.Set)edit.putStringSet(row.getKey(),(java.util.Set<String>)value);
    }edit.commit();}
}
