package com.mptaptap.playlisthub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Choreographer;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mptaptap.core.Track;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** UI-only fixtures: these checks do not authenticate, recommend, import or transfer real music. */
@RunWith(AndroidJUnit4.class)
public class UiThemeCheckTest {
    private static final String[] HUB_SCREENS={"create","library","batch","history","account","preview","review","transfer","sync","community","publicDetail","publication","communityRules","report","blockedAuthors"};
    private static final String[] HUB_HEADINGS={"음악을 한곳에","내 플레이리스트","가져오기 결과","이전 음악 목록과 전송","UI 검사용 계정","UI 검사용 영상 목록","옮길 곡 확인","플레이리스트 전송 결과","원본 목록의 변경 내용","다른 사람의 음악 목록","UI 검사용 공개 목록","목록 공개 설정","커뮤니티 운영 규칙","음악 목록 신고","차단한 작성자"};

    @Test public void allHubScreensAndTheirPopupContentUseTheSharedTone() throws Exception {
        try(UiFixture fixture=new UiFixture(true);
            ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(fixture.context,HubActivity.class).putExtra("mode","create"))) {
            ready(HubActivity.class);
            for(String screen:HUB_SCREENS) {
                app.onActivity(activity->{HubRequest requests=(HubRequest)get(activity,"requests");seed(requests.data);put(requests.data,"screen",screen);invoke(activity,"render");((androidx.core.widget.NestedScrollView)((View)get(activity,"content")).getParent()).scrollTo(0,0);});
                idle();app.onActivity(activity->checkHub(activity,screen));capture("hub-"+screen);
                AtomicBoolean overflowing=new AtomicBoolean();app.onActivity(activity->{View content=(View)get(activity,"content");androidx.core.widget.NestedScrollView scroll=(androidx.core.widget.NestedScrollView)content.getParent();overflowing.set(content.getHeight()>scroll.getHeight()-scroll.getPaddingTop()-scroll.getPaddingBottom());if(overflowing.get())scroll.scrollTo(0,content.getHeight());});
                if(overflowing.get()) {
                    idle();app.onActivity(activity->{View content=(View)get(activity,"content");assertTrue("Overflowing content must be scrollable",((View)content.getParent()).getScrollY()>0);MaterialButton last=null;for(MaterialButton action:views(content,MaterialButton.class))if(action.isShown())last=action;assertNotNull("Every overflowing Hub screen retains an action",last);Rect visible=new Rect();assertTrue("The final action must be reachable",last.getGlobalVisibleRect(visible));assertEquals("The final action must not be clipped horizontally",last.getWidth(),visible.width());assertEquals("The final action must not be clipped vertically",last.getHeight(),visible.height());assertTrue("The final action needs a 48dp target",visible.height()>=TapTapUi.dp(activity,48));});
                    capture("hub-"+screen+"-bottom");
                }
            }
            // The anonymous form is a separate branch from the signed-in account screenshot.
            app.onActivity(activity->{JSONObject data=((HubRequest)get(activity,"requests")).data;put(data,"account",new JSONObject());put(data,"screen","account");invoke(activity,"render");});
            idle();app.onActivity(activity->checkHub(activity,"account"));capture("hub-account-login");
            app.onActivity(activity->{JSONObject data=((HubRequest)get(activity,"requests")).data;seed(data);put(data,"screen","create");invoke(activity,"render");});idle();
            onView(isAssignableFrom(Spinner.class)).perform(scrollTo(),click());capture("provider-dropdown");
            onData(allOf(is(instanceOf(String.class)),is("Spotify"))).perform(click());idle();
            app.onActivity(activity->assertEquals("spotify",((HubRequest)get(activity,"requests")).data.optString("sourceProvider")));
            app.onActivity(activity->{JSONObject data=((HubRequest)get(activity,"requests")).data;put(data,"screen","preview");invoke(activity,"render");});idle();
            onView(withText("원문과 시간 보기")).perform(scrollTo(),click());checkPopup("popup-source",false);dismissPopup();
            app.onActivity(activity->{JSONObject data=((HubRequest)get(activity,"requests")).data;put(data,"screen","review");invoke(activity,"render");});idle();
            onView(withText("후보 선택 또는 제외")).perform(scrollTo(),click());checkPopup("popup-candidates",false);dismissPopup();
            app.onActivity(activity->{JSONObject data=((HubRequest)get(activity,"requests")).data;put(data,"screen","publicDetail");invoke(activity,"render");});idle();
            onView(withText("내 목록으로 복사")).perform(scrollTo(),click());checkPopup("popup-copy",true);dismissPopup();
            assertNull(fixture.failure.get());assertEquals("UI fixtures must never write to a service",0,fixture.writes);
        }
    }

    @Test public void switchingTabsAndRecreatingHubKeepsTheSelectedTab() throws Exception {
        try(UiFixture fixture=new UiFixture(true);ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
            ready(MainActivity.class);app.onActivity(activity->{assertSelected(activity,R.id.nav_recommend);assertMainHeader(activity);});capture("main-request");
            onView(withId(R.id.nav_create)).perform(click());ready(HubActivity.class);onActive(HubActivity.class,activity->assertSelected(activity,R.id.nav_create));
            onView(withId(R.id.nav_community)).perform(click());ready(HubActivity.class);
            AtomicReference<HubActivity> old=new AtomicReference<>();onActive(HubActivity.class,activity->{assertSelected(activity,R.id.nav_community);old.set(activity);activity.recreate();});
            waitUntil(()->{HubActivity current=active(HubActivity.class);return current!=null&&current!=old.get()&&!((HubRequest)get(current,"requests")).busy();});
            onActive(HubActivity.class,activity->{assertSelected(activity,R.id.nav_community);assertEquals("community",get(activity,"mode"));});capture("navigation-community-restored");
            onView(withId(R.id.nav_account)).perform(click());ready(HubActivity.class);onActive(HubActivity.class,activity->assertSelected(activity,R.id.nav_account));capture("navigation-account");
            onView(withId(R.id.nav_library)).perform(click());ready(MainActivity.class);app.onActivity(activity->{assertSelected(activity,R.id.nav_library);assertTrue(activity.findViewById(R.id.library_scroll).isShown());});capture("navigation-library");
            onView(withId(R.id.nav_recommend)).perform(click());ready(MainActivity.class);app.onActivity(activity->{assertSelected(activity,R.id.nav_recommend);assertMainHeader(activity);});
            assertNull(fixture.failure.get());assertEquals(0,fixture.writes);
        }
    }

    @Test public void localLibraryAndLargeFontDialogsKeepValidationAndActionsVisible() throws Exception {
        try(UiFixture fixture=new UiFixture(false)) {
            String dbName="ui-theme-"+UUID.randomUUID()+".db";LocalStore disposable=new LocalStore(fixture.context,dbName);
            String id=disposable.create("UI 검사용 빈 플레이리스트").getString("id");
            try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                ready(MainActivity.class);app.onActivity(activity->{LocalStore original=(LocalStore)get(activity,"local");set(activity,"local",disposable);original.close();});
                onView(withId(R.id.nav_library)).perform(click());ready(MainActivity.class);app.onActivity(activity->checkInputsAndCards(activity.findViewById(R.id.library)));capture("local-library");
                onView(withText(containsString("UI 검사용 빈 플레이리스트"))).perform(scrollTo(),click());ready(MainActivity.class);capture("local-playlist-detail");
                app.onActivity(activity->assertEquals(id,get(activity,"playlistId")));
                // Rebuild dynamic content at a large font size without changing the device's global settings.
                app.onActivity(activity->{Configuration large=new Configuration(activity.getResources().getConfiguration());large.fontScale=1.5f;activity.getResources().updateConfiguration(large,null);assertEquals(1.5f,activity.getResources().getConfiguration().fontScale,0);invoke(activity,"editPlaylist",new Class[]{List.class,String.class,String.class},null,null,"");});
                checkPopup("popup-input-large-font",true);
                onView(withId(android.R.id.button1)).perform(click());idle();
                onActive(MainActivity.class,activity->{AlertDialog dialog=showingDialog(activity);assertTrue("Blank name validation must preserve the popup",dialog.isShowing());EditText input=views(dialog.getWindow().getDecorView(),EditText.class).get(0);assertEquals("이름을 입력해 주세요.",input.getError().toString());input.requestFocus();((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});
                SystemClock.sleep(400);checkPopup("popup-input-validation-keyboard",true);
                onActive(MainActivity.class,activity->{AlertDialog dialog=showingDialog(activity);var insets=ViewCompat.getRootWindowInsets(dialog.getWindow().getDecorView());writeKeyboardEvidence(fixture.context,insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime()));});dismissPopup();
                app.onActivity(activity->{Configuration normal=new Configuration(activity.getResources().getConfiguration());normal.fontScale=fixture.originalFontScale;activity.getResources().updateConfiguration(normal,null);
                    @SuppressWarnings("unchecked") List<Track> tracks=(List<Track>)get(activity,"catalogue");assertFalse(tracks.isEmpty());invoke(activity,"editResult",new Class[]{Track.class},tracks.get(0));});
                checkPopup("popup-three-actions",false);onActive(MainActivity.class,activity->{AlertDialog dialog=showingDialog(activity);assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown());});dismissPopup();
                app.onActivity(activity->{MaterialAlertDialogBuilder builder=(MaterialAlertDialogBuilder)invoke(activity,"dialogBuilder");builder.setTitle("UI 검사용 선택 목록").setItems(new String[]{"첫 번째 선택","두 번째 선택"},(d,w)->{}).setNegativeButton("닫기",null).show();});checkPopup("popup-list",false);dismissPopup();
                app.onActivity(activity->{MaterialAlertDialogBuilder builder=(MaterialAlertDialogBuilder)invoke(activity,"dialogBuilder");builder.setTitle("UI 검사용 안내").setMessage("긴 안내문도 읽을 수 있게 줄 간격과 대비를 확인해요. 실제 서비스 작업은 실행하지 않아요.").setPositiveButton("확인",null).setNegativeButton("취소",null).show();});checkPopup("popup-message",false);dismissPopup();
                app.onActivity(activity->invoke(activity,"showSettings"));checkPopup("popup-settings",true);
                onView(withText("서비스 이용 시작")).perform(scrollTo());app.onActivity(activity->assertVisibleText(showingDialog(activity).getWindow().getDecorView(),"서비스 이용 시작"));capture("popup-settings-actions");dismissPopup();
                app.onActivity(activity->assertNull("Settings dismissal must keep its existing cleanup handler",get(activity,"settingsDialog")));
                app.onActivity(activity->{@SuppressWarnings("unchecked") List<Track> tracks=(List<Track>)get(activity,"catalogue");invoke(activity,"external",new Class[]{Track.class},tracks.get(0));});checkPopup("popup-platform-picker",false);
                app.onActivity(activity->{View decor=showingDialog(activity).getWindow().getDecorView();assertEquals("The external chooser retains both music services",2,views(decor,MaterialCardView.class).size());checkInputsAndCards(decor);assertVisibleText(decor,"Spotify");assertVisibleText(decor,"YouTube Music");});dismissPopup();
            } finally {disposable.close();fixture.context.deleteDatabase(dbName);}
        }
    }

    private static void seed(JSONObject data) {
        JSONObject song=json("title","UI 검사용 곡","artist","UI 검사용 아티스트","externalId","ui-only-song","album","UI 검사용 앨범");
        JSONObject item=json("sourceIndex",0,"track",song,"timestamp","00:00","seconds",0,"sourceRow","00:00 UI 검사용 아티스트 - UI 검사용 곡");
        put(data,"sourceProvider","youtube");put(data,"targetProvider","youtube");put(data,"libraryProvider","youtube");put(data,"libraryLoaded","true");put(data,"librarySelections",new JSONArray());
        put(data,"libraryItems",array(json("id","ui-list","title","UI 검사용 플레이리스트","itemCount",1,"privacyStatus","private")));
        put(data,"batchImports",array(json("requestId","ui-import","title","UI 검사용 가져오기","status","DONE","size",1)));
        put(data,"history",json("imports",array(json("id","ui-import","provider","youtube","title","UI 검사용 변환 기록","size",1)),"transfers",array(json("id","ui-transfer","provider","youtube","title","UI 검사용 전송 기록","status","COMPLETED"))));
        put(data,"account",json("profile",profile(),"configuration",configuration(),"connections",new JSONArray()));
        put(data,"imported",json("title","UI 검사용 영상 목록","entries",array(item),"durationSeconds",120,"videoId","aaaaaaaaaaa","provider","youtube"));
        put(data,"importId","ui-import");put(data,"listTitle","UI 검사용 목록");put(data,"edits",new JSONArray());put(data,"selections",new JSONArray());
        put(data,"review",json("items",array(json("sourceIndex",0,"source",song,"status","NEEDS_REVIEW","candidates",array(json("track",song)))),"nextOffset",-1));
        put(data,"transfer",json("provider","youtube","status","COMPLETED","items",array(json("sourceIndex",0,"status","ADDED"))));
        put(data,"sync",json("added",array(song),"removed",new JSONArray(),"changed",new JSONArray(),"after",json("items",array(item))));
        put(data,"publicRows",publicRows());put(data,"shareId","ui-share");put(data,"publicDetail",json("title","UI 검사용 공개 목록","author","UI 검사용 작성자","size",1,"favourites",2,"items",array(item)));
        put(data,"publication",json("public",false));put(data,"publicationId","ui-list");
        put(data,"communityRules",json("version","community-v1","rules",new JSONArray().put("서로의 취향을 존중하고 괴롭힘·혐오 표현을 게시하지 마세요.").put("권리가 있는 음악 정보만 공유하고 개인정보를 게시하지 마세요.")));put(data,"rulesPurpose","publication");put(data,"rulesPlaylist","ui-list");
        put(data,"reportShare","ui-share");put(data,"reportTitle","UI 검사용 공개 목록");put(data,"reportAuthor","UI 검사용 작성자");put(data,"reportTarget","playlist");put(data,"reportReason","spam");put(data,"reportDetails","");
        put(data,"blockedAuthors",array(json("blockId","ui-block","author","UI 검사용 차단 작성자")));
    }

    private static JSONObject profile(){return json("name","UI 검사용 계정","email","ui-theme@example.invalid");}
    private static JSONObject configuration(){return json("providers",array(json("provider","spotify","configured",false,"setupMessage","UI 검사용 연결 안내"),json("provider","youtube","configured",true)));}
    private static JSONArray publicRows(){return array(json("title","UI 검사용 공개 목록","author","UI 검사용 작성자","size",1,"favourites",2,"shareId","ui-share"));}

    private static void checkHub(HubActivity activity,String screen) {
        assertEquals(screen,((HubRequest)get(activity,"requests")).data.optString("screen"));
        View content=(View)get(activity,"content");assertTrue(content.getParent() instanceof androidx.core.widget.NestedScrollView);
        GradientDrawable background=(GradientDrawable)((View)content.getParent()).getBackground();assertEquals(activity.getColor(R.color.background),background.getColor().getDefaultColor());
        assertTrue("Secondary content must be charcoal",ColorUtils.calculateLuminance(activity.getColor(R.color.background))<.05);
        checkInputsAndCards(content);String heading=null;for(int i=0;i<HUB_SCREENS.length;i++)if(HUB_SCREENS[i].equals(screen))heading=HUB_HEADINGS[i];
        if(screen.equals("account")&&!((HubRequest)get(activity,"requests")).data.optJSONObject("account").has("profile"))heading="내 음악을 이어가요";
        assertVisibleText(content,heading);
        assertSelected(activity,R.id.nav_create); // These secondary screens were opened inside the create tab.
    }

    private static void checkInputsAndCards(View root) {
        for(MaterialCardView card:views(root,MaterialCardView.class))assertEquals(card.getContext().getColor(R.color.surface),card.getCardBackgroundColor().getDefaultColor());
        for(CheckBox check:views(root,CheckBox.class))assertEquals("Checkbox labels need the same readable ink",check.getContext().getColor(R.color.ink),check.getCurrentTextColor());
        for(EditText input:views(root,EditText.class)) {
            assertEquals(input.getContext().getColor(R.color.ink),input.getCurrentTextColor());assertEquals(input.getContext().getColor(R.color.muted),input.getCurrentHintTextColor());
            assertTrue("Forms must use a rounded filled background; hint="+input.getHint()+" class="+input.getBackground().getClass().getName(),input.getBackground() instanceof android.graphics.drawable.StateListDrawable);
            assertTrue(input.getMinimumHeight()>=TapTapUi.dp(input.getContext(),56));
            assertTrue("Input hints must remain readable",ColorUtils.calculateContrast(input.getCurrentHintTextColor(),input.getContext().getColor(R.color.input_surface))>=4.5);
        }
    }

    private static void assertMainHeader(MainActivity activity) {
        TextView title=activity.findViewById(R.id.header_title);assertEquals(activity.getColor(R.color.discover_ink),title.getCurrentTextColor());
        assertTrue(activity.findViewById(R.id.request_panel).isShown());
        assertTrue("Main uses the warm gradient",((android.graphics.drawable.LayerDrawable)activity.findViewById(R.id.main).getBackground()).getDrawable(0) instanceof GradientDrawable);
    }

    private static void assertSelected(Activity activity,int id){assertEquals(id,((BottomNavigationView)activity.findViewById(R.id.navigation)).getSelectedItemId());}

    private static void checkPopup(String screenshot,boolean hasInput) throws Exception {
        waitUntil(()->{AtomicBoolean shown=new AtomicBoolean();Activity activity=active(Activity.class);if(activity==null)return false;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AlertDialog dialog=findShowingDialog(activity);shown.set(dialog!=null&&dialog.getWindow().getDecorView().isShown()&&dialog.getWindow().getDecorView().getWidth()>0);});return shown.get();});
        idle();Activity activity=active(Activity.class);assertNotNull(activity);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            AlertDialog dialog=showingDialog(activity);View decor=dialog.getWindow().getDecorView();assertTrue(dialog.isShowing());
            String expected=screenshot.equals("popup-source")?"영상 설명 원문":screenshot.equals("popup-candidates")?"곡과 버전을 확인해 주세요":screenshot.equals("popup-copy")?"내 목록으로 복사":screenshot.startsWith("popup-input")?"새 플레이리스트":screenshot.equals("popup-three-actions")?"이번 추천에서 제외":screenshot.equals("popup-list")?"UI 검사용 선택 목록":screenshot.equals("popup-settings")?"나의 음악 설정":screenshot.equals("popup-platform-picker")?"어디에서 들을까요?":"UI 검사용 안내";
            assertVisibleText(decor,expected);
            for(int id:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
                TextView button=dialog.getButton(id);if(button==null||button.getVisibility()!=View.VISIBLE)continue;
                Rect visible=new Rect();assertTrue("Dialog actions must remain visible",button.getGlobalVisibleRect(visible));assertEquals("No clipped action width",button.getWidth(),visible.width());assertEquals("No clipped action height",button.getHeight(),visible.height());
                assertTrue("Actions need a 48dp target",button.getHeight()>=TapTapUi.dp(activity,48));assertNotNull(button.getLayout());
                for(int line=0;line<button.getLineCount();line++)assertTrue("Dialog action text must fit",button.getLayout().getLineWidth(line)<=button.getWidth()-button.getTotalPaddingLeft()-button.getTotalPaddingRight()+1);
            }
            TextView message=decor.findViewById(android.R.id.message);if(message!=null)assertTrue(ColorUtils.calculateContrast(message.getCurrentTextColor(),activity.getColor(R.color.surface))>=4.5);
            if(hasInput){assertFalse(views(decor,EditText.class).isEmpty());checkInputsAndCards(decor);}
        });capture(screenshot);
    }

    private static AlertDialog showingDialog(Activity activity){
        AlertDialog dialog=findShowingDialog(activity);if(dialog!=null)return dialog;throw new AssertionError("No app popup is showing");
    }
    private static AlertDialog findShowingDialog(Activity activity){
        @SuppressWarnings("unchecked") java.util.Set<AlertDialog> dialogs=(java.util.Set<AlertDialog>)get(activity,"dialogs");
        for(AlertDialog dialog:dialogs)if(dialog.isShowing())return dialog;return null;
    }
    private static void dismissPopup(){Activity activity=active(Activity.class);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AlertDialog dialog=showingDialog(activity);((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(dialog.getWindow().getDecorView().getWindowToken(),0);dialog.dismiss();});idle();}
    private static <T extends View> List<T> views(View root,Class<T> type){List<T> found=new ArrayList<>();if(type.isInstance(root))found.add(type.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)found.addAll(views(((ViewGroup)root).getChildAt(i),type));return found;}
    private static void assertVisibleText(View root,String expected){for(TextView label:views(root,TextView.class))if(expected!=null&&expected.contentEquals(label.getText())&&label.getGlobalVisibleRect(new Rect()))return;throw new AssertionError("Expected visible screen/popup heading: "+expected);}

    private static void capture(String label) throws Exception {
        idle();SystemClock.sleep(100);idle();Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File folder=new File(context.getExternalFilesDir(null),"ui-theme-check");assertTrue(folder.isDirectory()||folder.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(folder,label+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
    }
    private static void writeKeyboardEvidence(Context context,boolean shown){try{File folder=new File(context.getExternalFilesDir(null),"ui-theme-check");try(FileOutputStream output=new FileOutputStream(new File(folder,"keyboard-evidence.json"))){output.write(json("keyboardObserved",shown,"fontScale",1.5,"fixtureOnly",true).toString().getBytes(StandardCharsets.UTF_8));}}catch(Exception error){throw new AssertionError(error);}}

    private static <T extends Activity> T active(Class<T> type){AtomicReference<T> result=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(type.isInstance(activity))result.set(type.cast(activity));});return result.get();}
    private static <T extends Activity> void onActive(Class<T> type,java.util.function.Consumer<T> action){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(type.isInstance(activity)){action.accept(type.cast(activity));return;}throw new AssertionError("Expected activity missing: "+type.getSimpleName());});}
    private static void ready(Class<? extends Activity> type){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(type.isInstance(activity)){boolean busy=activity instanceof HubActivity?((HubRequest)get(activity,"requests")).busy():(Boolean)get(activity,"busy");ready.set(!busy&&activity.hasWindowFocus());}});return ready.get();});}
    private static void waitUntil(java.util.function.BooleanSupplier condition){long end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){if(condition.getAsBoolean()){idle();return;}SystemClock.sleep(50);}throw new AssertionError("UI did not settle within 10 seconds");}
    private static void idle(){
        var instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync();CountDownLatch frames=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback(){int remaining=2;public void doFrame(long time){if(--remaining==0)frames.countDown();else Choreographer.getInstance().postFrameCallback(this);}}));
        try{assertTrue("Views must finish drawing before UI evidence is captured",frames.await(3,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}instrumentation.waitForIdleSync();
    }
    private static Object get(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void set(Object target,String name,Object value){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception error){throw new AssertionError(error);}}
    private static Object invoke(Object target,String name){return invoke(target,name,new Class[0]);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception error){throw new AssertionError(error);}}
    private static JSONObject json(Object...values){JSONObject result=new JSONObject();for(int i=0;i<values.length;i+=2)put(result,(String)values[i],values[i+1]);return result;}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception error){throw new AssertionError(error);}}
    private static JSONArray array(JSONObject...rows){JSONArray result=new JSONArray();for(JSONObject row:rows)result.put(row);return result;}

    /** A local read-only endpoint isolates every account and all real server credentials. */
    private static final class UiFixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        final Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        final float originalFontScale=context.getResources().getConfiguration().fontScale;
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicBoolean running=new AtomicBoolean(true);
        final ServerSocket server;final Thread responder;volatile int writes;
        UiFixture(boolean online) throws Exception {
            // The runner grants local-network access on API 37 and restores device permissions afterward.
            if(online&&android.os.Build.VERSION.SDK_INT>=37)assertEquals("Grant local-network permission before running loopback UI checks",android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK));
            server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(300);
            String base="http://127.0.0.1:"+server.getLocalPort();String token=String.join("",java.util.Collections.nCopies(43,"U"));
            assertTrue(settings.edit().clear().putString("prompt","").commit());if(online){assertTrue(settings.edit().putString("server",base).putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).commit());assertTrue(credentials.edit().putString(base,token).commit());}
            responder=new Thread(()->{while(running.get())try(Socket socket=server.accept()) {
                socket.setSoTimeout(2000);DataInputStream input=new DataInputStream(socket.getInputStream());String[] request=line(input).split(" ");String header;int bytes=0;
                while(!(header=line(input)).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))bytes=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                assertTrue(bytes>=0&&bytes<100000);input.readFully(new byte[bytes]);if(!"GET".equals(request[0])){writes++;throw new AssertionError("UI fixture forbids writes: "+request[1]);}
                String path=request[1],body;
                if(path.equals("/health"))body="{\"status\":\"ok\"}";
                else if(path.equals("/accounts/me"))body=profile().toString();
                else if(path.equals("/connections/config"))body=configuration().toString();
                else if(path.equals("/connections")||path.equals("/catalogue")||path.equals("/me/saved-tracks")||path.equals("/playlists"))body="[]";
                else if(path.equals("/me/feedback"))body="{}";
                else if(path.startsWith("/public/playlists?"))body=publicRows().toString();
                else throw new AssertionError("Unexpected UI fixture read: "+path);
                byte[] reply=body.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(reply);socket.getOutputStream().flush();
            }catch(SocketTimeoutException waiting){}catch(Throwable error){if(running.get())failure.compareAndSet(null,error);return;}},"ui-theme-read-only-fixture");responder.start();
        }
        private static String line(DataInputStream input)throws Exception{StringBuilder result=new StringBuilder();int value;while((value=input.read())!=-1){if(value=='\n')return result.toString().replace("\r","");assertTrue(result.length()<8192);result.append((char)value);}throw new java.io.EOFException();}
        @Override public void close() throws Exception {
            running.set(false);server.close();responder.join(2000);assertFalse("Fixture worker must stop",responder.isAlive());
            Configuration normal=new Configuration(context.getResources().getConfiguration());normal.fontScale=originalFontScale;context.getResources().updateConfiguration(normal,null);
            restore(settings,oldSettings);restore(credentials,oldCredentials);
            if(failure.get()!=null)throw new AssertionError(failure.get());
        }
        private static void restore(SharedPreferences prefs,Map<String,?> previous){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> row:previous.entrySet()){
            Object value=row.getValue();if(value instanceof String)edit.putString(row.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)value);
            else if(value instanceof Integer)edit.putInt(row.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(row.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(row.getKey(),(Float)value);
            else if(value instanceof java.util.Set){@SuppressWarnings("unchecked") java.util.Set<String> strings=(java.util.Set<String>)value;edit.putStringSet(row.getKey(),strings);}
        }assertTrue(edit.commit());}
    }
}
