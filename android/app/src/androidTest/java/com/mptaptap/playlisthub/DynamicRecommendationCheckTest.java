package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mptaptap.core.Recommender;
import com.mptaptap.core.Track;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.withDecorView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** New verified recommendation identities, using only a disposable loopback server. */
@RunWith(AndroidJUnit4.class)
public class DynamicRecommendationCheckTest {
    @Test public void verifiedNewTracksSupportReactionSaveRefinementReplacementAndRestore() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();LocalStore local=new LocalStore(context);Map<String,Integer> oldFeedback=local.feedback();
        JSONArray original=new JSONArray(ApiClient.read(context.getAssets().open("tracks.json"))),expanded=new JSONArray(original.toString()),fresh=new JSONArray(),picks=new JSONArray();
        for(int i=0;i<9;i++) {
            Track track=new Track();track.id=UUID.randomUUID().toString();track.title=i==8?"새 카탈로그의 대체 곡":"검증된 보사노바 곡 "+(i+1);track.artist="검증 검사 가수";
            track.tags.add("bossa_nova");track.tags.add("calm");track.tags.add("genre:afrobeat");track.language="ja";track.source="MusicBrainz";track.sourceUrl="https://musicbrainz.org/recording/"+track.id;
            track.license="CC0-1.0";track.aiUseAllowed=true;track.checkedAt="2026-10-06T00:00:00Z";track.tagsSource="검사 추천 태그";
            JSONObject row=TrackJson.write(track);fresh.put(row);expanded.put(row);if(i<8)picks.put(new JSONObject().put("track",row).put("reason","검증한 새 곡이에요."));
        }
        JSONObject first=fresh.getJSONObject(0),alternative=fresh.getJSONObject(8);String firstId=first.getString("id"),firstTitle=first.getString("title"),alternativeTitle=alternative.getString("title");
        assertFalse("This record must not exist in the bundled catalogue",original.toString().contains(firstId));
        verifyCoverArtwork(context,first);
        AtomicBoolean running=new AtomicBoolean(true),registered=new AtomicBoolean(),liked=new AtomicBoolean(),saved=new AtomicBoolean();
        AtomicInteger recommendationWrites=new AtomicInteger(),feedbackWrites=new AtomicInteger(),saveWrites=new AtomicInteger(),catalogueReads=new AtomicInteger();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        String listId=UUID.randomUUID().toString(),title="새 곡 저장 검사",itemId=UUID.randomUUID().toString();var worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort(),token=(UUID.randomUUID().toString()+UUID.randomUUID()).replace("-","").substring(0,43);
            var replies=worker.submit(()->{
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());String[] request=httpLine(input).split(" ");String header;int length=0;boolean authenticated=false;
                    while(!(header=httpLine(input)).isEmpty()) {
                        String lower=header.toLowerCase(java.util.Locale.ROOT);if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        if(lower.startsWith("authorization:")&&header.endsWith("Bearer "+token))authenticated=true;
                    }
                    assertTrue("Keep the disposable account",authenticated);assertTrue(length>=0&&length<=100000);byte[] bytes=new byte[length];input.readFully(bytes);JSONObject body=length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));String response;int code=200;
                    switch(request[1]) {
                        case "/health":assertEquals("GET",request[0]);response="{\"status\":\"ok\"}";break;
                        case "/catalogue":assertEquals("GET",request[0]);catalogueReads.incrementAndGet();response=(registered.get()?expanded:original).toString();break;
                        case "/me/feedback":assertEquals("GET",request[0]);response=(liked.get()?new JSONObject().put(firstId,1):new JSONObject()).toString();break;
                        case "/me/saved-tracks":assertEquals("GET",request[0]);response=(saved.get()?new JSONArray().put(firstId):new JSONArray()).toString();break;
                        case "/recommendations":
                            assertEquals("POST",request[0]);int count=recommendationWrites.incrementAndGet();assertTrue(count<=3);
                            if(count==1){assertEquals("보사노바 음악",body.getString("prompt"));assertEquals(0,body.getJSONArray("history").length());assertEquals(0,body.getJSONArray("keptTrackIds").length());}
                            else if(count==2){assertEquals("더 잔잔하게",body.getString("prompt"));assertEquals("보사노바 음악",body.getJSONArray("history").getString(0));assertEquals(1,body.getJSONArray("keptTrackIds").length());assertEquals(firstId,body.getJSONArray("keptTrackIds").getString(0));assertEquals("bossa_nova",body.getJSONObject("previousConditions").getJSONArray("tags").getString(0));}
                            else{assertEquals("보사노바 음악",body.getString("prompt"));assertEquals(7,body.getJSONArray("keptTrackIds").length());assertEquals(firstId,body.getJSONArray("excludedTrackIds").getString(0));}
                            if(count<3)assertEquals(0,body.getJSONArray("excludedTrackIds").length());registered.set(true);
                            JSONArray returned=picks;
                            if(count==3){returned=new JSONArray();for(int i=1;i<8;i++)returned.put(picks.get(i));returned.put(new JSONObject().put("track",fresh.get(8)).put("reason","새 AI 추천"));}
                            response=new JSONObject().put("tracks",returned).put("mode","openai").put("notice","").put("conditions",new JSONObject().put("tags",(count==1?new JSONArray().put("bossa_nova"):new JSONArray().put("bossa_nova").put("calm")).put("genre:afrobeat")).put("excludedTags",new JSONArray().put("genre:gregorian_chant")).put("language","ja")).toString();break;
                        case "/playlists":assertEquals("GET",request[0]);response=new JSONArray().put(new JSONObject().put("id",listId).put("title",title).put("size",saved.get()?1:0)).toString();break;
                        default:
                            if(request[1].equals("/me/feedback/"+firstId)){assertEquals("PUT",request[0]);assertEquals(1,feedbackWrites.incrementAndGet());assertEquals(1,body.getInt("reaction"));liked.set(true);response="{\"reaction\":1}";}
                            else if(request[1].equals("/playlists/"+listId+"/items/batch")){assertEquals("POST",request[0]);assertEquals(1,saveWrites.incrementAndGet());assertEquals(1,body.getJSONArray("trackIds").length());assertEquals(firstId,body.getJSONArray("trackIds").getString(0));saved.set(true);response="[]";}
                            else if(request[1].equals("/playlists/"+listId)){assertEquals("GET",request[0]);response=new JSONObject().put("id",listId).put("title",title).put("items",new JSONArray().put(new JSONObject().put("id",itemId).put("trackId",firstId).put("track",first))).toString();}
                            else if(request[1].equals("/playback/tracks/"+alternative.getString("id")+"?includeCandidates=true")){assertEquals("GET",request[0]);code=404;response="{\"message\":\"검증용 영상 연결 없음\"}";}
                            else throw new AssertionError("Unexpected test request: "+request[0]+" "+request[1]);
                    }
                    byte[] content=response.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+code+" OK\r\nContent-Type: application/json\r\nContent-Length: "+content.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(content);socket.getOutputStream().flush();
                }catch(SocketTimeoutException timeout){
                    // Each next request must follow an actual user action.
                }catch(Throwable error){if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                assertTrue(settings.edit().clear().putString("serviceConsent:"+base,ApiClient.CONSENT_VERSION).commit());assertTrue(credentials.edit().putString(base,token).commit());
                try(ActivityScenario<MainActivity> app=ActivityScenario.launch(MainActivity.class)) {
                    ready(app);onView(withId(R.id.connect_ai)).check(matches(isDisplayed()));
                    onView(withId(R.id.prompt)).perform(scrollTo(),replaceText("즐거운 제이팝"),closeSoftKeyboard());onView(withId(R.id.settings)).perform(click());onView(withText("개발자 연결 설정")).inRoot(isDialog()).perform(scrollTo(),click());
                    assertEquals("An unconnected request must not use a song list",0,recommendationWrites.get());
                    onView(withText(containsString("AI 추천은 아직 연결되지 않았어요"))).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withHint("https://서버주소")).inRoot(isDialog()).perform(scrollTo(),replaceText(base),closeSoftKeyboard());
                    onView(withText("서버에 연결")).inRoot(isDialog()).perform(scrollTo(),click());ready(app);
                    assertEquals(base,settings.getString("server",""));
                    onView(withId(R.id.connect_ai)).check(matches(not(isDisplayed())));
                    onView(withId(R.id.prompt)).check(matches(withText("즐거운 제이팝")));
                    ready(app);onView(withId(R.id.prompt)).perform(scrollTo(),replaceText("보사노바 음악"),closeSoftKeyboard());onView(withId(R.id.recommend)).perform(scrollTo(),click());ready(app);
                    assertNull("Test server failure",serverFailure.get());assertEquals(1,recommendationWrites.get());assertTrue("Refresh the newly registered alternative candidates",catalogueReads.get()>=2);
                    onView(withId(R.id.condition_summary)).check(matches(withText(containsString("보사노바"))));
                    onView(withId(R.id.condition_summary)).check(matches(withText(allOf(containsString("afrobeat"),containsString("gregorian chant 제외"),containsString("일본어")))));
                    onView(currentCard(R.id.track_tags)).check(matches(withText(containsString("afrobeat"))));
                    onView(currentCard(R.id.track_title)).perform(scrollTo()).check(matches(withText(firstTitle)));onView(withId(R.id.like)).perform(click());ready(app);
                    onView(withId(R.id.like)).check(matches(withContentDescription("좋아요 취소")));
                    onView(currentCard(R.id.save)).perform(scrollTo(),click());ready(app);onView(withText(title+" · 0곡")).inRoot(isDialog()).perform(click());ready(app);
                    onView(currentCard(R.id.save)).check(matches(withText("저장됨 · 추가")));noticeGone(app);
                    onView(withId(R.id.settings)).perform(click());onView(withText("개발자 연결 설정")).inRoot(isDialog()).perform(scrollTo(),click());onView(withText("좋아요·싫어요 관리 (1)")).inRoot(isDialog()).perform(scrollTo(),click());
                    onView(withText("좋아요 · "+firstTitle+" — 검증 검사 가수")).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withText("닫기")).inRoot(isDialog()).perform(click());onView(withText("닫기")).inRoot(isDialog()).perform(click());
                    onView(withId(R.id.refine_request)).perform(scrollTo(),click());onView(withHint("예: 더 신나게, 피아노는 제외해줘")).inRoot(isDialog()).perform(replaceText("더 잔잔하게"),closeSoftKeyboard());
                    onView(withText("유지 · "+firstTitle)).inRoot(isDialog()).perform(scrollTo(),click());
                    app.recreate();ready(app);
                    onView(withHint("예: 더 신나게, 피아노는 제외해줘")).inRoot(isDialog()).check(matches(withText("더 잔잔하게")));
                    onView(withText("유지 · "+firstTitle)).inRoot(isDialog()).check(matches(isChecked()));
                    onView(withText("추천 바꾸기")).inRoot(isDialog()).perform(click());ready(app);
                    onView(currentCard(R.id.track_title)).perform(scrollTo()).check(matches(withText(firstTitle)));onView(withId(R.id.condition_summary)).check(matches(withText(containsString("잔잔"))));
                    app.recreate();ready(app);onView(withId(R.id.like)).check(matches(withContentDescription("좋아요 취소")));onView(currentCard(R.id.save)).check(matches(withText("저장됨 · 추가")));
                    onView(currentCard(R.id.edit_result)).perform(scrollTo(),click());onView(withText("같은 조건의 다른 곡")).inRoot(isDialog()).perform(click());ready(app);
                    onView(currentCard(R.id.track_title)).perform(scrollTo()).check(matches(withText(alternativeTitle)));assertEquals("Replacement must request a new AI candidate",3,recommendationWrites.get());noticeGone(app);
                    onView(withId(R.id.listen)).perform(click());ready(app);onView(withText("외부에서 검색")).inRoot(isDialog()).perform(click());
                    Intent opened=captureIntent(Intent.ACTION_VIEW,()->onView(withText("YouTube Music")).inRoot(isDialog()).perform(click()));
                    assertEquals("https://music.youtube.com/search?q="+android.net.Uri.encode(alternativeTitle+" 검증 검사 가수"),opened.getDataString());
                    app.recreate();ready(app);onView(currentCard(R.id.track_title)).perform(scrollTo()).check(matches(withText(alternativeTitle)));onView(withId(R.id.counter)).check(matches(withText("1 / 8")));
                    onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());ready(app);onView(withText("곡명·아티스트로 찾기")).perform(scrollTo(),click());
                    onView(withHint("곡명 또는 아티스트 검색")).inRoot(isDialog()).perform(replaceText(alternativeTitle),closeSoftKeyboard());onView(withText(alternativeTitle+"\n검증 검사 가수")).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
                    settledSearchDialog(app);
                    onView(withText("닫기")).inRoot(allOf(isDialog(),withDecorView(hasDescendant(withHint("곡명 또는 아티스트 검색"))))).perform(click());
                    waitUntil(()->{AtomicBoolean focused=new AtomicBoolean();app.onActivity(a->focused.set(a.hasWindowFocus()));return focused.get();});
                    onView(withText(title+"\n1곡")).perform(scrollTo(),click());ready(app);onView(withText("1. "+firstTitle+"\n검증 검사 가수")).perform(scrollTo()).check(matches(isDisplayed()));
                    Intent chooser=captureIntent(Intent.ACTION_CHOOSER,()->onView(withText("목록 텍스트 공유")).perform(scrollTo(),click()));Intent shared=chooser.getParcelableExtra(Intent.EXTRA_INTENT);
                    assertNotNull(shared);assertTrue(shared.getStringExtra(Intent.EXTRA_TEXT).contains(firstTitle));assertTrue(shared.getStringExtra(Intent.EXTRA_TEXT).contains(first.getString("sourceUrl")));
                    assertNull("Test server failure",serverFailure.get());assertEquals(3,recommendationWrites.get());assertEquals(1,feedbackWrites.get());assertEquals(1,saveWrites.get());assertEquals(oldFeedback,local.feedback());
                    onView(withId(R.id.settings)).perform(click());onView(withText("개발자 연결 설정")).inRoot(isDialog()).perform(scrollTo(),click());onView(withText("AI 연결 해제 · 이 기기의 목록 사용")).inRoot(isDialog()).perform(scrollTo(),click());ready(app);
                    onView(withId(R.id.settings)).perform(click());onView(withText("개발자 연결 설정")).inRoot(isDialog()).perform(scrollTo(),click());onView(withText("곡 데이터와 이용 안내")).inRoot(isDialog()).perform(scrollTo(),click());onView(withText(containsString("고정 곡 목록"))).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withText("닫기")).inRoot(isDialog()).perform(click());onView(withText("닫기")).inRoot(isDialog()).perform(click());assertEquals(oldFeedback,local.feedback());
                }
            } finally {running.set(false);server.close();replies.get(2,TimeUnit.SECONDS);}
        } finally {running.set(false);worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);local.clearFeedback();for(Map.Entry<String,Integer> row:oldFeedback.entrySet())local.react(row.getKey(),row.getValue());local.close();}
    }

    private static void verifyCoverArtwork(Context context,JSONObject original) throws Exception {
        String address="https://coverartarchive.org/release/"+UUID.randomUUID()+"/front-500";JSONObject encoded=new JSONObject(original.toString()).put("artworkUrl",address);
        assertEquals(address,TrackJson.write(TrackJson.read(encoded)).getString("artworkUrl"));
        assertEquals(original.getJSONArray("tags").toString(),TrackJson.write(TrackJson.read(encoded)).getJSONArray("tags").toString());
        encoded.put("artworkUrl","https://coverartarchive.org.attacker.invalid/front-500");try{TrackJson.read(encoded);fail("Reject an untrusted artwork URL");}catch(org.json.JSONException expected){}
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);AtomicInteger fetched=new AtomicInteger();android.widget.ImageView[] image=new android.widget.ImageView[1];
        ArtworkLoader loader=new ArtworkLoader(key->{assertEquals(address,key);fetched.incrementAndGet();return bitmap;});
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{image[0]=new android.widget.ImageView(context);loader.load(image[0],"",address,null);});
            waitUntil(()->{AtomicBoolean loaded=new AtomicBoolean();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->loaded.set(address.equals(image[0].getTag())));return loaded.get();});assertEquals(1,fetched.get());assertSame(bitmap,loader.peek("",address));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->loader.load(image[0],"","https://archive.org/download/arbitrary.jpg",null));assertEquals(1,fetched.get());assertNull(loader.peek("","https://archive.org/download/arbitrary.jpg"));
        } finally {loader.close();}
    }
    private static Intent captureIntent(String action,Runnable click) {
        var instrumentation=InstrumentationRegistry.getInstrumentation();AtomicReference<Intent> captured=new AtomicReference<>();androidx.test.runner.intent.IntentCallback callback=intent->{if(action.equals(intent.getAction()))captured.set(new Intent(intent));};
        IntentFilter filter=new IntentFilter(action);if(action.equals(Intent.ACTION_VIEW)){filter.addDataScheme("https");filter.addCategory(Intent.CATEGORY_BROWSABLE);}
        var monitor=instrumentation.addMonitor(filter,new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true);var intents=androidx.test.runner.intent.IntentMonitorRegistry.getInstance();intents.addIntentCallback(callback);
        try{click.run();instrumentation.waitForIdleSync();assertEquals("Block the actual external activity",1,monitor.getHits());assertNotNull(captured.get());return captured.get();}
        finally{intents.removeIntentCallback(callback);instrumentation.removeMonitor(monitor);}
    }
    private static org.hamcrest.Matcher<View> currentCard(int id){return allOf(withId(id),isDescendantOfA(allOf(isAssignableFrom(android.widget.ScrollView.class),isDisplayed())));}
    private static void noticeGone(ActivityScenario<MainActivity> app){waitUntil(()->{AtomicBoolean gone=new AtomicBoolean();app.onActivity(a->gone.set(a.findViewById(com.google.android.material.R.id.snackbar_action)==null));return gone.get();});}
    private static void settledSearchDialog(ActivityScenario<MainActivity> app){
        // Insets can still move the dialog after Espresso has hidden the API37 keyboard.
        android.graphics.Rect previous=new android.graphics.Rect();AtomicInteger stable=new AtomicInteger();
        waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(activity->{try{
            var field=MainActivity.class.getDeclaredField("dialogs");field.setAccessible(true);
            for(Object entry:(java.util.Set<?>)field.get(activity)){
                androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)entry;if(!dialog.isShowing())continue;
                View decor=dialog.getWindow().getDecorView(),button=dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
                var insets=androidx.core.view.ViewCompat.getRootWindowInsets(decor);android.graphics.Rect current=new android.graphics.Rect();
                if(button!=null&&button.getGlobalVisibleRect(current)&&!decor.isLayoutRequested()&&insets!=null&&!insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())){
                    if(current.equals(previous))ready.set(stable.incrementAndGet()>=5);else{previous.set(current);stable.set(0);}
                }else stable.set(0);
            }
        }catch(Exception error){throw new AssertionError(error);}});return ready.get();});
    }
    private static void ready(ActivityScenario<MainActivity> app){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->{try{var f=MainActivity.class.getDeclaredField("busy");f.setAccessible(true);ready.set(!f.getBoolean(a));}catch(Exception e){throw new AssertionError(e);}});return ready.get();});}
    private static void waitUntil(java.util.function.BooleanSupplier ready){long end=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<end){if(ready.getAsBoolean()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}captureTimeout();throw new AssertionError("The app request did not finish.");}
    private static void captureTimeout(){
        try{
            var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();java.io.File folder=new java.io.File(context.getExternalFilesDir(null),"dynamic-recommendation-check");assertTrue(folder.isDirectory()||folder.mkdirs());JSONArray windows=new JSONArray();
            instrumentation.runOnMainSync(()->{
                if(android.os.Build.VERSION.SDK_INT>=29)for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){
                    try{JSONArray labels=new JSONArray();collectLabels(root,labels);windows.put(new JSONObject().put("root",root.getClass().getName()).put("focus",root.hasWindowFocus()).put("shown",root.isShown()).put("texts",labels));}catch(Exception error){throw new AssertionError(error);}
                }
            });
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(folder,"timeout-windows.json"))){output.write(windows.toString(2).getBytes(StandardCharsets.UTF_8));}
            android.graphics.Bitmap screenshot=instrumentation.getUiAutomation().takeScreenshot();if(screenshot!=null)try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(folder,"timeout.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{screenshot.recycle();}
        }catch(Exception ignored){}
    }
    private static void collectLabels(View view,JSONArray labels){if(!view.isShown())return;if(view instanceof android.widget.TextView){String text=((android.widget.TextView)view).getText().toString();if(!text.isEmpty())labels.put(text);}if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)collectLabels(((android.view.ViewGroup)view).getChildAt(i),labels);}
    private static ViewAction closeSoftKeyboard(){ViewAction close=androidx.test.espresso.action.ViewActions.closeSoftKeyboard();return new ViewAction(){
        public org.hamcrest.Matcher<View> getConstraints(){return close.getConstraints();}public String getDescription(){return "close the keyboard using the current platform visibility";}
        public void perform(UiController ui,View view){ui.loopMainThreadUntilIdle();if(android.os.Build.VERSION.SDK_INT<30)close.perform(ui,view);else{var insets=androidx.core.view.ViewCompat.getRootWindowInsets(view);assertNotNull(insets);if(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))close.perform(ui,view);}ui.loopMainThreadUntilIdle();}
    };}
    private static String httpLine(DataInputStream input) throws java.io.IOException {StringBuilder value=new StringBuilder();int next;while((next=input.read())!=-1){if(next=='\n')return value.toString();if(next!='\r')value.append((char)next);if(value.length()>8192)throw new java.io.IOException("Test header too long");}throw new java.io.EOFException("Incomplete test header");}
    private static void restore(SharedPreferences prefs,Map<String,?> rows){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> row:rows.entrySet()){Object v=row.getValue();if(v instanceof String)edit.putString(row.getKey(),(String)v);else if(v instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)v);else if(v instanceof Integer)edit.putInt(row.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(row.getKey(),(Long)v);else if(v instanceof Float)edit.putFloat(row.getKey(),(Float)v);else if(v instanceof java.util.Set)edit.putStringSet(row.getKey(),(java.util.Set<String>)v);}assertTrue(edit.commit());}
}
