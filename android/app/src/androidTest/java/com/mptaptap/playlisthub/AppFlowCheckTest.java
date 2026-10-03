package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        String bulkTitle="전체 저장 검사 "+UUID.randomUUID().toString().substring(0,8);
        LocalStore data=new LocalStore(context);
        Map<String,Integer> previousFeedback=data.feedback();
        settings.edit().remove("server").remove("prompt").commit();
        data.clearFeedback();
        try {
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                ready(app);
                onView(withId(R.id.recommend)).perform(click());
                onView(withId(R.id.prompt_layout)).check((view,error)->assertEquals("듣고 싶은 음악을 1~500자로 입력해 주세요.",((com.google.android.material.textfield.TextInputLayout)view).getError()));
                app.recreate();ready(app);
                onView(withId(R.id.results)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.example_study)).perform(click());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(allOf(withId(R.id.artwork),isDisplayed())).perform(scrollTo(),swipeLeft());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                assertTrue("Swiping must not record feedback",data.feedback().isEmpty());
                AtomicReference<String> beforeEdit=new AtomicReference<>();
                onView(allOf(withId(R.id.track_title),isDisplayed())).check((view,error)->beforeEdit.set(((android.widget.TextView)view).getText().toString()));
                onView(withId(R.id.condition_summary)).check(matches(withText(containsString("가사 없는"))));
                onView(allOf(withId(R.id.edit_result),isDisplayed())).perform(scrollTo(),click());
                onView(withText("같은 조건의 다른 곡")).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(allOf(withId(R.id.track_title),isDisplayed())).check(matches(not(withText(beforeEdit.get()))));
                assertTrue("Replacing a result must not record feedback",data.feedback().isEmpty());
                onView(withText("되돌리기")).perform(click());ready(app);
                onView(allOf(withId(R.id.track_title),isDisplayed())).check(matches(withText(beforeEdit.get())));
                onView(allOf(withId(R.id.edit_result),isDisplayed())).perform(scrollTo(),click());
                onView(withText("이번 추천에서 제외")).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 7")));
                assertTrue("Temporary exclusion must not record a dislike",data.feedback().isEmpty());
                onView(withText("되돌리기")).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(allOf(withId(R.id.edit_result),isDisplayed())).perform(scrollTo(),click());
                onView(withText("이번 추천에서 제외")).perform(click());ready(app);
                app.recreate();ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 7")));
                onView(withId(R.id.condition_summary)).check(matches(withText(containsString("가사 없는"))));
                assertTrue(data.feedback().isEmpty());
                onView(withId(R.id.edit_request)).perform(scrollTo(),click());
                onView(withId(R.id.prompt)).check(matches(hasFocus())).perform(closeSoftKeyboard());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.next)).perform(scrollTo(),click());ready(app);
                onView(allOf(withId(R.id.like),isDisplayed())).perform(scrollTo(),click());ready(app);
                assertEquals(1,data.feedback().size());
                onView(withId(R.id.previous)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.next)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                app.recreate();ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(allOf(withId(R.id.like),isDisplayed())).check(matches(withText("좋아요 취소")));
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
                onView(withId(R.id.previous)).perform(scrollTo(),click());ready(app);
                onView(allOf(withId(R.id.save),isDisplayed())).perform(scrollTo(),click());ready(app);
                onView(withText(title+" · 1곡")).perform(click());ready(app);
                assertEquals(2,ourPlaylist(data,title).getJSONArray("items").length());
                onView(withId(R.id.save_results)).perform(scrollTo(),click());ready(app);
                onView(withText("+ 새 플레이리스트")).perform(click());
                onView(withHint("예: 비 오는 날의 음악")).perform(replaceText(bulkTitle),closeSoftKeyboard());
                onView(withText("만들기")).perform(click());ready(app);
                assertEquals(8,ourPlaylist(data,bulkTitle).getJSONArray("items").length());
                onView(withId(R.id.save_results)).perform(scrollTo(),click());ready(app);
                onView(withText(bulkTitle+" · 8곡")).perform(click());ready(app);
                assertEquals(8,ourPlaylist(data,bulkTitle).getJSONArray("items").length());
                onView(withId(R.id.edit_request)).perform(scrollTo(),click());
                onView(withId(R.id.prompt)).perform(replaceText("일본어 노래 추천"),closeSoftKeyboard());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("0 / 0")));
                onView(withId(R.id.card_navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.empty)).perform(scrollTo()).check(matches(isDisplayed()));
            }
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                ready(app);
                onView(withId(R.id.nav_library)).perform(click());ready(app);
                onView(withHint("내 목록 이름 검색")).perform(replaceText("일치하지않는검색어"),closeSoftKeyboard());
                onView(withText("검색한 이름의 목록이 없어요.")).check(matches(isDisplayed()));
                onView(withHint("내 목록 이름 검색")).perform(replaceText(title),closeSoftKeyboard());
                onView(withText(title+"\n2곡 · 앱 추천에서 저장")).perform(scrollTo(),click());ready(app);
                onView(withText("2곡")).check(matches(isDisplayed()));
                onView(withText("이름 변경")).perform(scrollTo(),click());
                onView(withHint("예: 비 오는 날의 음악")).perform(replaceText(title+" 변경"),closeSoftKeyboard());
                onView(withText("변경")).perform(click());ready(app);
                String id=ourPlaylist(data,title+" 변경").getString("id");
                String first= data.playlist(id).getJSONArray("items").getJSONObject(0).getString("id");
                onView(withText(startsWith("1. "))).perform(scrollTo(),click());
                onView(withText("아래로 이동")).perform(click());ready(app);
                assertEquals(first,data.playlist(id).getJSONArray("items").getJSONObject(1).getString("id"));
                app.recreate();ready(app);
                onView(withText(title+" 변경")).check(matches(isDisplayed()));
                onView(withText("2곡")).check(matches(isDisplayed()));
                assertEquals(first,data.playlist(id).getJSONArray("items").getJSONObject(1).getString("id"));
                android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
                android.app.Instrumentation.ActivityMonitor share=instrumentation.addMonitor(new IntentFilter(Intent.ACTION_CHOOSER),new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true);
                try {
                    onView(withText("목록 텍스트 공유")).perform(scrollTo(),click());ready(app);
                    assertEquals("Sharing must launch the native chooser without sending a message",1,share.getHits());
                    assertEquals(1,data.feedback().size());
                } finally { instrumentation.removeMonitor(share); }
                onView(withId(R.id.nav_recommend)).perform(click());
                app.recreate();ready(app);
                onView(withId(R.id.nav_library)).perform(click());ready(app);
                onView(withText(title+" 변경\n2곡 · 앱 추천에서 저장")).perform(scrollTo(),click());ready(app);
                onView(withText(startsWith("1. "))).perform(scrollTo(),click());
                onView(withText("곡 정보·출처")).perform(click());
                onView(withText(containsString("MusicBrainz 핵심 메타데이터 · CC0"))).check(matches(isDisplayed()));
                onView(withText("닫기")).perform(click());
                onView(withText(startsWith("1. "))).perform(scrollTo(),click());
                onView(withText("이 목록에서 제거")).perform(click());
                onView(withText("제거")).perform(click());ready(app);
                onView(withText("1곡")).check(matches(isDisplayed()));
                assertEquals(1,data.playlist(id).getJSONArray("items").length());
                onView(withText("목록 삭제")).perform(scrollTo(),click());
                onView(withText("취소")).perform(click());assertEquals(1,data.playlist(id).getJSONArray("items").length());
                onView(withText("목록 삭제")).perform(scrollTo(),click());
                onView(withText("삭제")).perform(click());ready(app);
                onView(withText("검색한 이름의 목록이 없어요.")).check(matches(isDisplayed()));
                onView(withText("곡명·아티스트로 찾기")).perform(scrollTo(),click());
                onView(withHint("곡명 또는 아티스트 검색")).perform(replaceText("blackpink"),closeSoftKeyboard());
                onView(withText("How You Like That\nBLACKPINK")).check(matches(isDisplayed()));
                onView(withHint("곡명 또는 아티스트 검색")).perform(replaceText("일치하지않는곡"),closeSoftKeyboard());
                onView(withText("일치하는 곡이 없어요. 다른 곡명이나 아티스트를 입력해 보세요.")).check(matches(isDisplayed()));
                onView(withText("닫기")).perform(click());
            }
        } finally {
            data.getWritableDatabase().delete("playlists","title IN (?,?,?)",new String[]{title,title+" 변경",bulkTitle});
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
