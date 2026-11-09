package com.mptaptap.playlisthub;

import android.content.Intent;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static org.junit.Assert.*;

/** Explicit live test; only disposable app records are written and cleaned up. */
@RunWith(AndroidJUnit4.class)
public class LiveVideoConversionCheckTest {
    @Test public void commentTimelineBecomesAnIndividualVideoAndLoadsThePlayer() throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("liveConversionUrl");
        Assume.assumeTrue(url!=null&&!url.isEmpty());
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ApiClient api=new ApiClient(context,context.getSharedPreferences("settings",0).getString("server",""));
        AtomicReference<String> importId=new AtomicReference<>(),playlistId=new AtomicReference<>();
        try {
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","create"))){
                ready(app);onView(withHint("YouTube 영상 주소")).perform(scrollTo(),replaceText(url),closeSoftKeyboard());
                onView(withText("영상에서 곡 목록 가져오기")).perform(scrollTo(),click());ready(app);
                app.onActivity(activity->{JSONObject imported=data(activity).optJSONObject("imported");assertNotNull(imported);assertTrue(imported.optJSONArray("entries").length()>1);assertEquals("official_comment",imported.optString("metadataMode"));importId.set(imported.optString("id"));});
                onView(withText("개별 YouTube 영상 찾기")).perform(scrollTo(),click());ready(app);
                app.recreate();ready(app);
                app.onActivity(activity->{
                    JSONObject review=data(activity).optJSONObject("review");assertNotNull(review);
                    JSONArray rows=review.optJSONArray("items");boolean included=false;
                    for(int i=0;i<rows.length();i++){
                        JSONObject row=rows.optJSONObject(i),candidate=null;JSONArray choices=row.optJSONArray("candidates");
                        if(!included&&choices!=null&&choices.length()>0){candidate=choices.optJSONObject(0).optJSONObject("track");included=true;}
                        invoke(activity,"select",new Class[]{int.class,JSONObject.class,boolean.class},row.optInt("sourceIndex"),candidate,candidate==null);
                    }
                    assertTrue(included);invoke(activity,"render",new Class[]{});
                });
                onView(withText("선택한 개별 영상을 내 목록에 저장")).perform(scrollTo(),click());ready(app);
                onView(withText("내 목록에 저장했어요")).inRoot(isDialog()).check(matches(isDisplayed()));
                app.onActivity(activity->playlistId.set(data(activity).optString("matchedSaveId")));
                assertFalse(playlistId.get().isEmpty());onView(withText("계속 보기")).inRoot(isDialog()).perform(click());
            }
            JSONObject playlist=api.object("GET","/playlists/"+playlistId.get(),null),track=playlist.getJSONArray("items").getJSONObject(0).getJSONObject("track");
            assertEquals(1,playlist.getJSONArray("items").length());assertFalse(track.getString("sourceUrl").contains("&t="));
            assertNotEquals(url.substring(url.lastIndexOf('=')+1),track.getString("externalId"));
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                waitForMain(app,false);
                app.onActivity(activity->invoke(activity,"externalItemActions",new Class[]{String.class,String.class,JSONObject.class,int.class,int.class},playlistId.get(),"live-test-item",track,0,1));
                onView(withText("여기서 들어보기")).inRoot(isDialog()).perform(click());waitForMain(app,true);
            }
        } finally {
            if(playlistId.get()!=null&&!playlistId.get().isEmpty())api.request("DELETE","/playlists/"+playlistId.get(),null);
            if(importId.get()!=null&&!importId.get().isEmpty())api.request("DELETE","/imports/playlists/"+importId.get(),null);
        }
    }
    private static JSONObject data(HubActivity activity){return ((HubRequest)get(activity,"requests")).data;}
    private static void ready(ActivityScenario<HubActivity> app){long end=SystemClock.uptimeMillis()+60000;while(SystemClock.uptimeMillis()<end){AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->done.set(!((HubRequest)get(activity,"requests")).busy()));if(done.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(100);}fail("Live conversion timed out");}
    private static void waitForMain(ActivityScenario<MainActivity> app,boolean player){long end=SystemClock.uptimeMillis()+35000;AtomicReference<String> title=new AtomicReference<>("");while(SystemClock.uptimeMillis()<end){AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->{WebView web=(WebView)get(activity,"player");if(web!=null)title.set(web.getTitle()==null?"":web.getTitle());done.set(player?web!=null&&title.get().contains("재생 버튼"):!((Boolean)get(activity,"busy")));});if(done.get())return;SystemClock.sleep(150);}fail("App/player not ready: "+title.get());}
    private static Object get(Object target,String name){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void invoke(Object target,String name,Class[] types,Object... args){try{var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,args);}catch(Exception error){throw new AssertionError(error);}}
}
