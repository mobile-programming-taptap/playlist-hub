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
                onView(withText(org.hamcrest.Matchers.startsWith("개별 영상 "))).perform(scrollTo(),click());ready(app);
                onView(withText("내 목록에 저장했어요")).inRoot(isDialog()).check(matches(isDisplayed()));
                app.onActivity(activity->playlistId.set(data(activity).optString("matchedSaveId")));
                assertFalse(playlistId.get().isEmpty());onView(withText("계속 보기")).inRoot(isDialog()).perform(click());
            }
            JSONObject playlist=api.object("GET","/playlists/"+playlistId.get(),null),track=playlist.getJSONArray("items").getJSONObject(0).getJSONObject("track");
            assertTrue(playlist.getJSONArray("items").length()>0);assertFalse(track.getString("sourceUrl").contains("&t="));
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
    @Test public void fourTimelinesSaveFromTheAndroidConversionScreen() throws Exception {
        String base=InstrumentationRegistry.getArguments().getString("liveTimelineBase");
        Assume.assumeTrue(base!=null&&!base.isEmpty());
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var settings=context.getSharedPreferences("settings",0);
        var credentials=context.getSharedPreferences("server_credentials",0);
        String consentKey="serviceConsent:"+base;
        String oldServer=settings.getString("server",null),oldConsent=settings.getString(consentKey,null);
        String oldToken=credentials.getString(base,null);
        String[] videos={"6X7kLTbaMxg","KBj8vfaBhWQ","YsWaftG0D6U","8EMrjq188nQ"};
        int[] counts={29,25,20,27};
        String[] sources={"official_comment","official_metadata","official_comment","official_comment"};
        String[] firstTitles={"집 앞","一輪花","Payphone","Pretty Lies"};
        assertTrue(settings.edit().putString("server",base).putString(consentKey,ApiClient.CONSENT_VERSION).commit());
        assertTrue(credentials.edit().remove(base).commit());
        ApiClient api=new ApiClient(context,base);
        var created=new java.util.ArrayList<String>();
        var individualPlaylists=new java.util.ArrayList<String>();
        boolean checkIndividual=InstrumentationRegistry.getArguments().getString("liveIndividual","false").equals("true");
        boolean checkArtwork=InstrumentationRegistry.getArguments().getString("liveArtwork","false").equals("true");
        try {
            api.connect();
            for(int index=0;index<videos.length;index++){
                int count=counts[index];
                String video=videos[index],source=sources[index],firstTitle=firstTitles[index];
                String url="https://www.youtube.com/watch?v="+video;
                var savedId=new AtomicReference<String>();
                var timestamps=new AtomicReference<JSONArray>();
                try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","create"))){
                    ready(app);
                    onView(withHint("YouTube 영상 주소")).perform(scrollTo(),replaceText(url),closeSoftKeyboard());
                    onView(withText("영상에서 곡 목록 가져오기")).perform(scrollTo(),click());ready(app);
                    app.onActivity(a->{
                        JSONObject imported=data(a).optJSONObject("imported");
                        assertNotNull("Conversion for "+video,imported);
                        created.add(imported.optString("id"));
                        assertEquals(source,imported.optString("metadataMode"));
                        JSONArray entries=imported.optJSONArray("entries");
                        assertEquals(count,entries.length());
                        timestamps.set(entries);
                    });
                    if(index==2)onView(withText("곡명과 아티스트 순서 바꾸기")).perform(scrollTo(),click());
                    onView(withText(firstTitle)).perform(scrollTo()).check(matches(isDisplayed()));
                    if(checkArtwork)awaitArtwork(firstTitle,video);
                    onView(withText("선택한 곡을 앱 목록에 저장 ("+count+"곡)")).perform(scrollTo(),click());ready(app);
                    onView(withText("내 목록에 저장했어요")).inRoot(isDialog()).check(matches(isDisplayed()));
                    app.onActivity(a->savedId.set(data(a).optString("saveId")));
                    if(index==0&&checkIndividual){
                        onView(withText("계속 보기")).inRoot(isDialog()).perform(click());
                        onView(withText("개별 YouTube 영상 찾기")).perform(scrollTo(),click());ready(app);
                        var expectedVideos=new java.util.ArrayList<String>();
                        app.onActivity(a->{
                            JSONObject review=data(a).optJSONObject("review");
                            assertNotNull("The automatic search must reach the result list",review);
                            assertEquals(-1,review.optInt("nextOffset"));
                            JSONArray rows=review.optJSONArray("items");
                            assertEquals(count,rows.length());
                            for(int j=0;j<rows.length();j++){
                                JSONArray candidates=rows.optJSONObject(j).optJSONArray("candidates");
                                if(candidates.length()>0)expectedVideos.add(candidates.optJSONObject(0).optJSONObject("track").optString("externalId"));
                            }
                        });
                        assertFalse(expectedVideos.isEmpty());
                        if(checkArtwork)awaitArtwork("1. 집 앞 · 태리 (Terry)",expectedVideos.get(0));
                        onView(withText("개별 영상 "+expectedVideos.size()+"곡 내 목록에 저장")).perform(scrollTo(),click());ready(app);
                        app.onActivity(a->individualPlaylists.add(data(a).optString("matchedSaveId")));
                        String matchedId=individualPlaylists.get(0);
                        assertFalse(matchedId.isEmpty());
                        JSONArray individual=api.object("GET","/playlists/"+matchedId,null).getJSONArray("items");
                        assertEquals(expectedVideos.size(),individual.length());
                        for(int j=0;j<individual.length();j++)
                            assertEquals(expectedVideos.get(j),individual.getJSONObject(j).getJSONObject("track").getString("externalId"));
                        android.util.Log.i("TapTapTimelineCheck","First-ranked individual videos saved: "+individual.length()+"/"+count);
                    }
                    onView(withText("내 목록으로")).inRoot(isDialog()).perform(click());
                }
                assertFalse(savedId.get().isEmpty());
                JSONArray library=api.array("/playlists");
                boolean found=false;
                for(int j=0;j<library.length();j++)if(savedId.get().equals(library.getJSONObject(j).getString("id")))found=true;
                assertTrue("Saved playlist must appear in My Lists",found);
                JSONArray tracks=api.object("GET","/playlists/"+savedId.get(),null).getJSONArray("items");
                assertEquals(count,tracks.length());
                assertEquals(firstTitle,tracks.getJSONObject(0).getJSONObject("track").getString("title"));
                for(int j=0;j<tracks.length();j++){
                    int seconds=timestamps.get().getJSONObject(j).getInt("seconds");
                    assertEquals(url+"&t="+seconds+"s",tracks.getJSONObject(j).getJSONObject("track").getString("sourceUrl"));
                }
                if(checkArtwork&&index==3){
                    try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                        waitForMain(app,false);
                        onView(withId(R.id.nav_library)).perform(click());
                        waitForMain(app,false);
                        String playlistTitle=api.object("GET","/playlists/"+savedId.get(),null).getString("title");
                        onView(withText(playlistTitle)).perform(scrollTo(),click());waitForMain(app,false);
                        awaitArtwork(firstTitle,video);
                        onView(withContentDescription("곡 메뉴 · Bloodshot")).perform(scrollTo(),click());
                        onView(withText("여기서 들어보기")).inRoot(isDialog()).check(matches(isDisplayed()));
                        onView(withText("닫기")).inRoot(isDialog()).perform(click());
                        onView(withContentDescription("여기서 들어보기 · Bloodshot")).perform(scrollTo(),click());
                        waitForMain(app,true);
                        onView(withContentDescription("Bloodshot YouTube 공식 플레이어")).inRoot(isDialog()).check(matches(isDisplayed()));
                        android.util.Log.i("TapTapTimelineCheck","Saved list thumbnail and direct playback verified for "+video);
                    }
                }
                android.util.Log.i("TapTapTimelineCheck",video+" "+source+" "+count+" saved and verified");
            }
        } finally {
            try{
                for(String id:individualPlaylists)if(!id.isEmpty())api.request("DELETE","/playlists/"+id,null);
                for(String id:created){
                    try{api.request("DELETE","/playlists/"+id,null);}catch(ApiClient.ApiError e){if(e.statusCode!=404)throw e;}
                    api.request("DELETE","/imports/playlists/"+id,null);
                }
            }finally {
                settings.edit().putString("server",oldServer).putString(consentKey,oldConsent).commit();
                credentials.edit().putString(base,oldToken).commit();
            }
        }
    }

    private static void awaitArtwork(String title,String key) {
        long until=SystemClock.uptimeMillis()+12_000;
        var artwork=onView(org.hamcrest.Matchers.allOf(withId(R.id.row_artwork),hasSibling(hasDescendant(withText(title)))));
        AssertionError failure=null;
        while(SystemClock.uptimeMillis()<until){
            try{
                artwork.check((view,error)->{
                    if(error!=null)throw error;
                    assertEquals("Artwork for "+title,key,view.getTag());
                    assertTrue(((android.widget.ImageView)view).getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
                });
                return;
            }catch(AssertionError error){failure=error;}
            SystemClock.sleep(100);
        }
        throw new AssertionError("Thumbnail did not load for "+title,failure);
    }
    private static JSONObject data(HubActivity activity){return ((HubRequest)get(activity,"requests")).data;}
    private static void ready(ActivityScenario<HubActivity> app){long end=SystemClock.uptimeMillis()+60000;while(SystemClock.uptimeMillis()<end){AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->done.set(!((HubRequest)get(activity,"requests")).busy()));if(done.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(100);}fail("Live conversion timed out");}
    private static void waitForMain(ActivityScenario<MainActivity> app,boolean player){long end=SystemClock.uptimeMillis()+35000;AtomicReference<String> title=new AtomicReference<>("");while(SystemClock.uptimeMillis()<end){AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->{WebView web=(WebView)get(activity,"player");if(web!=null)title.set(web.getTitle()==null?"":web.getTitle());done.set(player?web!=null&&title.get().contains("재생 버튼"):!((Boolean)get(activity,"busy")));});if(done.get())return;SystemClock.sleep(150);}fail("App/player not ready: "+title.get());}
    private static Object get(Object target,String name){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void invoke(Object target,String name,Class[] types,Object... args){try{var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,args);}catch(Exception error){throw new AssertionError(error);}}
}
