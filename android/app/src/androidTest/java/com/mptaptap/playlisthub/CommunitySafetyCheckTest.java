package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

/** Community writes stay inside disposable HTTP fixtures and the currently selected owner. */
@RunWith(AndroidJUnit4.class)
public class CommunitySafetyCheckTest {
    @Test public void publishingRequiresReviewAndExplicitAgreementAndCancellationNeverWrites() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<HubActivity> app=f.publication()){
            ready(app);onView(withText("커뮤니티에 공개")).perform(scrollTo(),click());ready(app);
            onView(withText("1. 서로의 취향을 존중해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));assertEquals(0,f.acceptanceWrites);assertEquals(0,f.publicationWrites);
            app.recreate();ready(app);onView(withText("동의하고 공개")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("취소 · 비공개 유지")).perform(scrollTo(),click());ready(app);assertEquals(0,f.acceptanceWrites);assertEquals(0,f.publicationWrites);
            onView(withText("커뮤니티에 공개")).perform(scrollTo(),click());ready(app);onView(withText("동의하고 공개")).perform(scrollTo(),click());ready(app);
            assertEquals(1,f.acceptanceWrites);assertEquals(1,f.publicationWrites);assertTrue(f.published);onView(withText("공개 링크 공유")).perform(scrollTo()).check(matches(isDisplayed()));
            app.recreate();ready(app);assertEquals(1,f.publicationWrites);assertNull(f.failure.get());
        }
    }

    @Test public void reportsPreserveTheDraftAndRetryTheSameConfirmedBodyWithoutCancelWrites() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<HubActivity> app=f.community()){
            ready(app);openDetail(app);safety("이 목록 신고");
            onView(withText("신고 사유 선택")).perform(scrollTo(),click());onView(withText("스팸·광고")).inRoot(isDialog()).perform(click());ready(app);
            onView(withHint("어떤 내용인지 알려주세요 (최대 500자)")).perform(scrollTo(),replaceText("같은 광고가 반복돼요"),closeSoftKeyboard());
            app.recreate();ready(app);onView(withHint("어떤 내용인지 알려주세요 (최대 500자)")).check(matches(withText("같은 광고가 반복돼요")));
            confirmReport(app);onView(withText("취소")).inRoot(isDialog()).perform(click());ready(app);assertEquals(0,f.reportWrites);
            f.failReportOnce=true;confirmReport(app);onView(withText("신고 저장")).inRoot(isDialog()).perform(click());ready(app);
            assertEquals(1,f.reportWrites);onView(withText("같은 요청 다시 시도")).perform(scrollTo(),click());ready(app);
            assertEquals(2,f.reportWrites);onView(withText("신고를 저장했어요.")).perform(scrollTo()).check(matches(isDisplayed()));assertEquals("playlist",f.lastReport.getString("target"));
            safety("작성자 신고");onView(withText("신고 사유 선택")).perform(scrollTo(),click());onData(is("저작권 침해")).inRoot(isDialog()).perform(click());ready(app);
            confirmReport(app);onView(withText("신고 저장")).inRoot(isDialog()).perform(click());ready(app);
            assertEquals(3,f.reportWrites);assertEquals("author",f.lastReport.getString("target"));assertEquals("copyright",f.lastReport.getString("reason"));assertNull(f.failure.get());
        }
    }

    @Test public void blockAndUnblockRequireConfirmationAndAnOldDialogCannotWriteForANewOwner() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<HubActivity> app=f.community()){
            ready(app);openDetail(app);safety("작성자 차단");onView(withText("취소")).inRoot(isDialog()).perform(click());ready(app);assertEquals(0,f.blockWrites);
            safety("작성자 차단");onView(withText("작성자 차단")).inRoot(isDialog()).perform(click());ready(app);assertEquals(1,f.blockWrites);
            onView(withText("조건에 맞는 공개 목록이 없어요.")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("커뮤니티 관리")).perform(scrollTo(),click());onView(withText("차단한 작성자")).inRoot(isDialog()).perform(click());ready(app);
            onView(withText("차단 해제")).perform(scrollTo(),click());onView(withText("취소")).inRoot(isDialog()).perform(click());ready(app);assertEquals(0,f.unblockWrites);
            app.recreate();ready(app);onView(withText("차단 해제")).perform(scrollTo(),click());onView(withText("차단 해제")).inRoot(isDialog()).perform(click());ready(app);
            assertEquals(1,f.unblockWrites);onView(withText("차단한 작성자가 없어요.")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("음악 발견으로 돌아가기")).perform(scrollTo(),click());ready(app);openDetail(app);safety("작성자 차단");
            assertTrue(f.credentials.edit().putString(f.base,repeat('B')).commit());onView(withText("작성자 차단")).inRoot(isDialog()).perform(click());ready(app);
            assertEquals("Never block for a replacement account",1,f.blockWrites);onView(withText("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));assertNull(f.failure.get());
        }
    }

    @Test public void changedRulesAndDelayedAcceptanceCannotPublishForAReplacementOwner() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<HubActivity> app=f.publication()){
            ready(app);onView(withText("커뮤니티에 공개")).perform(scrollTo(),click());ready(app);f.rejectOldRules=true;
            onView(withText("동의하고 공개")).perform(scrollTo(),click());ready(app);assertEquals(0,f.publicationWrites);
            onView(withText("동의하고 공개")).perform(scrollTo(),click());ready(app);onView(withText("규칙 다시 확인")).perform(scrollTo()).check(matches(isDisplayed()));assertEquals(0,f.publicationWrites);
            onView(withText("규칙 다시 확인")).perform(scrollTo(),click());ready(app);f.holdAcceptance=true;
            onView(withText("동의하고 공개")).perform(scrollTo(),click());assertTrue(f.acceptanceStarted.await(5,TimeUnit.SECONDS));
            app.recreate();assertTrue(f.credentials.edit().putString(f.base,repeat('B')).commit());f.releaseAcceptance.countDown();ready(app);
            assertEquals(0,f.publicationWrites);assertFalse(f.published);assertEquals(repeat('B'),f.credentials.getString(f.base,""));assertNull(f.failure.get());
        }
        try(Fixture f=new Fixture()){
            f.own=true;try(ActivityScenario<HubActivity> app=f.community()){ready(app);openDetail(app);onView(withText("신고 · 작성자 차단")).check(doesNotExist());}
        }
        for(int rejection:new int[]{409,403})try(Fixture f=new Fixture();ActivityScenario<HubActivity> app=f.publication()){
            ready(app);onView(withText("커뮤니티에 공개")).perform(scrollTo(),click());ready(app);f.rejectPublication=rejection;
            onView(withText("동의하고 공개")).perform(scrollTo(),click());ready(app);assertEquals(0,f.publicationWrites);
            onView(withText("동의하고 공개")).perform(scrollTo(),click());ready(app);onView(withText(rejection==409?"규칙 다시 확인":"공개 설정 다시 확인")).perform(scrollTo()).check(matches(isDisplayed()));assertEquals(0,f.publicationWrites);
            if(rejection==409){onView(withText("규칙 다시 확인")).perform(scrollTo(),click());ready(app);onView(withText("동의하고 공개")).perform(scrollTo(),click());ready(app);assertEquals(1,f.publicationWrites);}
            else{onView(withText("공개 설정 다시 확인")).perform(scrollTo(),click());ready(app);onView(withText("커뮤니티에 공개")).check(doesNotExist());onView(withText("운영 규칙에 따라 공개가 중단된 목록이에요. 내 목록에서는 계속 볼 수 있어요.")).check(matches(isDisplayed()));}
        }
    }

    private static void openDetail(ActivityScenario<HubActivity> app){onView(withText("목록 열기")).perform(scrollTo(),click());ready(app);}
    private static void confirmReport(ActivityScenario<HubActivity> app){
        app.onActivity(a->new androidx.core.view.WindowInsetsControllerCompat(a.getWindow(),a.getWindow().getDecorView()).hide(androidx.core.view.WindowInsetsCompat.Type.ime()));onView(withText("신고 내용 확인")).perform(scrollTo());android.graphics.Rect previous=new android.graphics.Rect();int[] stable={0};long end=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<end){AtomicBoolean done=new AtomicBoolean();app.onActivity(activity->{android.view.View root=activity.getWindow().getDecorView();var insets=androidx.core.view.ViewCompat.getRootWindowInsets(root);android.view.View action=findText(root,"신고 내용 확인");android.graphics.Rect current=new android.graphics.Rect();
            if(activity.hasWindowFocus()&&insets!=null&&!insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())&&action!=null&&!root.isLayoutRequested()&&action.getGlobalVisibleRect(current)){if(current.equals(previous))done.set(++stable[0]>=5);else{previous.set(current);stable[0]=0;}}else stable[0]=0;});
            if(done.get()){onView(withText("신고 내용 확인")).perform(click());return;}SystemClock.sleep(50);
        }fail("Report action did not settle after restoring the draft and keyboard");
    }
    private static android.view.View findText(android.view.View view,String label){if(view instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)view).getText()))return view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.view.View found=findText(((android.view.ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void safety(String action){onView(withText("신고 · 작성자 차단")).perform(scrollTo(),click());onView(withText(action)).inRoot(isDialog()).perform(click());}
    private static void ready(ActivityScenario<HubActivity> app){long end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){AtomicBoolean done=new AtomicBoolean();app.onActivity(a->{try{var field=HubActivity.class.getDeclaredField("requests");field.setAccessible(true);done.set(!((HubRequest)field.get(a)).busy()&&a.hasWindowFocus());}catch(Exception error){throw new AssertionError(error);}});if(done.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(30);}fail("Community UI did not settle");}
    private static String repeat(char value){return String.join("",java.util.Collections.nCopies(43,String.valueOf(value)));}
    private static JSONObject json(Object... entries){JSONObject result=new JSONObject();try{for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}catch(Exception error){throw new AssertionError(error);}}

    private static final class Fixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        final Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        final AtomicBoolean running=new AtomicBoolean(true);final AtomicReference<Throwable> failure=new AtomicReference<>();
        final CountDownLatch acceptanceStarted=new CountDownLatch(1),releaseAcceptance=new CountDownLatch(1);
        final ServerSocket server;final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();final java.util.concurrent.Future<?> task;
        final String base;volatile int acceptanceWrites,publicationWrites,reportWrites,blockWrites,unblockWrites;
        volatile boolean published,blocked,own,failReportOnce,rejectOldRules,holdAcceptance,hidden;volatile int rejectPublication;volatile String version="community-v1";volatile JSONObject lastReport;
        Fixture() throws Exception {
            if(android.os.Build.VERSION.SDK_INT>=37)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
            server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(200);base="http://127.0.0.1:"+server.getLocalPort();
            assertTrue(settings.edit().putString("server",base).putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).commit());assertTrue(credentials.edit().putString(base,repeat('A')).commit());
            task=worker.submit(()->{while(running.get())try(Socket socket=server.accept()){
                socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());String[] request=line(input).split(" ");String header;int size=0;String owner="";
                while(!(header=line(input)).isEmpty()){if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(header.startsWith("Authorization: Bearer "))owner=header.substring("Authorization: Bearer ".length());}
                assertTrue(size>=0&&size<=100000);byte[] bytes=new byte[size];input.readFully(bytes);JSONObject body=size==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));int status=200;String response;
                if(!request[0].equals("GET"))assertEquals("Only the confirmed original owner may write",repeat('A'),owner);
                String path=request[1];
                if(path.equals("/health")){assertEquals("GET",request[0]);response="{}";}
                else if(path.startsWith("/public/playlists?")){assertEquals("GET",request[0]);response=(blocked?new JSONArray():new JSONArray().put(json("shareId","fixture-share","title","검사용 공개 목록","author","검사용 작성자","size",1))).toString();}
                else if(path.equals("/public/playlists/fixture-share")){assertEquals("GET",request[0]);assertFalse(blocked);response=json("shareId","fixture-share","title","검사용 공개 목록","author","검사용 작성자","size",1,"isOwner",own,"items",new JSONArray()).toString();}
                else if(path.equals("/playlists/fixture-list/publication")){
                    if(request[0].equals("PUT")){assertTrue(body.getBoolean("public"));assertTrue(acceptanceWrites>0);if(hidden)status=403;else if(rejectPublication!=0){status=rejectPublication;rejectPublication=0;if(status==409)version="community-v2";else hidden=true;}else{publicationWrites++;published=true;}}
                    else assertEquals("GET",request[0]);response=json("public",published,"sharePath","/share/fixture-share","rulesVersion",version,"rulesAccepted",acceptanceWrites>0,"moderationHidden",hidden,"message","공개 설정을 다시 확인해 주세요.").toString();
                }else if(path.equals("/community/rules")){assertEquals("GET",request[0]);response=json("version",version,"rules",new JSONArray().put("서로의 취향을 존중해 주세요.").put("개인정보와 유해한 콘텐츠를 게시하지 마세요.")).toString();}
                else if(path.equals("/community/rules/acceptance")){
                    assertEquals("PUT",request[0]);acceptanceWrites++;assertTrue(body.getBoolean("accepted"));
                    if(rejectOldRules||!version.equals(body.getString("version"))){rejectOldRules=false;version="community-v2";status=400;response=json("message","운영 규칙이 바뀌었어요.").toString();}
                    else{if(holdAcceptance){acceptanceStarted.countDown();assertTrue(releaseAcceptance.await(10,TimeUnit.SECONDS));}response=json("version",version,"accepted",true).toString();}
                }else if(path.equals("/public/playlists/fixture-share/reports")){
                    assertEquals("POST",request[0]);reportWrites++;if(reportWrites==2)assertEquals(lastReport.toString(),body.toString());lastReport=body;
                    if(failReportOnce){failReportOnce=false;status=503;response=json("message","신고를 저장하지 못했어요.").toString();}else{status=201;response=json("reportId","fixture-report","status","OPEN","duplicate",false).toString();}
                }else if(path.equals("/public/playlists/fixture-share/block-author")){assertEquals("POST",request[0]);assertEquals(0,size);blockWrites++;blocked=true;response=json("blockId","fixture-block","author","검사용 작성자").toString();}
                else if(path.equals("/community/blocks")){assertEquals("GET",request[0]);response=(blocked?new JSONArray().put(json("blockId","fixture-block","author","검사용 작성자")):new JSONArray()).toString();}
                else if(path.equals("/community/blocks/fixture-block")){assertEquals("DELETE",request[0]);assertEquals(0,size);unblockWrites++;blocked=false;status=204;response="";}
                else throw new AssertionError("Unexpected community fixture path: "+path);
                byte[] content=response.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+content.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(content);socket.getOutputStream().flush();
            }catch(SocketTimeoutException idle){}catch(Throwable error){if(running.get())failure.compareAndSet(null,error);return;}});
        }
        ActivityScenario<HubActivity> community(){return ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","community"));}
        ActivityScenario<HubActivity> publication(){return ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","community").putExtra("playlistId","fixture-list"));}
        @Override public void close()throws Exception{releaseAcceptance.countDown();running.set(false);server.close();task.get(2,TimeUnit.SECONDS);worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);assertNull(failure.get());}
        private static String line(DataInputStream input)throws Exception{StringBuilder text=new StringBuilder();int next;while((next=input.read())!=-1){if(next=='\n')return text.toString().replace("\r","");assertTrue(text.length()<8192);text.append((char)next);}throw new java.io.EOFException();}
    }
    private static void restore(SharedPreferences preferences,Map<String,?> original){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,(java.util.Set<String>)value);}assertTrue(edit.commit());}
}
