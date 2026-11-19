package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.action.CoordinatesProvider;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Track;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

/** Guest-mode 내 목록: delete straight from the list with undo, and drag rows to reorder lists and songs. */
@RunWith(AndroidJUnit4.class)
public class LibraryManageCheckTest {
    @Test public void listsDeleteWithUndoAndReorderByDrag() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);Map<String,?> previous=settings.getAll();
        settings.edit().remove("server").remove("serviceConsent:"+TapTapApplication.defaultServer()).commit();
        LocalStore data=new LocalStore(context);List<String> created=new ArrayList<>();
        try {
            for(String title:Arrays.asList("검사 목록 A","검사 목록 B","검사 목록 C"))created.add(data.create(title).getString("id"));
            List<String> before=ids(data.playlists());List<String> order=new ArrayList<>(before);
            // Make our three lists the first three rows, newest first: C, B, A.
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(app);onView(withId(R.id.nav_library)).perform(click());ready(app);
                onView(withContentDescription("삭제: 검사 목록 B")).perform(scrollTo(),click());
                onView(withText("검사 목록 B")).check(matches(withEffectiveVisibility(Visibility.GONE)));
                SystemClock.sleep(3500);onView(withText("실행 취소")).check(matches(isDisplayed())).perform(click());
                onView(withText("검사 목록 B")).check(matches(isDisplayed()));
                SystemClock.sleep(4500);assertTrue("Undo must keep the list",ids(data.playlists()).contains(created.get(1)));
                onView(withContentDescription("삭제: 검사 목록 B")).perform(scrollTo(),click());
                waitUntil(()->{try{return !ids(data.playlists()).contains(created.get(1));}catch(Exception e){throw new AssertionError(e);}});
                ready(app);onView(withText("검사 목록 B")).check(doesNotExist());
                // Drag A (third of ours) above C (first of ours).
                onView(withContentDescription("순서 변경: 검사 목록 A")).perform(scrollTo(),dragTo("검사 목록 C"));
                waitUntil(()->{try{List<String> now=ids(data.playlists());return now.indexOf(created.get(0))<now.indexOf(created.get(2));}catch(Exception e){throw new AssertionError(e);}});
                ready(app);
                app.onActivity(a->{View box=a.findViewById(R.id.library);int[] first=new int[2],second=new int[2];findText(box,"검사 목록 A").getLocationOnScreen(first);findText(box,"검사 목록 C").getLocationOnScreen(second);assertTrue("A must now sit above C",first[1]<second[1]);});
            }
        } finally {
            for(String id:created)try{data.delete(id);}catch(Exception ignored){}
            SharedPreferences.Editor edit=settings.edit().clear();for(Map.Entry<String,?> row:previous.entrySet())if(row.getValue() instanceof String)edit.putString(row.getKey(),(String)row.getValue());else if(row.getValue() instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)row.getValue());edit.commit();
        }
    }

    @Test public void songsReorderByDrag() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);Map<String,?> previous=settings.getAll();
        settings.edit().remove("server").remove("serviceConsent:"+TapTapApplication.defaultServer()).commit();
        LocalStore data=new LocalStore(context);String id=data.create("드래그 곡 목록").getString("id");
        try {
            List<Track> tracks=new ArrayList<>();
            JSONArray catalogue=new JSONArray(ApiClient.read(context.getAssets().open("tracks.json")));
            for(int i=0;i<3;i++)tracks.add(TrackJson.read(catalogue.getJSONObject(i)));
            String first=tracks.get(0).title,second=tracks.get(1).title,third=tracks.get(2).title;
            data.saveAll(id,tracks);
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(app);onView(withId(R.id.nav_library)).perform(click());ready(app);
                onView(withText("드래그 곡 목록")).perform(scrollTo(),click());ready(app);
                onView(withContentDescription("순서 변경: "+third)).perform(scrollTo(),dragTo(first));
                waitUntil(()->{try{return songTitles(data,id).get(0).equals(third);}catch(Exception e){throw new AssertionError(e);}});
                assertEquals(Arrays.asList(third,first,second),songTitles(data,id));
            }
        } finally {
            data.delete(id);
            SharedPreferences.Editor edit=settings.edit().clear();for(Map.Entry<String,?> row:previous.entrySet())if(row.getValue() instanceof String)edit.putString(row.getKey(),(String)row.getValue());else if(row.getValue() instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)row.getValue());edit.commit();
        }
    }

    /** Presses the drag handle and slides it a little above the row titled {@code target}. */
    private static GeneralSwipeAction dragTo(String target){
        CoordinatesProvider start=view->{int[] at=new int[2];view.getLocationOnScreen(at);return new float[]{at[0]+view.getWidth()/2f,at[1]+view.getHeight()/2f};};
        CoordinatesProvider end=view->{View root=view.getRootView().findViewById(R.id.library);View row=findText(root,target);int[] at=new int[2];row.getLocationOnScreen(at);int[] handle=new int[2];view.getLocationOnScreen(handle);return new float[]{handle[0]+view.getWidth()/2f,at[1]-row.getHeight()/4f};};
        return new GeneralSwipeAction(Swipe.SLOW,start,end,Press.FINGER);
    }
    private static View findText(View root,String text){
        if(root instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)root).getText()))return root;
        if(root instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)root).getChildCount();i++){View hit=findText(((android.view.ViewGroup)root).getChildAt(i),text);if(hit!=null)return hit;}
        return null;
    }
    private static List<String> ids(JSONArray rows)throws Exception{List<String> out=new ArrayList<>();for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i).getString("id"));return out;}
    private static List<String> songTitles(LocalStore data,String id)throws Exception{JSONArray items=data.playlist(id).getJSONArray("items");List<String> out=new ArrayList<>();for(int i=0;i<items.length();i++)out.add(items.getJSONObject(i).getJSONObject("track").getString("title"));return out;}
    private static void waitUntil(java.util.function.BooleanSupplier condition){long until=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean())return;SystemClock.sleep(100);}fail("Condition was not met within ten seconds");}
    private static void ready(ActivityScenario<MainActivity> app){
        long until=SystemClock.uptimeMillis()+5000;AtomicBoolean ready=new AtomicBoolean();
        do{InstrumentationRegistry.getInstrumentation().waitForIdleSync();app.onActivity(activity->ready.set(activity.findViewById(R.id.progress).getVisibility()!=View.VISIBLE));if(ready.get())return;SystemClock.sleep(25);}while(SystemClock.uptimeMillis()<until);
        fail("App operation did not finish within five seconds");
    }
}
