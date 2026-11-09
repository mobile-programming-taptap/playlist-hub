package com.mptaptap.playlisthub;

import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Track;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicitly invoked only on a device with a connected YouTube account and known track. */
@RunWith(AndroidJUnit4.class)
public class LivePlaybackCheckTest {
    @Test public void verifiedTrackLoadsTheRealYouTubePlayer() throws Exception {
        var args=InstrumentationRegistry.getArguments();String id=args.getString("liveTrackId");
        Assume.assumeTrue(id!=null&&!id.isEmpty());
        try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
            waitFor(app,false);
            app.onActivity(activity->{try{
                Track track=new Track();track.id=id;track.title=args.getString("liveTitle","");track.artist=args.getString("liveArtist","");
                var method=MainActivity.class.getDeclaredMethod("listen",Track.class);method.setAccessible(true);method.invoke(activity,track);
            }catch(Exception error){throw new AssertionError(error);}});
            if("true".equals(args.getString("confirmCandidate"))) {
                waitFor(app,false);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("영상 재생"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            }
            waitFor(app,true);
            if("true".equals(args.getString("pressPlay"))) {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(WebView.class))
                    .perform(stablePlayerTap());
                long until=SystemClock.uptimeMillis()+30000;AtomicReference<String> state=new AtomicReference<>("");
                while(SystemClock.uptimeMillis()<until){
                    app.onActivity(activity->{try{var field=MainActivity.class.getDeclaredField("player");field.setAccessible(true);WebView web=(WebView)field.get(activity);state.set(web==null?"":web.getTitle());}catch(Exception error){throw new AssertionError(error);}});
                    if(state.get()!=null&&state.get().contains("재생 중"))break;
                    SystemClock.sleep(200);
                }
                assertTrue("YouTube must enter PLAYING after a real tap: "+state.get(),state.get()!=null&&state.get().contains("재생 중"));
                System.out.println("LIVE PLAYER: PLAYING after user tap");
                SystemClock.sleep(5000);
                app.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                app.onActivity(activity->{try{var field=MainActivity.class.getDeclaredField("player");field.setAccessible(true);assertNull("Backgrounding stops and removes the player",field.get(activity));}catch(Exception error){throw new AssertionError(error);}});
            }
        }
    }
    private static androidx.test.espresso.ViewAction stablePlayerTap(){return new androidx.test.espresso.ViewAction(){
        public org.hamcrest.Matcher<android.view.View> getConstraints(){return androidx.test.espresso.matcher.ViewMatchers.isDisplayed();}
        public String getDescription(){return "Tap the rendered YouTube player once after layout and loading settle";}
        public void perform(androidx.test.espresso.UiController ui,android.view.View view){android.graphics.Rect previous=new android.graphics.Rect();int stable=0;for(int i=0;i<100;i++){ui.loopMainThreadForAtLeast(50);android.graphics.Rect current=new android.graphics.Rect();if(view.hasWindowFocus()&&!view.isLayoutRequested()&&((WebView)view).getProgress()==100&&view.getGlobalVisibleRect(current)&&current.equals(previous))stable++;else stable=0;previous.set(current);if(stable>=5){androidx.test.espresso.action.ViewActions.click().perform(ui,view);return;}}throw new AssertionError("Player touch target did not finish loading");}
    };}
    private static void waitFor(ActivityScenario<MainActivity> app,boolean player) {
        long until=SystemClock.uptimeMillis()+35000;AtomicReference<String> title=new AtomicReference<>("");
        while(SystemClock.uptimeMillis()<until){
            AtomicBoolean ready=new AtomicBoolean();
            app.onActivity(activity->{try{
                var busy=MainActivity.class.getDeclaredField("busy");busy.setAccessible(true);
                var field=MainActivity.class.getDeclaredField("player");field.setAccessible(true);WebView web=(WebView)field.get(activity);
                if(web!=null)title.set(web.getTitle()==null?"":web.getTitle());
                ready.set(player?web!=null&&title.get().contains("재생 버튼"):!busy.getBoolean(activity));
            }catch(Exception error){throw new AssertionError(error);}});
            if(ready.get())return;
            if(title.get().contains("재생할 수 없어요")||title.get().contains("YouTube 연결에 실패"))break;
            SystemClock.sleep(200);
        }
        fail("Player did not become ready: "+title.get());
    }
}
