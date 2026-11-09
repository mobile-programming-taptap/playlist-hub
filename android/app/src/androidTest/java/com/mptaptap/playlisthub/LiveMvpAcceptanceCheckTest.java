package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Recommender;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

/** Opt-in real-service acceptance across independent devices. All writes belong to one disposable account. */
@RunWith(AndroidJUnit4.class)
public class LiveMvpAcceptanceCheckTest {
    @Test public void actualRecommendationAndCrossDeviceAccountRoundTrip() throws Exception {
        var args=InstrumentationRegistry.getArguments();String run=args.getString("acceptanceRun"),phase=args.getString("acceptancePhase");
        Assume.assumeTrue(run!=null&&phase!=null);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",0),credentials=context.getSharedPreferences("server_credentials",0);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        String base="http://127.0.0.1:8080",email="mvp-"+run+"@example.invalid",password="Acceptance-"+run+"-Only",title="MVP "+run;
        try {
            assertTrue(settings.edit().putString("server",base).putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).remove("conversation").putString("prompt","").commit());
            assertTrue(credentials.edit().remove(base).commit());new ApiClient(context,base).connect();
            try(ActivityScenario<HubActivity> account=ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","account"))){
                ready(account);onView(withText("이메일로 로그인")).perform(scrollTo(),click());
                onView(withHint("이메일 주소")).perform(scrollTo(),replaceText(email));
                onView(withHint("비밀번호 (15자 이상)")).perform(scrollTo(),replaceText(password),closeSoftKeyboard());
                if(phase.equals("create")){onView(withHint("이름")).perform(scrollTo(),replaceText("MVP 검증"),closeSoftKeyboard());onView(withText("회원가입")).perform(scrollTo(),click());}
                else onView(withText("로그인")).perform(scrollTo(),click());
                ready(account);onView(withText("MVP 검증")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            ApiClient api=new ApiClient(context,base);
            if(phase.equals("create")) {
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                    ready(app);onView(withId(R.id.prompt)).perform(replaceText("신나는 제이팝 음악 추천해줘"),closeSoftKeyboard());
                    onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                    List<Recommender.Pick> first=picks(app);assertTrue("Real AI must produce seven or more songs",first.size()>=7);
                    String kept=first.get(0).track.id,excluded=first.get(1).track.id;
                    onView(withId(R.id.pager)).perform(swipeLeft());
                    assertEquals("A swipe alone must not rate music",0,api.object("GET","/me/feedback",null).length());
                    onView(withId(R.id.dislike)).perform(click());ready(app);
                    onView(withId(R.id.previous)).perform(click());onView(withId(R.id.like)).perform(click());ready(app);
                    JSONObject feedback=api.object("GET","/me/feedback",null);assertEquals(1,feedback.getInt(kept));assertEquals(-1,feedback.getInt(excluded));
                    onView(withId(R.id.refine_request)).perform(scrollTo(),click());
                    onView(withHint("예: 더 신나게, 피아노는 제외해줘")).perform(scrollTo(),replaceText("유지한 곡은 남기고 조금 더 차분하게 추천해줘"),closeSoftKeyboard());
                    onView(withText("유지 · "+first.get(0).track.title)).perform(scrollTo(),click());
                    app.recreate();onView(withText("추천 바꾸기")).inRoot(isDialog()).perform(click());ready(app);
                    List<Recommender.Pick> refined=picks(app);assertTrue(refined.stream().anyMatch(p->p.track.id.equals(kept)));assertFalse(refined.stream().anyMatch(p->p.track.id.equals(excluded)));
                    onView(withId(R.id.save_results)).perform(scrollTo(),click());ready(app);
                    onView(withText("+ 새 플레이리스트")).inRoot(isDialog()).perform(click());
                    onView(withHint("예: 비 오는 날의 음악")).perform(replaceText(title),closeSoftKeyboard());onView(withText("만들기")).inRoot(isDialog()).perform(click());ready(app);
                    JSONArray lists=api.array("/playlists");assertEquals(1,lists.length());assertEquals(title,lists.getJSONObject(0).getString("title"));assertEquals(refined.size(),lists.getJSONObject(0).getInt("size"));
                    app.recreate();ready(app);assertEquals(refined.size(),picks(app).size());
                    System.out.println("LIVE MVP create: real AI, swipe, feedback, refinement, save and rotation passed; tracks="+refined.size());
                }
            } else {
                JSONArray lists=api.array("/playlists");assertEquals(1,lists.length());JSONObject list=lists.getJSONObject(0);
                assertEquals(phase.equals("read")?title:title+" B",list.getString("title"));assertTrue(list.getInt("size")>=1);
                JSONObject feedback=api.object("GET","/me/feedback",null);assertEquals(2,feedback.length());assertTrue(feedback.toString().contains(":1"));assertTrue(feedback.toString().contains(":-1"));
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                    ready(app);onView(withId(R.id.nav_library)).perform(click());ready(app);
                    onView(withText(containsString(list.getString("title")))).perform(scrollTo(),click());ready(app);
                    onView(withText(list.getString("title"))).check(matches(isDisplayed()));
                    if(phase.equals("read")){
                        onView(withText("이름 변경")).perform(click());onView(withHint("예: 비 오는 날의 음악")).perform(replaceText(title+" B"),closeSoftKeyboard());onView(withText("변경")).inRoot(isDialog()).perform(click());ready(app);
                        app.recreate();ready(app);onView(withText(title+" B")).check(matches(isDisplayed()));
                    }
                    System.out.println("LIVE MVP "+phase+": independent login restored playlist and both reactions; shared rename verified");
                }
                if(phase.equals("verify")){api.request("DELETE","/accounts/me",new JSONObject().put("password",password));System.out.println("LIVE MVP disposable account deleted");}
            }
        } finally {restore(settings,oldSettings);restore(credentials,oldCredentials);}
    }
    @SuppressWarnings("unchecked") private static List<Recommender.Pick> picks(ActivityScenario<MainActivity> app){var result=new java.util.concurrent.atomic.AtomicReference<List<Recommender.Pick>>();app.onActivity(a->result.set(new java.util.ArrayList<>((List<Recommender.Pick>)get(a,"picks"))));return result.get();}
    private static Object get(Object object,String name){try{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception error){throw new AssertionError(error);}}
    private static <T extends android.app.Activity> void ready(ActivityScenario<T> app){long end=SystemClock.uptimeMillis()+60000;while(SystemClock.uptimeMillis()<end){AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->ready.set(a instanceof MainActivity?!((Boolean)get(a,"busy")):!((HubRequest)get(a,"requests")).busy()));if(ready.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(100);}fail("Live app did not finish its request");}
    private static void restore(SharedPreferences preferences,Map<String,?> values){var edit=preferences.edit().clear();for(var entry:values.entrySet()){Object v=entry.getValue();if(v instanceof String)edit.putString(entry.getKey(),(String)v);else if(v instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)v);else if(v instanceof Integer)edit.putInt(entry.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(entry.getKey(),(Long)v);else if(v instanceof Float)edit.putFloat(entry.getKey(),(Float)v);else if(v instanceof java.util.Set)edit.putStringSet(entry.getKey(),(java.util.Set<String>)v);}assertTrue(edit.commit());}
}
