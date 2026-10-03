package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import com.mptaptap.core.Recommender;
import com.mptaptap.core.Track;
import java.util.List;
import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ConversationFlowCheckTest {
    @Test public void refineKeepsDraftSelectedSongAndDirectHistory() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);Map<String,?> old=settings.getAll();
        LocalStore data=new LocalStore(context);Map<String,Integer> feedback=data.feedback();
        settings.edit().remove("server").remove("prompt").remove("conversation").remove("conversationOwner").commit();data.clearFeedback();
        try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
            ready(app);onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
            onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
            AtomicReference<String> title=new AtomicReference<>();
            onView(allOf(withId(R.id.track_title),isDisplayed())).perform(scrollTo()).check((v,e)->title.set(((TextView)v).getText().toString()));
            onView(withId(R.id.refine_request)).perform(scrollTo(),click());
            onView(withHint("예: 더 신나게, 피아노는 제외해줘")).perform(replaceText("영어 팝으로 바꿔줘"),closeSoftKeyboard());
            onView(withText("유지 · "+title.get())).perform(scrollTo(),click());
            app.recreate();ready(app);refinementReady(app);
            onView(withHint("예: 더 신나게, 피아노는 제외해줘")).check(matches(withText("영어 팝으로 바꿔줘")));
            onView(withText("유지 · "+title.get())).check(matches(isChecked()));
            onView(withText("추천 바꾸기")).perform(click());ready(app);
            onView(allOf(withId(R.id.track_title),isDisplayed())).perform(scrollTo()).check(matches(withText(title.get())));
            onView(withId(R.id.condition_summary)).check(matches(withText(containsString("영어"))));
            onView(withId(R.id.condition_summary)).check(matches(withText(containsString("팝"))));
            assertTrue(data.feedback().isEmpty());JSONArray history=new JSONArray(settings.getString("conversation","[]"));
            assertEquals(2,history.length());assertEquals("영어 팝으로 바꿔줘",history.getString(1));
            app.recreate();ready(app);
            onView(allOf(withId(R.id.track_title),isDisplayed())).perform(scrollTo()).check(matches(withText(title.get())));
        } finally {
            data.clearFeedback();for(Map.Entry<String,Integer> row:feedback.entrySet())data.react(row.getKey(),row.getValue());data.close();
            SharedPreferences.Editor restore=settings.edit().clear();for(Map.Entry<String,?> row:old.entrySet()){
                Object v=row.getValue();if(v instanceof String)restore.putString(row.getKey(),(String)v);else if(v instanceof Boolean)restore.putBoolean(row.getKey(),(Boolean)v);
                else if(v instanceof Integer)restore.putInt(row.getKey(),(Integer)v);else if(v instanceof Long)restore.putLong(row.getKey(),(Long)v);else if(v instanceof Float)restore.putFloat(row.getKey(),(Float)v);
                else if(v instanceof java.util.Set)restore.putStringSet(row.getKey(),(java.util.Set<String>)v);
            }restore.commit();
        }
    }
    @Test public void pendingSnapshotRetainsRefinementAndRejectsAnotherOwner() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        List<Track> tracks=TrackJson.list(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json"))));
        JSONObject snapshot=new JSONObject().put("prompt","팝으로 바꿔줘").put("history",new JSONArray(List.of("잔잔한 한국어 피아노 음악")))
            .put("keptTrackIds",new JSONArray(List.of(tracks.get(0).id))).put("excludedTrackIds",new JSONArray())
            .put("previousConditions",TrackJson.conditions(Recommender.parse("잔잔한 한국어 피아노 음악")));
        RecommendationRequest restored=new RecommendationRequest();AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{restored.restore(snapshot,null);assertTrue(restored.retry(null,tracks,new HashMap<>()));}catch(Throwable e){failure.set(e);}});
        long end=SystemClock.elapsedRealtime()+10000;AtomicReference<RecommendationRequest.State> result=new AtomicReference<>();
        do{instrumentation.runOnMainSync(()->result.set(restored.current()));if(!result.get().loading)break;SystemClock.sleep(20);}while(SystemClock.elapsedRealtime()<end);
        if(failure.get()!=null)throw new AssertionError(failure.get());assertFalse(result.get().loading);assertNull(result.get().error);
        Recommender.Conditions conditions=(Recommender.Conditions)result.get().result[3];assertEquals("ko",conditions.language);assertTrue(conditions.tags.contains("pop"));assertFalse(conditions.tags.contains("piano"));
        assertEquals(tracks.get(0).id,((List<Recommender.Pick>)result.get().result[0]).get(0).track.id);
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);String base="http://127.0.0.1:1",previous=credentials.getString(base,null);
        try {
            credentials.edit().putString(base,String.join("",java.util.Collections.nCopies(43,"A"))).commit();ApiClient stale=new ApiClient(context,base);
            instrumentation.runOnMainSync(()->{try{restored.consume(restored.current());restored.restore(snapshot,stale);}catch(Throwable e){failure.set(e);}});
            credentials.edit().putString(base,String.join("",java.util.Collections.nCopies(43,"B"))).commit();
            instrumentation.runOnMainSync(()->{assertFalse(restored.retry(stale,tracks,new HashMap<>()));restored.discard();assertNull(restored.snapshot());restored.onCleared();});
            try{stale.request("POST","/recommendations",snapshot);fail("A stale request must stop before connecting to the network");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("계정"));}
            if(failure.get()!=null)throw new AssertionError(failure.get());
        } finally {SharedPreferences.Editor restore=credentials.edit();if(previous==null)restore.remove(base);else restore.putString(base,previous);restore.commit();}
    }
    private static void refinementReady(ActivityScenario<MainActivity> app) {
        long deadline=SystemClock.elapsedRealtime()+15000;AtomicBoolean done=new AtomicBoolean();
        while(SystemClock.elapsedRealtime()<deadline){app.onActivity(a->{try{var field=MainActivity.class.getDeclaredField("refinementPrompt");field.setAccessible(true);
            android.view.View input=(android.view.View)field.get(a);done.set(input!=null&&input.hasWindowFocus());}catch(Exception e){throw new AssertionError(e);}});
            if(done.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}
        throw new AssertionError("The restored refinement dialog did not receive focus.");
    }
    private static void ready(ActivityScenario<MainActivity> app) {
        long deadline=SystemClock.elapsedRealtime()+15000;AtomicBoolean done=new AtomicBoolean();
        while(SystemClock.elapsedRealtime()<deadline){app.onActivity(a->{try{var field=MainActivity.class.getDeclaredField("busy");field.setAccessible(true);done.set(!field.getBoolean(a));}catch(Exception e){throw new AssertionError(e);}});
            if(done.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}
        throw new AssertionError("The recommendation did not finish.");
    }
}
