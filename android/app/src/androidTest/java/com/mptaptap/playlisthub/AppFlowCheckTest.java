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
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
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
                onView(withId(R.id.recommend)).perform(closeSoftKeyboard(),scrollTo(),click());
                onView(withId(R.id.prompt_layout)).check((view,error)->assertEquals("듣고 싶은 음악을 1~500자로 입력해 주세요.",((com.google.android.material.textfield.TextInputLayout)view).getError()));
                app.recreate();ready(app);
                onView(withId(R.id.results)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(allOf(withId(R.id.artwork),isDisplayed())).perform(scrollTo(),swipeLeft());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                assertTrue("Swiping must not record feedback",data.feedback().isEmpty());
                AtomicReference<String> beforeEdit=new AtomicReference<>();
                onView(currentCard(R.id.track_title)).perform(scrollTo()).check((view,error)->beforeEdit.set(((android.widget.TextView)view).getText().toString()));
                onView(withId(R.id.condition_summary)).check(matches(withText(containsString("가사 없는"))));
                onView(currentCard(R.id.edit_result)).perform(scrollTo(),click());
                onView(withText("같은 조건의 다른 곡")).inRoot(isDialog()).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(currentCard(R.id.track_title)).perform(scrollTo()).check(matches(not(withText(beforeEdit.get()))));
                assertTrue("Replacing a result must not record feedback",data.feedback().isEmpty());
                onView(withText("되돌리기")).perform(click());ready(app);
                onView(currentCard(R.id.track_title)).perform(scrollTo()).check(matches(withText(beforeEdit.get())));
                onView(currentCard(R.id.edit_result)).perform(scrollTo(),click());
                onView(withText("이번 추천에서 제외")).inRoot(isDialog()).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 7")));
                assertTrue("Temporary exclusion must not record a dislike",data.feedback().isEmpty());
                onView(withText("되돌리기")).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(currentCard(R.id.edit_result)).perform(scrollTo(),click());
                onView(withText("이번 추천에서 제외")).inRoot(isDialog()).perform(click());ready(app);
                app.recreate();ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 7")));
                onView(withId(R.id.condition_summary)).check(matches(withText(containsString("가사 없는"))));
                assertTrue(data.feedback().isEmpty());
                onView(withId(R.id.edit_request)).perform(scrollTo(),click());
                onView(withId(R.id.card_navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.prompt)).check(matches(hasFocus())).perform(closeSoftKeyboard());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.next)).perform(click());ready(app);
                onView(allOf(withId(R.id.like),isDisplayed())).perform(click());ready(app);
                assertEquals(1,data.feedback().size());
                onView(withId(R.id.previous)).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.next)).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                app.recreate();ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(allOf(withId(R.id.like),isDisplayed())).check(matches(withContentDescription("좋아요 취소")));
                onView(currentCard(R.id.external)).perform(scrollTo(),click());
                onView(withText("Spotify")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("YouTube Music")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("취소")).inRoot(isDialog()).perform(click());
                onView(currentCard(R.id.save)).perform(scrollTo(),click());ready(app);
                onView(withText("+ 새 플레이리스트")).inRoot(isDialog()).perform(click());
                onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(title),closeSoftKeyboard());
                onView(withText("만들기")).inRoot(isDialog()).perform(click());ready(app);
                onView(currentCard(R.id.save)).check(matches(withText("저장됨 · 추가")));
                noticeGone(app);
                onView(currentCard(R.id.save)).perform(scrollTo(),click());ready(app);
                onView(withText(title+" · 1곡")).inRoot(isDialog()).perform(click());ready(app);
                assertEquals(1,ourPlaylist(data,title).getJSONArray("items").length());
                onView(withId(R.id.previous)).perform(click());ready(app);
                noticeGone(app);
                onView(currentCard(R.id.save)).perform(scrollTo(),click());ready(app);
                onView(withText(title+" · 1곡")).inRoot(isDialog()).perform(click());ready(app);
                assertEquals(2,ourPlaylist(data,title).getJSONArray("items").length());
                onView(withId(R.id.save_results)).perform(scrollTo(),click());ready(app);
                onView(withText("+ 새 플레이리스트")).inRoot(isDialog()).perform(click());
                onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(bulkTitle),closeSoftKeyboard());
                onView(withText("만들기")).inRoot(isDialog()).perform(click());ready(app);
                assertEquals(8,ourPlaylist(data,bulkTitle).getJSONArray("items").length());
                onView(withId(R.id.save_results)).perform(scrollTo(),click());ready(app);
                onView(withText(bulkTitle+" · 8곡")).inRoot(isDialog()).perform(click());ready(app);
                assertEquals(8,ourPlaylist(data,bulkTitle).getJSONArray("items").length());
                noticeGone(app);
                onView(withId(R.id.edit_request)).perform(scrollTo(),click());
                onView(withId(R.id.prompt)).perform(replaceText("일본어 노래 추천"),closeSoftKeyboard());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("0 / 0")));
                onView(withId(R.id.card_navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.empty)).perform(scrollTo()).check(matches(isDisplayed()));
            }
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)){
                ready(app);
                onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());ready(app);
                onView(withHint("내 목록 이름 검색")).perform(replaceText("일치하지않는검색어"),closeSoftKeyboard());
                onView(withText("검색한 이름의 목록이 없어요.")).check(matches(isDisplayed()));
                onView(withHint("내 목록 이름 검색")).perform(replaceText(title),closeSoftKeyboard());
                onView(withText(title+"\n2곡")).perform(scrollTo(),click());ready(app);
                onView(withText("2곡")).check(matches(isDisplayed()));
                onView(withText("이름 변경")).perform(scrollTo(),click());
                onView(withHint("예: 비 오는 날의 음악")).inRoot(isDialog()).perform(replaceText(title+" 변경"),closeSoftKeyboard());
                onView(withText("변경")).inRoot(isDialog()).perform(click());ready(app);
                String id=ourPlaylist(data,title+" 변경").getString("id");
                String first= data.playlist(id).getJSONArray("items").getJSONObject(0).getString("id");
                onView(withText(startsWith("1. "))).perform(scrollTo(),click());
                onView(withText("아래로 이동")).inRoot(isDialog()).perform(click());ready(app);
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
                onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());ready(app);
                onView(withText(title+" 변경\n2곡")).perform(scrollTo(),click());ready(app);
                onView(withText(startsWith("1. "))).perform(scrollTo(),click());
                onView(withText("곡 정보·출처")).inRoot(isDialog()).perform(click());
                onView(withText(containsString("MusicBrainz 핵심 메타데이터 · CC0"))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("닫기")).inRoot(isDialog()).perform(click());
                onView(withText(startsWith("1. "))).perform(scrollTo(),click());
                onView(withText("이 목록에서 제거")).inRoot(isDialog()).perform(click());
                onView(withText("제거")).inRoot(isDialog()).perform(click());ready(app);
                onView(withText("1곡")).check(matches(isDisplayed()));
                assertEquals(1,data.playlist(id).getJSONArray("items").length());
                onView(withText("목록 삭제")).perform(scrollTo(),click());
                onView(withText("취소")).inRoot(isDialog()).perform(click());assertEquals(1,data.playlist(id).getJSONArray("items").length());
                onView(withText("목록 삭제")).perform(scrollTo(),click());
                onView(withText("삭제")).inRoot(isDialog()).perform(click());ready(app);
                onView(withText("검색한 이름의 목록이 없어요.")).check(matches(isDisplayed()));
                onView(withText("곡명·아티스트로 찾기")).perform(scrollTo(),click());
                onView(withHint("곡명 또는 아티스트 검색")).inRoot(isDialog()).perform(replaceText("blackpink"),closeSoftKeyboard());
                onView(withText("How You Like That\nBLACKPINK")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("How You Like That\nBLACKPINK")).inRoot(isDialog()).perform(scrollTo(),click());
                onView(withText("목록에 저장")).inRoot(isDialog()).perform(click());ready(app);
                int catalogueBefore=ourPlaylist(data,bulkTitle).getJSONArray("items").length();
                onView(withText(bulkTitle+" · "+catalogueBefore+"곡")).inRoot(isDialog()).perform(click());ready(app);
                onView(withText("플레이리스트에 1곡을 저장했어요.")).check(matches(isDisplayed()));
                onView(withText("곡 찾기")).check(doesNotExist());
                org.json.JSONArray catalogueItems=ourPlaylist(data,bulkTitle).getJSONArray("items");
                boolean catalogueSaved=false;
                for(int i=0;i<catalogueItems.length();i++) {
                    org.json.JSONObject track=catalogueItems.getJSONObject(i).getJSONObject("track");
                    if("How You Like That".equals(track.getString("title"))&&"BLACKPINK".equals(track.getString("artist")))catalogueSaved=true;
                }
                assertTrue("The selected catalogue track must be stored",catalogueSaved);
                noticeGone(app);
                onView(withHint("내 목록 이름 검색")).perform(replaceText(bulkTitle),closeSoftKeyboard());
                onView(withText(startsWith(bulkTitle+"\n"))).perform(scrollTo(),click());ready(app);
                onView(withText(catalogueItems.length()+"곡")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText(containsString("How You Like That\nBLACKPINK"))).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("‹ 내 목록으로")).perform(scrollTo(),click());ready(app);
                onView(withText("곡명·아티스트로 찾기")).perform(scrollTo(),click());
                onView(withHint("곡명 또는 아티스트 검색")).inRoot(isDialog()).perform(replaceText("일치하지않는곡"),closeSoftKeyboard());
                onView(withText("일치하는 곡이 없어요. 다른 곡명이나 아티스트를 입력해 보세요.")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("닫기")).inRoot(isDialog()).perform(click());
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

    @Test public void requestDraftSurvivesRecreateAndNavigation() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);
        Map<String,Integer> previousFeedback=data.feedback();
        settings.edit().remove("server").remove("prompt").commit();
        data.clearFeedback();
        String draft="비 오는 밤에는 공부 대신 산책하며 듣고 싶은 차분한 노래를 찾아주세요. 가사가 있는 한국어 음악과 편안한 재즈 위주로 너무 빠른 곡은 피하고 조용한 분위기를 오래 이어가 주세요.";
        try {
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(app);
                onView(withId(R.id.example_study)).perform(closeSoftKeyboard(),scrollTo(),click());
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                onView(withId(R.id.next)).perform(click());ready(app);
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                AtomicReference<String> title=new AtomicReference<>(),artist=new AtomicReference<>(),conditions=new AtomicReference<>();
                onView(currentCard(R.id.track_title)).check((view,error)->title.set(((android.widget.TextView)view).getText().toString()));
                onView(currentCard(R.id.track_artist)).check((view,error)->artist.set(((android.widget.TextView)view).getText().toString()));
                onView(withId(R.id.condition_summary)).check((view,error)->conditions.set(((android.widget.TextView)view).getText().toString()));
                onView(withId(R.id.edit_request)).perform(scrollTo(),click());
                onView(withId(R.id.prompt)).perform(replaceText(draft),closeSoftKeyboard());
                app.recreate();ready(app);
                onView(withId(R.id.request_panel)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
                onView(withId(R.id.results)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.card_navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.prompt)).check(matches(withText(draft))).perform(closeSoftKeyboard());
                onView(withId(R.id.nav_library)).perform(click());ready(app);
                onView(withId(R.id.library_scroll)).check(matches(isDisplayed()));
                onView(withId(R.id.nav_recommend)).perform(click());ready(app);
                onView(withId(R.id.request_panel)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
                onView(withId(R.id.card_navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.prompt)).check(matches(withText(draft))).perform(closeSoftKeyboard());
                pressBack();ready(app);
                onView(withId(R.id.request_panel)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.results)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
                onView(withId(R.id.card_navigation)).check(matches(isDisplayed()));
                onView(withId(R.id.counter)).check(matches(withText("2 / 8")));
                onView(currentCard(R.id.track_title)).check(matches(withText(title.get())));
                onView(currentCard(R.id.track_artist)).check(matches(withText(artist.get())));
                onView(withId(R.id.condition_summary)).check(matches(withText(conditions.get())));
                assertTrue("Editing a draft must not record feedback",data.feedback().isEmpty());
            }
        } finally {
            data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
            SharedPreferences.Editor restore=settings.edit();
            for(String key:new String[]{"server","prompt"}) {
                if(previousSettings.containsKey(key))restore.putString(key,(String)previousSettings.get(key));else restore.remove(key);
            }
            restore.commit();data.close();
        }
    }

    private org.hamcrest.Matcher<View> currentCard(int id) {
        return allOf(withId(id),isDescendantOfA(allOf(isAssignableFrom(android.widget.ScrollView.class),isDisplayed())));
    }

    private void noticeGone(ActivityScenario<MainActivity> app) {
        long until=SystemClock.uptimeMillis()+5000;AtomicBoolean gone=new AtomicBoolean();
        do {
            app.onActivity(activity->{View notice=activity.findViewById(com.google.android.material.R.id.snackbar_text);gone.set(notice==null||!notice.isShown());});
            if(gone.get())return;SystemClock.sleep(25);
        } while(SystemClock.uptimeMillis()<until);
        fail("Save notice did not finish within five seconds");
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
