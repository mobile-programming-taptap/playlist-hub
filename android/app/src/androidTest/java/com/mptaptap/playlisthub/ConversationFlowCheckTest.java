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
    @Test public void pendingSnapshotRetainsRefinementAndRejectsAnotherOwner() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        List<Track> tracks=TrackJson.list(new JSONArray(ApiClient.read(context.getAssets().open("tracks.json"))));
        JSONObject snapshot=new JSONObject().put("prompt","팝으로 바꿔줘").put("history",new JSONArray(List.of("잔잔한 한국어 피아노 음악")))
            .put("keptTrackIds",new JSONArray(List.of(tracks.get(0).id))).put("excludedTrackIds",new JSONArray())
            .put("previousConditions",TrackJson.conditions(Recommender.parse("잔잔한 한국어 피아노 음악")));
        RecommendationRequest restored=new RecommendationRequest();AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{restored.restore(snapshot,null);assertTrue(restored.retry(null));}catch(Throwable e){failure.set(e);}});
        long end=SystemClock.elapsedRealtime()+10000;AtomicReference<RecommendationRequest.State> result=new AtomicReference<>();
        do{instrumentation.runOnMainSync(()->result.set(restored.current()));if(!result.get().loading)break;SystemClock.sleep(20);}while(SystemClock.elapsedRealtime()<end);
        if(failure.get()!=null)throw new AssertionError(failure.get());assertFalse(result.get().loading);assertNull(result.get().result);
        assertNotNull(result.get().error);assertTrue(result.get().error.getMessage().contains("AI 추천을 연결"));
        assertEquals("A failed connection must preserve the exact retry request",snapshot.toString(),restored.snapshot().toString());
        SharedPreferences credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);String base="http://127.0.0.1:1",previous=credentials.getString(base,null);
        try {
            credentials.edit().putString(base,String.join("",java.util.Collections.nCopies(43,"A"))).commit();ApiClient stale=new ApiClient(context,base);
            instrumentation.runOnMainSync(()->{try{restored.consume(restored.current());restored.restore(snapshot,stale);}catch(Throwable e){failure.set(e);}});
            credentials.edit().putString(base,String.join("",java.util.Collections.nCopies(43,"B"))).commit();
            instrumentation.runOnMainSync(()->{assertFalse(restored.retry(stale));restored.discard();assertNull(restored.snapshot());restored.onCleared();});
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
