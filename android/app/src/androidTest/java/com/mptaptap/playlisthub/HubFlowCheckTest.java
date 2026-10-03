package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import java.util.Map;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
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
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses only disposable runner/loopback fixtures; no real provider requests. */
@RunWith(AndroidJUnit4.class)
public class HubFlowCheckTest {
    @Test public void importedPlaylistReviewExclusionAndTransferRetryUseConfirmedRows() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        AtomicBoolean running=new AtomicBoolean(true);AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        AtomicReference<String> importId=new AtomicReference<>(),jobId=new AtomicReference<>();AtomicReference<JSONArray> savedRows=new AtomicReference<>();
        AtomicInteger imports=new AtomicInteger(),saves=new AtomicInteger(),matches=new AtomicInteger(),reviews=new AtomicInteger(),transfers=new AtomicInteger(),retries=new AtomicInteger(),statusReads=new AtomicInteger();
        String listTitle="검수한 전송 목록",sourceUrl="https://open.spotify.com/playlist/SSSSSSSSSSSSSSSSSSSSSS",notice="가져오지 못한 2개 항목: 2번째 · 로컬 파일은 다른 플랫폼에서 찾을 수 없어요. / 4번째 · 음악 곡이 아닌 항목은 이전하지 않아요.";
        int[] indices={0,2,4};String[] sourceNames={"원본 곡 A","원본 곡 B","원본 곡 C"},targetIds={"aaaaaaaaaa1","bbbbbbbbbb2"};
        JSONArray originals=new JSONArray();
        for(int i=0;i<indices.length;i++){String externalId=UUID.randomUUID().toString().replace("-","").substring(0,22);originals.put(new JSONObject().put("sourceIndex",indices[i]).put("recommendationTrackId",JSONObject.NULL)
                .put("track",new JSONObject().put("platform","spotify").put("externalId",externalId).put("title",sourceNames[i]).put("artist","검사 가수").put("sourceUrl","https://open.spotify.com/track/"+externalId)));}
        var worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort(),token="PPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPP";
            var replies=worker.submit(()->{
                JSONObject review=null,job=null;
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());String[] request=httpLine(input).split(" ");
                    String header;int length=0;boolean authenticated=false;
                    while(!(header=httpLine(input)).isEmpty()) {
                        String lower=header.toLowerCase(java.util.Locale.ROOT);if(lower.startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        if(lower.startsWith("authorization:")&&header.endsWith("Bearer "+token))authenticated=true;
                    }
                    assertTrue("Keep the seeded disposable session",authenticated);assertTrue("Bound the fixture request",length>=0&&length<=100000);
                    byte[] bodyBytes=new byte[length];input.readFully(bodyBytes);JSONObject body=length==0?new JSONObject():new JSONObject(new String(bodyBytes,StandardCharsets.UTF_8));
                    String method=request[0],path=request[1];JSONObject response;
                    if(path.equals("/health")){assertEquals("GET",method);response=new JSONObject().put("status","ok");}
                    else if(path.equals("/imports/playlists")) {
                        assertEquals("POST",method);assertEquals(1,imports.incrementAndGet());assertEquals("spotify",body.getString("provider"));assertEquals(listTitle,body.getString("title"));assertEquals(sourceUrl,body.getString("url"));
                        importId.set(body.getString("id"));assertEquals(importId.get(),UUID.fromString(importId.get()).toString());
                        response=new JSONObject().put("id",importId.get()).put("provider","spotify").put("title",listTitle).put("sourceUrl",sourceUrl).put("items",originals).put("reviewedItems",originals).put("localPlaylistId",importId.get()).put("saved",true).put("notice",notice).put("skippedCount",2)
                                .put("skippedItems",new JSONArray().put(new JSONObject().put("sourceIndex",1).put("reason","로컬 파일")).put(new JSONObject().put("sourceIndex",3).put("reason","음악 곡이 아닌 항목")));
                    } else if(path.equals("/imports/playlists/"+importId.get()+"/save")) {
                        assertEquals("POST",method);assertTrue(saves.incrementAndGet()<=2);assertEquals(importId.get(),body.getString("playlistId"));assertEquals(listTitle,body.getString("title"));JSONArray entries=body.getJSONArray("entries");assertEquals(3,entries.length());
                        for(int i=0;i<indices.length;i++){JSONObject row=entries.getJSONObject(i);assertEquals(indices[i],row.getInt("sourceIndex"));assertFalse(row.getBoolean("excluded"));assertEquals(i==0?"수정한 곡 A":sourceNames[i],row.getString("title"));assertEquals("검사 가수",row.getString("artist"));}
                        savedRows.set(entries);response=new JSONObject().put("id",importId.get()).put("title",listTitle).put("size",3).put("items",originals);
                    } else if(path.equals("/matches")) {
                        assertEquals("POST",method);assertEquals(1,matches.incrementAndGet());assertEquals(importId.get(),body.getString("importId"));assertEquals("youtube",body.getString("targetProvider"));assertEquals(2,saves.get());JSONArray rows=new JSONArray();
                        for(int i=0;i<indices.length;i++){JSONObject source=new JSONObject(originals.getJSONObject(i).getJSONObject("track").toString()).put("title",savedRows.get().getJSONObject(i).getString("title"));JSONArray candidates=new JSONArray();
                            if(i<2)candidates.put(new JSONObject().put("track",new JSONObject().put("platform","youtube").put("externalId",targetIds[i]).put("title","대상 곡 "+(i==0?"A":"B")).put("artist","대상 가수").put("album","검사 앨범")));
                            rows.put(new JSONObject().put("sourceIndex",indices[i]).put("source",source).put("candidates",candidates).put("status",i<2?"NEEDS_REVIEW":"UNKNOWN").put("selected",JSONObject.NULL).put("approved",false));}
                        review=new JSONObject().put("importId",importId.get()).put("targetProvider","youtube").put("items",rows).put("nextOffset",-1);response=review;
                    } else if(path.equals("/matches/"+importId.get())) {
                        assertEquals("PUT",method);assertEquals(1,reviews.incrementAndGet());assertEquals("youtube",body.getString("targetProvider"));JSONArray selected=body.getJSONArray("selections");assertEquals(3,selected.length());
                        for(int i=0;i<indices.length;i++){JSONObject choice=selected.getJSONObject(i),row=review.getJSONArray("items").getJSONObject(i);assertEquals(indices[i],choice.getInt("sourceIndex"));assertEquals(i==2,choice.getBoolean("excluded"));assertEquals(i<2?targetIds[i]:"",choice.getString("targetId"));row.put("approved",true).put("status",i==2?"USER_EXCLUDED":"MATCHED").put("selected",i==2?JSONObject.NULL:row.getJSONArray("candidates").getJSONObject(0).getJSONObject("track"));}
                        response=review;
                    } else if(path.equals("/transfers")) {
                        assertEquals("POST",method);assertEquals(1,transfers.incrementAndGet());assertEquals(1,reviews.get());assertTrue(body.getBoolean("confirmed"));assertEquals(importId.get(),body.getString("importId"));assertEquals("youtube",body.getString("targetProvider"));assertEquals(listTitle,body.getString("title"));jobId.set(body.getString("id"));assertEquals(jobId.get(),UUID.fromString(jobId.get()).toString());
                        job=new JSONObject().put("id",jobId.get()).put("importId",importId.get()).put("provider","youtube").put("title",listTitle).put("status","PARTIAL").put("excludedSourceIndices",new JSONArray().put(4)).put("playlistUrl","").put("notice","두 번째 전송 항목은 다시 시도할 수 있어요.")
                                .put("items",new JSONArray().put(new JSONObject().put("sourceIndex",0).put("targetId",targetIds[0]).put("status","ADDED")).put(new JSONObject().put("sourceIndex",2).put("targetId",targetIds[1]).put("status","FAILED")))
                                .put("counts",new JSONObject().put("added",1).put("failed",1).put("excluded",1).put("pending",0).put("unknown",0));response=job;
                    } else if(path.equals("/transfers/"+jobId.get()+"/retry")) {
                        assertEquals("POST",method);assertEquals(1,retries.incrementAndGet());assertTrue(body.getBoolean("confirmed"));assertEquals(1,transfers.get());assertEquals("PARTIAL",job.getString("status"));
                        job.getJSONArray("items").getJSONObject(1).put("status","ADDED");job.put("status","COMPLETED").put("notice","플랫폼 목록에 저장했어요.");job.getJSONObject("counts").put("added",2).put("failed",0);response=job;
                    } else if(path.equals("/transfers/"+jobId.get())) {assertEquals("GET",method);statusReads.incrementAndGet();response=job;}
                    else throw new AssertionError("Unexpected test request: "+method+" "+path);
                    byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                }catch(SocketTimeoutException timeout){
                    // Await the next explicit user action on this loopback fixture.
                }catch(Throwable error){if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                assertTrue(settings.edit().putString("server",base).commit());assertTrue(credentials.edit().putString(base,token).commit());
                try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                    ready(app);focused(app);
                    onView(withHint("플레이리스트 주소")).perform(scrollTo(),replaceText(sourceUrl),closeSoftKeyboard());
                    onView(withHint("새 목록 이름")).perform(scrollTo(),replaceText(listTitle),closeSoftKeyboard());onView(withText("플레이리스트 가져오기")).perform(scrollTo(),click());ready(app);
                    assertNull("Test server failure",serverFailure.get());onView(withText(notice)).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withText("3. 가져온 곡")).perform(scrollTo()).check(matches(isDisplayed()));onView(withText("5. 가져온 곡")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(allOf(withHint("곡명"),hasSibling(withText("1. 가져온 곡")))).perform(scrollTo(),replaceText("수정한 곡 A"),closeSoftKeyboard());
                    onView(withText("선택한 곡을 앱 목록에 저장")).perform(scrollTo(),click());ready(app);
                    onView(withText("내 목록에 저장했어요")).inRoot(isDialog()).check(matches(isDisplayed()));onView(withText("계속 보기")).inRoot(isDialog()).perform(click());assertEquals(1,saves.get());assertEquals(3,savedRows.get().length());
                    onView(isAssignableFrom(android.widget.Spinner.class)).perform(scrollTo(),click());onData(allOf(is(instanceOf(String.class)),is("YouTube Music"))).perform(click());
                    onView(withText("대상 플랫폼의 곡 후보 찾기")).perform(scrollTo(),click());ready(app);assertNull("Test server failure",serverFailure.get());
                    onView(allOf(withText("후보 선택 또는 제외"),hasSibling(withText("1. 수정한 곡 A · 검사 가수")))).perform(scrollTo(),click());onView(withText("대상 곡 A · 대상 가수 · 검사 앨범")).inRoot(isDialog()).perform(click());
                    onView(allOf(withText("후보 선택 또는 제외"),hasSibling(withText("3. 원본 곡 B · 검사 가수")))).perform(scrollTo(),click());onView(withText("대상 곡 B · 대상 가수 · 검사 앨범")).inRoot(isDialog()).perform(click());
                    onView(allOf(withText("후보 선택 또는 제외"),hasSibling(withText("5. 원본 곡 C · 검사 가수")))).perform(scrollTo(),click());onView(withText("이 곡은 전송에서 제외")).inRoot(isDialog()).perform(click());
                    onView(withText("전송에서 제외됨")).perform(scrollTo()).check(matches(isDisplayed()));onView(withText("선택 확인 후 플레이리스트 보내기")).perform(scrollTo(),click());ready(app);
                    onView(withText("확인한 곡을 전송할까요?")).inRoot(isDialog()).check(matches(isDisplayed()));assertEquals("Confirmation must precede a remote transfer",0,transfers.get());assertEquals(1,reviews.get());
                    onView(withText("전송")).inRoot(isDialog()).perform(click());ready(app);assertNull("Test server failure",serverFailure.get());
                    onView(withText("3번째 곡 · 실패 · 다시 시도할 수 있어요")).perform(scrollTo()).check(matches(isDisplayed()));onView(withText("5번째 곡 · 사용자가 제외")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withText("완료 1곡 · 실패 1곡 · 제외 1곡 · 대기 0곡 · 확인 필요 0곡")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withText("남은 곡 이어서 보내기")).perform(scrollTo(),click());onView(withText("남은 곡을 보낼까요?")).inRoot(isDialog()).check(matches(isDisplayed()));assertEquals(0,retries.get());
                    onView(withText("계속 보내기")).inRoot(isDialog()).perform(click());ready(app);assertNull("Test server failure",serverFailure.get());
                    onView(withText("완료 2곡 · 실패 0곡 · 제외 1곡 · 대기 0곡 · 확인 필요 0곡")).perform(scrollTo()).check(matches(isDisplayed()));onView(withText("남은 곡 이어서 보내기")).check(doesNotExist());
                    app.recreate();ready(app);focused(app);onView(withText("5번째 곡 · 사용자가 제외")).perform(scrollTo()).check(matches(isDisplayed()));onView(withText("완료 2곡 · 실패 0곡 · 제외 1곡 · 대기 0곡 · 확인 필요 0곡")).perform(scrollTo()).check(matches(isDisplayed()));
                    assertNull("Test server failure",serverFailure.get());assertEquals(1,imports.get());assertEquals(2,saves.get());assertEquals(1,matches.get());assertEquals(1,reviews.get());assertEquals(1,transfers.get());assertEquals(1,retries.get());assertTrue(statusReads.get()>0);assertEquals(token,credentials.getString(base,""));
                }
            } finally {running.set(false);server.close();replies.get(2,TimeUnit.SECONDS);}
        } finally {running.set(false);worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);}
    }

    @Test public void oauthReturnDuringConnectionReadRefreshesAfterStaleResponse() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=37)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean running=new AtomicBoolean(true),connected=new AtomicBoolean();AtomicInteger connectionReads=new AtomicInteger();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        var worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);String base="http://127.0.0.1:"+server.getLocalPort(),token="HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH";
            var replies=worker.submit(()->{
                while(running.get())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    String[] request=input.readLine().split(" ");assertEquals("GET",request[0]);String header;boolean authenticated=false;
                    while(!(header=input.readLine()).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:")&&header.endsWith("Bearer "+token))authenticated=true;
                    assertTrue("The callback must keep the same account session",authenticated);String content;
                    switch(request[1]) {
                        case "/health":content="{\"status\":\"ok\"}";break;
                        case "/accounts/me":content="{\"name\":\"복귀 검사\",\"email\":\"callback@example.invalid\"}";break;
                        case "/connections/config":content="{\"providers\":[{\"provider\":\"spotify\",\"configured\":true}]}";break;
                        case "/connections":
                            int read=connectionReads.incrementAndGet();content=connected.get()?"[{\"provider\":\"spotify\",\"connected\":true}]":"[]";
                            if(read==1){received.countDown();assertTrue("Release the old connection snapshot",release.await(15,TimeUnit.SECONDS));}break;
                        default:throw new AssertionError("Unexpected test path: "+request[1]);
                    }
                    byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                }catch(SocketTimeoutException timeout){
                    // Wait for the callback's follow-up read without external services.
                }catch(Throwable error){if(running.get())serverFailure.compareAndSet(null,error);return;}
            });
            try {
                assertTrue(settings.edit().putString("server",base).commit());assertTrue(credentials.edit().putString(base,token).commit());
                Uri callback=Uri.parse("taptap://connected?provider=spotify&status=connected");
                Intent callbackIntent=new Intent(Intent.ACTION_VIEW,callback).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.getPackageName());
                try(ActivityScenario<HubActivity> app=ActivityScenario.launch(callbackIntent)) {
                    assertTrue("Hold an account read before delivering the OAuth return",received.await(5,TimeUnit.SECONDS));
                    // Keep Scenario's intent match, but remove its launch-only CLEAR_TASK flag.
                    Intent returning=new Intent(callbackIntent).putExtra("nativeCallback",true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    assertNotNull("The manifest must resolve the OAuth return",returning.resolveActivity(context.getPackageManager()));
                    assertEquals(HubActivity.class.getName(),returning.resolveActivity(context.getPackageManager()).getClassName());
                    context.startActivity(returning);
                    waitUntil(()->{AtomicBoolean delivered=new AtomicBoolean();app.onActivity(a->delivered.set(a.getIntent().getBooleanExtra("nativeCallback",false)));return delivered.get();});
                    waitUntil(()->app.getState()==Lifecycle.State.RESUMED);focused(app);
                    app.recreate();connected.set(true);release.countDown();ready(app);
                    assertNull("Test server failure",serverFailure.get());assertEquals("Refresh once after the stale response",2,connectionReads.get());
                    onView(withText("연결됨")).perform(scrollTo()).check(matches(isDisplayed()));
                    onView(withText("연결 해제")).check(matches(isEnabled()));assertEquals(token,credentials.getString(base,""));
                }
                try(ActivityScenario<HubActivity> cold=ActivityScenario.launch(new Intent(context,HubActivity.class).setData(Uri.parse("taptap://connected?provider=spotify&status=connected")))) {
                    ready(cold);assertEquals("A cold callback must read connections only once",3,connectionReads.get());
                    onView(withText("연결됨")).perform(scrollTo()).check(matches(isDisplayed()));assertEquals(token,credentials.getString(base,""));
                }
                assertNull("Test server failure",serverFailure.get());
            } finally {running.set(false);release.countDown();server.close();replies.get(2,TimeUnit.SECONDS);}
        } finally {running.set(false);release.countDown();worker.shutdownNow();restore(settings,oldSettings);restore(credentials,oldCredentials);}
    }

    @Test public void accountPreservesAnonymousListsAndTimelineSavesSeparateSnapshots() throws Exception {
        String base=InstrumentationRegistry.getArguments().getString("fullServer","");assumeTrue("A dedicated test server is required",!base.isEmpty());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE),credentials=context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE);
        Map<String,?> oldSettings=settings.getAll(),oldCredentials=credentials.getAll();
        String suffix=UUID.randomUUID().toString(),email="check-"+suffix+"@example.invalid",password="native-check-only-"+suffix,title="영상 검사 "+suffix.substring(0,8);
        settings.edit().putString("server",base).commit();credentials.edit().remove(base).commit();ApiClient client=new ApiClient(context,base);client.connect();
        String anonymous=client.identity();String list=client.object("POST","/playlists",new JSONObject().put("title","익명 목록 "+suffix).put("id",UUID.randomUUID().toString())).getString("id");
        String track=client.array("/catalogue").getJSONObject(0).getString("id");client.object("POST","/playlists/"+list+"/items",new JSONObject().put("trackId",track));
        boolean registered=false;
        try {
            try(ActivityScenario<HubActivity> app=launch(context,"account")) {
                ready(app);assertEquals("Reading an anonymous account screen must preserve its owner",anonymous,client.identity());
                onView(withHint("이메일")).perform(scrollTo(),replaceText(email),closeSoftKeyboard());
                onView(withHint("비밀번호 (15자 이상)")).perform(scrollTo(),replaceText(password),closeSoftKeyboard());
                onView(withHint("이름 (회원가입)")).perform(scrollTo(),replaceText("기기 검사"),closeSoftKeyboard());
                onView(withText("회원가입")).perform(scrollTo(),click());ready(app);
                registered=true;assertNotEquals(anonymous,client.identity());client.connect();
                assertEquals(1,client.object("GET","/playlists/"+list,null).getJSONArray("items").length());
                onView(withText("로그아웃")).perform(scrollTo(),click());ready(app);client.connect();
                try{client.object("GET","/playlists/"+list,null);fail("A logged-out owner cannot read the account's lists");}catch(ApiClient.ApiError expected){assertEquals(404,expected.statusCode);}
                onView(withHint("이메일")).perform(scrollTo(),replaceText(email),closeSoftKeyboard());
                onView(withHint("비밀번호 (15자 이상)")).perform(scrollTo(),replaceText(password),closeSoftKeyboard());
                onView(withText("로그인")).perform(scrollTo(),click());ready(app);client.connect();
                assertEquals(email,client.object("GET","/accounts/me",null).getString("email"));
                assertEquals(1,client.object("GET","/playlists/"+list,null).getJSONArray("items").length());
            }
            JSONObject anotherTrack=client.array("/catalogue").getJSONObject(1);String anotherId=anotherTrack.getString("id");
            String deviceToken=client.object("POST","/accounts/login",new JSONObject().put("email",email).put("password",password)).getString("token");
            assertNotEquals("A second device has its own session",client.identity(),deviceToken);String extraItem=null;
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                readyMain(main);onView(withId(R.id.settings)).perform(click());onView(withText("좋아요·싫어요 관리 (0)")).perform(scrollTo()).check(matches(isDisplayed()));
                androidx.test.espresso.Espresso.pressBack();main.moveToState(Lifecycle.State.CREATED);
                deviceRequest(base,deviceToken,"PUT","/me/feedback/"+anotherId,new JSONObject().put("reaction",1));
                extraItem=deviceRequest(base,deviceToken,"POST","/playlists/"+list+"/items",new JSONObject().put("trackId",anotherId)).getString("id");
                main.moveToState(Lifecycle.State.RESUMED);readyMain(main);
                onView(withId(R.id.settings)).perform(click());onView(withText("좋아요·싫어요 관리 (1)")).perform(scrollTo(),click());
                onView(withText("좋아요 · "+anotherTrack.getString("title")+" — "+anotherTrack.getString("artist"))).check(matches(isDisplayed()));
                androidx.test.espresso.Espresso.pressBack();androidx.test.espresso.Espresso.pressBack();
                main.onActivity(a->{try{var field=MainActivity.class.getDeclaredField("savedTracks");field.setAccessible(true);assertTrue("The foreground must update saved-card state",((java.util.Set<?>)field.get(a)).contains(anotherId));}catch(ReflectiveOperationException error){throw new AssertionError(error);}});
                onView(withId(R.id.nav_library)).perform(click());readyMain(main);onView(withText("익명 목록 "+suffix+"\n2곡")).perform(scrollTo()).check(matches(isDisplayed()));
            } finally {
                deviceRequest(base,deviceToken,"DELETE","/me/feedback/"+anotherId,null);
                if(extraItem!=null)deviceRequest(base,deviceToken,"DELETE","/playlists/"+list+"/items/"+extraItem,null);
            }
            try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                ready(app);onView(withHint("YouTube 영상 주소")).perform(scrollTo(),replaceText("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),closeSoftKeyboard());
                onView(withHint("영상 설명의 시간표 붙여넣기 (선택)")).perform(scrollTo(),replaceText("00:00 Sample artist - Sample song\n01:00 Sample artist - Sample song"),closeSoftKeyboard());
                app.recreate();ready(app);focused(app);
                onView(withHint("YouTube 영상 주소")).check(matches(withText("https://www.youtube.com/watch?v=dQw4w9WgXcQ")));
                onView(withText("영상 시간표 확인")).perform(closeSoftKeyboard(),scrollTo(),click());ready(app);
                onView(withHint("새 목록 이름")).perform(scrollTo(),replaceText(title),closeSoftKeyboard());
                onView(allOf(withHint("곡명"),hasSibling(withText("1. 00:00")))).perform(scrollTo(),replaceText("Reviewed Sample"),closeSoftKeyboard());
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 00:00")))).perform(scrollTo(),replaceText("-1"),closeSoftKeyboard());
                timelineOpen(null);onView(withText("시작 시간이 영상 범위를 벗어났어요. 시간을 수정하거나 곡을 제외해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 00:00")))).perform(scrollTo(),replaceText("21601"),closeSoftKeyboard());
                timelineOpen(null);onView(withText("시작 시간이 영상 범위를 벗어났어요. 시간을 수정하거나 곡을 제외해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 00:00")))).perform(scrollTo(),replaceText("10"),closeSoftKeyboard());
                timelineOpen("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s");
                onView(withText("선택한 곡을 앱 목록에 저장")).perform(scrollTo(),click());ready(app);
            }
            JSONArray lists=client.array("/playlists");String converted=null;
            for(int i=0;i<lists.length();i++)if(lists.getJSONObject(i).getString("title").equals(title))converted=lists.getJSONObject(i).getString("id");
            assertNotNull("The edited timeline must create a real app playlist",converted);
            JSONArray items=client.object("GET","/playlists/"+converted,null).getJSONArray("items");assertEquals(2,items.length());
            assertTrue(items.getJSONObject(0).isNull("trackId"));assertTrue(items.getJSONObject(1).isNull("trackId"));
            assertNotEquals(items.getJSONObject(0).getString("id"),items.getJSONObject(1).getString("id"));
            assertEquals("Reviewed Sample",items.getJSONObject(0).getJSONObject("track").getString("title"));
            try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                ready(app);onView(withText("이전 변환 · 가져오기 · 전송 기록")).perform(scrollTo(),click());ready(app);
                onView(withText("목록 검토 · 변경 확인")).perform(scrollTo(),click());ready(app);
                onView(allOf(withHint("곡명"),hasSibling(withText("1. 00:00")))).check(matches(withText("Reviewed Sample")));
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 00:00")))).check(matches(withText("10")));
                timelineOpen("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s");
                onView(withHint("새 목록 이름")).perform(scrollTo(),replaceText("미제출 기록 초안"),closeSoftKeyboard());app.recreate();ready(app);
                onView(withHint("새 목록 이름")).check(matches(withText("미제출 기록 초안")));
                timelineOpen("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s");
            }
            assertEquals(40,client.array("/catalogue").length());
            assertEquals(1,client.array("/me/saved-tracks").length());
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                readyMain(main);onView(withId(R.id.nav_library)).perform(closeSoftKeyboard(),click());readyMain(main);
                onView(withText(title+"\n2곡")).perform(scrollTo(),click());readyMain(main);
                onView(withText("1. Reviewed Sample\nSample artist · 가져온 곡")).check(matches(isDisplayed()));
                onView(withText("1. Reviewed Sample\nSample artist · 가져온 곡")).perform(scrollTo(),click());
                onView(withText("곡 정보 수정")).perform(click());
                onView(withHint("곡명")).perform(replaceText("Edited Sample"),closeSoftKeyboard());
                onView(withHint("영상 시작 위치 · 초")).perform(replaceText("999999"),closeSoftKeyboard());
                onView(withText("저장")).perform(click());readyMain(main);
                onView(withHint("곡명")).check(matches(withText("Edited Sample")));
                onView(withHint("영상 시작 위치 · 초")).perform(replaceText("20"),closeSoftKeyboard());
                onView(withText("저장")).perform(click());readyMain(main);
                assertEquals("Edited Sample",client.object("GET","/playlists/"+converted,null).getJSONArray("items").getJSONObject(0).getJSONObject("track").getString("title"));
                onView(withText("공개 설정 · 공유 링크")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            String shareId;
            try(ActivityScenario<HubActivity> app=ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode","community").putExtra("playlistId",converted))) {
                ready(app);onView(withText("커뮤니티에 공개")).perform(scrollTo(),click());onView(withText("공개")).perform(click());ready(app);
                JSONObject publication=client.object("GET","/playlists/"+converted+"/publication",null);assertTrue(publication.getBoolean("public"));shareId=publication.getString("shareId");
                onView(withText("공개 링크 공유")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            try(ActivityScenario<HubActivity> app=launch(context,"community")) {
                ready(app);onView(withHint("공개 목록 검색")).perform(scrollTo(),replaceText(title),closeSoftKeyboard());onView(withText("검색")).perform(scrollTo(),click());ready(app);
                onView(withText("목록 열기")).perform(scrollTo(),click());ready(app);
                onView(withText("즐겨찾기")).perform(scrollTo(),click());ready(app);
                assertTrue(client.object("GET","/public/playlists/"+shareId,null).getBoolean("favourited"));
                onView(withText("즐겨찾기 해제")).check(matches(isDisplayed()));
            }
            String zeroId=UUID.randomUUID().toString(),zeroTitle="길이 미확인 "+suffix.substring(0,8);
            JSONObject zero=client.object("POST","/conversions/youtube",new JSONObject().put("id",zeroId)
                .put("url","https://www.youtube.com/watch?v=dQw4w9WgXcQ").put("durationSeconds",0)
                .put("timeline","01:00 First by Artist\n02:00 Second by Artist"));
            assertEquals(0,zero.getInt("durationSeconds"));
            assertFalse(zero.getJSONArray("entries").getJSONObject(0).getBoolean("needsReview"));
            assertEquals(120,zero.getJSONArray("entries").getJSONObject(0).getInt("endSeconds"));
            assertEquals(-1,zero.getJSONArray("entries").getJSONObject(1).getInt("endSeconds"));
            try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                ready(app);onView(withText("이전 변환 · 가져오기 · 전송 기록")).perform(scrollTo(),click());ready(app);
                onView(allOf(withText("목록 검토 · 변경 확인"),hasSibling(withText("사용자 타임라인")))).perform(scrollTo(),click());ready(app);
                timelineOpen("1. 01:00","https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=60s");
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 01:00")))).perform(scrollTo(),replaceText("21601"),closeSoftKeyboard());
                timelineOpen("1. 01:00",null);
                onView(withText("선택한 곡을 앱 목록에 저장")).perform(scrollTo(),click());ready(app);
                assertFalse("Out-of-range times must not be saved",client.object("GET","/imports/playlists/"+zeroId,null).getBoolean("saved"));
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 01:00")))).perform(scrollTo(),replaceText("90"),closeSoftKeyboard());
                onView(allOf(withHint("곡명"),hasSibling(withText("1. 01:00")))).perform(scrollTo(),replaceText("Reviewed unknown length"),closeSoftKeyboard());
                onView(withHint("새 목록 이름")).perform(scrollTo(),replaceText(zeroTitle),closeSoftKeyboard());
                onView(withText("선택한 곡을 앱 목록에 저장")).perform(scrollTo(),click());ready(app);
            }
            JSONObject savedZero=client.object("GET","/imports/playlists/"+zeroId,null);
            assertEquals(0,savedZero.getInt("durationSeconds"));
            String zeroPlaylist=savedZero.getString("localPlaylistId");
            JSONObject savedFirst=client.object("GET","/playlists/"+zeroPlaylist,null).getJSONArray("items").getJSONObject(0).getJSONObject("track");
            assertEquals(90,savedFirst.getInt("timestamp"));assertEquals(0,savedFirst.getInt("videoDurationSeconds"));
            assertEquals("Reviewed unknown length",savedFirst.getString("title"));
            try(ActivityScenario<HubActivity> app=launch(context,"create")) {
                ready(app);onView(withText("이전 변환 · 가져오기 · 전송 기록")).perform(scrollTo(),click());ready(app);
                onView(allOf(withText("목록 검토 · 변경 확인"),hasSibling(withText(zeroTitle)))).perform(scrollTo(),click());ready(app);
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 01:00")))).check(matches(withText("90")));
                onView(allOf(withHint("곡명"),hasSibling(withText("1. 01:00")))).check(matches(withText("Reviewed unknown length")));
                timelineOpen("1. 01:00","https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90s");
                app.recreate();ready(app);focused(app);
                onView(allOf(withHint("시작 시간 (초)"),hasSibling(withText("1. 01:00")))).check(matches(withText("90")));
                timelineOpen("1. 01:00","https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90s");
            }
            assertEquals(1,client.array("/me/saved-tracks").length());
            String accountOwner=client.identity();
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                readyMain(main);credentials.edit().remove(base).commit();
                onView(withId(R.id.settings)).perform(click());
                onView(withText("서버에 연결")).perform(closeSoftKeyboard(),scrollTo(),click());readyMain(main);
                onView(withHint("https://서버주소")).check(doesNotExist());
                assertFalse(client.identity().isEmpty());assertNotEquals(accountOwner,client.identity());
            } finally {credentials.edit().putString(base,accountOwner).commit();client.connect();}
            HubRequest stale=new HubRequest();var instrumentation=InstrumentationRegistry.getInstrumentation();
            instrumentation.runOnMainSync(()->stale.send(context,base,"owner-check","POST","/playlists",new JSONObject()));
            waitUntil(()->{AtomicBoolean idle=new AtomicBoolean();instrumentation.runOnMainSync(()->idle.set(!stale.busy()));return idle.get();});
            String owner=client.identity();credentials.edit().putString(base,String.join("",java.util.Collections.nCopies(43,"B"))).commit();
            try{instrumentation.runOnMainSync(()->{stale.retry(context,base);assertEquals("identity",stale.current().route);assertFalse(stale.busy());stale.onCleared();});}
            finally{credentials.edit().putString(base,owner).commit();client.connect();}
        } catch(Exception|AssertionError failure) {
            android.graphics.Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(screen!=null)try(var output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"hub-check-failure.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}catch(Exception ignored){}
            throw failure;
        } finally {
            try{if(registered)client.request("DELETE","/accounts/me",new JSONObject().put("password",password));else client.request("DELETE","/playlists/"+list,null);}catch(Exception ignored){}
            restore(settings,oldSettings);restore(credentials,oldCredentials);
        }
    }
    private static void timelineOpen(String expectedUrl) {
        timelineOpen("1. 00:00",expectedUrl);
    }
    private static void timelineOpen(String rowLabel,String expectedUrl) {
        var instrumentation=InstrumentationRegistry.getInstrumentation();AtomicReference<Intent> sent=new AtomicReference<>();
        androidx.test.runner.intent.IntentCallback callback=intent->{if(Intent.ACTION_VIEW.equals(intent.getAction()))sent.set(new Intent(intent));};
        var intents=androidx.test.runner.intent.IntentMonitorRegistry.getInstance();IntentFilter filter=new IntentFilter(Intent.ACTION_VIEW);filter.addDataScheme("https");filter.addDataScheme("http");filter.addCategory(Intent.CATEGORY_BROWSABLE);
        var blocked=instrumentation.addMonitor(filter,new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true);intents.addIntentCallback(callback);
        try {
            onView(allOf(withText("원본 영상의 이 시간 열기"),hasSibling(withText(rowLabel)))).perform(scrollTo(),click());instrumentation.waitForIdleSync();
            if(expectedUrl==null){assertNull("Invalid times must not launch an external activity",sent.get());assertEquals(0,blocked.getHits());}
            else{assertNotNull("Capture the actual external intent",sent.get());assertEquals(expectedUrl,sent.get().getDataString());assertEquals("Block the browser on both API24 and newer Android",1,blocked.getHits());}
        } finally {intents.removeIntentCallback(callback);instrumentation.removeMonitor(blocked);}
    }
    private static JSONObject deviceRequest(String base,String token,String method,String path,JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(base+path).openConnection();connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);try(var out=connection.getOutputStream()){out.write(bytes);}}
            int code=connection.getResponseCode();assertTrue("Second-device request failed with HTTP "+code,code>=200&&code<300);
            String response=ApiClient.read(connection.getInputStream());return response.trim().isEmpty()?new JSONObject():new JSONObject(response);
        } finally {connection.disconnect();}
    }
    private static String httpLine(DataInputStream input) throws java.io.IOException {
        StringBuilder line=new StringBuilder();int next;
        while((next=input.read())!=-1){if(next=='\n')return line.toString();if(next!='\r')line.append((char)next);if(line.length()>8192)throw new java.io.IOException("Test HTTP header is too long");}
        throw new java.io.EOFException("Incomplete test HTTP header");
    }
    private static ActivityScenario<HubActivity> launch(Context context,String mode){return ActivityScenario.launch(new Intent(context,HubActivity.class).putExtra("mode",mode));}
    private static void ready(ActivityScenario<HubActivity> app){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->{try{var f=HubActivity.class.getDeclaredField("requests");f.setAccessible(true);ready.set(!((HubRequest)f.get(a)).busy());}catch(Exception e){throw new AssertionError(e);}});return ready.get();});}
    private static void focused(ActivityScenario<HubActivity> app){waitUntil(()->{AtomicBoolean focused=new AtomicBoolean();app.onActivity(a->focused.set(a.hasWindowFocus()));return focused.get();});}
    private static void readyMain(ActivityScenario<MainActivity> app){waitUntil(()->{AtomicBoolean ready=new AtomicBoolean();app.onActivity(a->{try{var f=MainActivity.class.getDeclaredField("busy");f.setAccessible(true);ready.set(!f.getBoolean(a));}catch(Exception e){throw new AssertionError(e);}});return ready.get();});}
    private static void waitUntil(java.util.function.BooleanSupplier condition){long until=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<until){if(condition.getAsBoolean()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}throw new AssertionError("The app request did not finish.");}
    private static void restore(SharedPreferences prefs,Map<String,?> previous){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> row:previous.entrySet()){
        Object value=row.getValue();if(value instanceof String)edit.putString(row.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(row.getKey(),(Boolean)value);
        else if(value instanceof Integer)edit.putInt(row.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(row.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(row.getKey(),(Float)value);
        else if(value instanceof java.util.Set)edit.putStringSet(row.getKey(),(java.util.Set<String>)value);
    }edit.commit();}
}
