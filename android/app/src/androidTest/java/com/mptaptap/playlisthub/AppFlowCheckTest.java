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
    @Test public void requestDraftSurvivesRecreateAndNavigation() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        Map<String,?> previousSettings=settings.getAll();
        LocalStore data=new LocalStore(context);
        Map<String,Integer> previousFeedback=data.feedback();
        settings.edit().remove("server").remove("prompt").remove("serviceConsent:"+TapTapApplication.defaultServer()).commit();
        data.clearFeedback();
        String draft="비 오는 밤에는 공부 대신 산책하며 듣고 싶은 차분한 노래를 찾아주세요. 가사가 있는 한국어 음악과 편안한 재즈 위주로 너무 빠른 곡은 피하고 조용한 분위기를 오래 이어가 주세요.";
        try {
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(app);
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
                onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                onView(withText("서비스 이용 안내")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("동의하지 않음")).inRoot(isDialog()).perform(click());
                onView(withId(R.id.results)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.prompt)).check(matches(withText(draft)));
                assertTrue("Editing a draft must not record feedback",data.feedback().isEmpty());
            }
        } finally {
            data.clearFeedback();for(Map.Entry<String,Integer> reaction:previousFeedback.entrySet())data.react(reaction.getKey(),reaction.getValue());
            SharedPreferences.Editor restore=settings.edit();
            for(String key:new String[]{"server","prompt","serviceConsent:"+TapTapApplication.defaultServer()}) {
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
