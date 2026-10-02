package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppFlowCheckTest {
    @Test public void recommendReactSaveRestartAndRemove() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        String title="화면 검사 "+UUID.randomUUID().toString().substring(0,8);
        LocalStore data=new LocalStore(context);
        Map<String,Integer> previousFeedback=data.feedback();
        settings.edit().remove("server").remove("prompt").commit();
        data.clearFeedback();
        try {
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                ready(app);
                onView(withId(R.id.recommend)).perform(click());
                onView(withId(R.id.prompt_layout)).check((view,error)->assertEquals("듣고 싶은 음악을 1~500자로 입력해 주세요.",((com.google.android.material.textfield.TextInputLayout)view).getError()));
                onView(withId(R.id.example_study)).perform(click());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.pager)).perform(swipeLeft());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                assertTrue("Swiping must not record feedback",data.feedback().isEmpty());
                onView(allOf(withId(R.id.like),isDisplayed())).perform(scrollTo(),click());ready(app);
                assertEquals(1,data.feedback().size());
                onView(withId(R.id.previous)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.next)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                app.recreate();ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(allOf(withId(R.id.like),isDisplayed())).check(matches(withText("♥ 좋아요 취소")));
                onView(allOf(withId(R.id.external),isDisplayed())).perform(scrollTo(),click());
                onView(withText("Spotify · 곡 검색")).check(matches(isDisplayed()));
                onView(withText("YouTube Music · 영상으로 연결")).check(matches(isDisplayed()));
                onView(withText("취소")).perform(click());
                onView(allOf(withId(R.id.save),isDisplayed())).perform(scrollTo(),click());ready(app);
                onView(withText("+ 새 플레이리스트")).perform(click());
                onView(withHint("예: 비 오는 날의 음악")).perform(replaceText(title),closeSoftKeyboard());
                onView(withText("만들기")).perform(click());ready(app);
                onView(allOf(withId(R.id.save),isDisplayed())).check(matches(withText("저장됨 · 추가")));
                onView(allOf(withId(R.id.save),isDisplayed())).perform(click());ready(app);
                onView(withText(title+" · 1곡")).perform(click());ready(app);
                assertEquals(1,ourPlaylist(data,title).getJSONArray("items").length());
            }
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                ready(app);
                onView(withId(R.id.nav_library)).perform(click());ready(app);
                onView(withText(title+"\n1곡 · 앱 추천에서 저장")).perform(click());ready(app);
                onView(withText("1곡")).check(matches(isDisplayed()));
                onView(withText(startsWith("1. "))).perform(click());
                onView(withText("곡 정보·출처")).perform(click());
                onView(withText(containsString("MusicBrainz 핵심 메타데이터 · CC0"))).check(matches(isDisplayed()));
                onView(withText("닫기")).perform(click());
                onView(withText(startsWith("1. "))).perform(click());
                onView(withText("이 목록에서 제거")).perform(click());
                onView(withText("제거")).perform(click());ready(app);
                onView(withText("0곡")).check(matches(isDisplayed()));
                assertEquals(0,ourPlaylist(data,title).getJSONArray("items").length());
            }
        } finally {
            data.getWritableDatabase().delete("playlists","title=?",new String[]{title});
            data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
            SharedPreferences.Editor restore=settings.edit();
            for(String key:new String[]{"server","prompt"}){
                if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
            }
            restore.commit();data.close();
        }
    }

    private org.json.JSONObject ourPlaylist(LocalStore data,String title) throws Exception {
        org.json.JSONArray lists=data.playlists();
        for(int i=0;i<lists.length();i++)if(title.equals(lists.getJSONObject(i).getString("title")))return data.playlist(lists.getJSONObject(i).getString("id"));
        throw new AssertionError("Saved playlist missing");
    }

    private void ready(ActivityScenario<MainActivity> app){
        long until=SystemClock.uptimeMillis()+5000;
        AtomicBoolean ready=new AtomicBoolean();
        do{
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            app.onActivity(activity->ready.set(activity.findViewById(R.id.progress).getVisibility()!=View.VISIBLE
                && ((androidx.viewpager2.widget.ViewPager2)activity.findViewById(R.id.pager)).getScrollState()==androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE));
            if(ready.get())return;
            SystemClock.sleep(25);
        }while(SystemClock.uptimeMillis()<until);
        fail("App operation did not finish within five seconds");
    }
}
