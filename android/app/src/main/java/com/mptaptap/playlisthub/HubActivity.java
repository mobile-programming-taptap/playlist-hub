package com.mptaptap.playlisthub;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Account and explicit-source creation flows; no imported metadata reaches AI. */
public final class HubActivity extends AppCompatActivity {
    private static final String[] PROVIDERS={"spotify","youtube"};
    private static final String[] PROVIDER_NAMES={"Spotify","YouTube Music"};
    private static final String[] SAVED_TEXT={"screen","matchFlow","exportStopped","exportConnect","exportSourceUrl","exportExpanded","importId","targetProvider","transferId","transferKey","saveId","matchedSaveId","sourceKey","importRequestId","conversionRequestId","videoUrl","timeline","timelineExpanded","linkExpanded","importExpanded","listUrl","listTitle","email","name","emailExpanded","kakaoAction","welcomeAction","sourceProvider","libraryProvider","libraryCursor","libraryRequestCursor","libraryReset","libraryLoaded","libraryConnect","batchActiveId","authorizationProvider","authorizationStatus","authorizationReason","authorizationNotice","authorizationNoticeProvider","authorizationRejected"};
    private HubRequest requests;
    private String base,mode;
    private LinearLayout content,bottomBar;
    private TextView headerTitle;
    private BottomNavigationView navigation;
    private TextView status;
    private final java.util.Map<String,TextView> connectionNotices=new java.util.HashMap<>();
    private MaterialButton retry;
    private LinearProgressIndicator progress;
    private final List<View> controls=new ArrayList<>();
    private final ArtworkLoader artwork=new ArtworkLoader();
    private final java.util.Set<AlertDialog> dialogs=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int transferPolls;
    private final Runnable transferRefresh=()->{if(resumed&&!requests.busy()&&value("screen").equals("transfer")&&transferPolls++<20)send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null);};

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);EdgeToEdge.enable(this);
        requests=new ViewModelProvider(this).get(HubRequest.class);
        mode=saved==null?getIntent().getStringExtra("mode"):saved.getString("hubMode",getIntent().getStringExtra("mode"));
        // In-app entries always pass a mode or callback data; an external launch may omit ACTION_MAIN (e.g. the installer's "Open").
        if(mode==null)mode="welcome";
        boolean callback=getIntent().getData()!=null;if(callback&&saved==null)mode="account";
        base=serverBase();
        boolean changed=requests.synchronize(this,base);
        if(requests.data.length()==0) {
            if(saved!=null&&!changed&&requests.fingerprint(this,base).equals(saved.getString("hubOwner",requests.fingerprint(this,base)))) {
                for(String key:SAVED_TEXT)if(saved.containsKey(key))put(key,saved.getString(key));
                restoreArray(saved,"edits");restoreArray(saved,"selections");restoreArray(saved,"libraryItems");restoreArray(saved,"librarySelections");restoreArray(saved,"batchImports");
            }
            if(!requests.data.has("screen"))put("screen",mode);
        }
        if(callback&&saved==null){requests.receiveAuthorization(this,base,getIntent().getData());if(value("exportConnect").equals("true"))mode="create";if(value("libraryConnect").equals("true")){mode="create";put("libraryProvider",value("authorizationProvider"));}put("screen","account");}
        else if(requests.current()==null&&!requests.data.optBoolean("authorizationPending"))requests.restoreAuthorization(this,base);
        if(saved!=null&&saved.getBoolean("kakaoPending")&&!requests.kakaoPending())put("kakaoInterrupted",true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundResource(R.drawable.discover_background);
        setContentView(root);if(saved==null){root.setFocusableInTouchMode(true);root.requestFocus();}
        android.graphics.drawable.LayerDrawable background=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{getDrawable(R.drawable.discover_background),new android.graphics.drawable.ColorDrawable(getColor(R.color.navigation_surface))});background.setLayerGravity(1,Gravity.BOTTOM);root.setBackground(background);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            background.setLayerHeight(1,bars.bottom);
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,insets.getInsets(WindowInsetsCompat.Type.ime()).bottom));return WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.WindowInsetsControllerCompat bars=ViewCompat.getWindowInsetsController(root);if(bars!=null){bars.setAppearanceLightStatusBars(true);bars.setAppearanceLightNavigationBars(false);}
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(8),dp(16),dp(8));
        MaterialButton back=button("",this::back);back.setOnClickListener(v->back());back.setContentDescription("돌아가기");back.setIconResource(R.drawable.ic_previous);back.setIconTint(android.content.res.ColorStateList.valueOf(getColor(R.color.discover_ink)));back.setIconPadding(0);back.setPadding(0,0,0,0);back.setMinWidth(0);back.setStrokeWidth(0);back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        headerTitle=text(mode.equals("account")?"내 계정":"목록 만들기",20,true);headerTitle.setTextColor(getColor(R.color.discover_ink));
        LinearLayout.LayoutParams titleSize=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1);titleSize.setMarginStart(dp(8));header.addView(headerTitle,titleSize);
        MaterialButton history=button("기록",()->{put("screen","history");render();reload();});history.setContentDescription("이전 변환 · 가져오기 · 전송 기록");history.setTextSize(13);history.setMinWidth(0);history.setStrokeWidth(0);history.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));history.setTextColor(getColor(R.color.discover_ink));history.setVisibility(mode.equals("create")?View.VISIBLE:View.GONE);history.setTag("historyAction");header.addView(history);
        root.addView(header);if(mode.equals("welcome"))header.setVisibility(View.GONE);
        progress=new LinearProgressIndicator(this);progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        NestedScrollView scroll=new NestedScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));bottomBar=column();bottomBar.setPadding(dp(20),0,dp(20),dp(12));bottomBar.setVisibility(View.GONE);root.addView(bottomBar,new LinearLayout.LayoutParams(-1,-2));
        scroll.setBackground(TapTapUi.rounded(this,R.color.background,28,false));scroll.setClipToOutline(true);
        content=column();content.setPadding(dp(20),dp(12),dp(20),dp(24));scroll.addView(content);
        navigation=new BottomNavigationView(this);navigation.setId(R.id.navigation);navigation.inflateMenu(R.menu.bottom_nav);navigation.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);navigation.setSelectedItemId(tabId());root.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        if(mode.equals("welcome"))navigation.setVisibility(View.GONE);
        navigation.setOnItemSelectedListener(item->{if(requests.busy())return false;int id=item.getItemId();if(id==R.id.nav_recommend||id==R.id.nav_library){setResult(RESULT_OK,new Intent().putExtra("selectTab",id==R.id.nav_library?"library":"recommend"));finish();return true;}mode=id==R.id.nav_account?"account":"create";put("screen",mode);render();scrollToTop();reload();return true;});
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){back();}});
        requests.states().observe(this,state->{
            if(state==null)return;busy(state.loading);
            if(state.loading)return;
            requests.consume(state);
            if(!requests.accept(this,base,state)){base=serverBase();requests.synchronize(this,base);put("screen",mode);render();if(!base.isEmpty())reload();return;}
            if(state.error!=null)failure(state.route,state.error);
            else delivered(state.route,state.value);
            if(!state.route.equals("auth"))refreshAuthorization();
        });
        render();
        if(base.isEmpty()){render();return;}
        if(requests.current()==null&&!requests.data.optBoolean("authorizationPending"))reload();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getData()!=null){if(requests.receiveAuthorization(this,base,intent.getData()))refreshAuthorization();else{render();if(!requests.busy()&&!base.isEmpty())reload();}}}
    @Override protected void onResume(){super.onResume();resumed=true;base=serverBase();if(requests!=null&&requests.synchronize(this,base)){put("screen",mode);render();if(!requests.busy()&&!base.isEmpty())reload();}else if(requests!=null){refreshAuthorization();if(value("screen").equals("transfer"))scheduleTransferRefresh();}}
    private void refreshAuthorization(){if(resumed&&!base.isEmpty()&&ApiClient.consentAccepted(this,base)&&requests.data.optBoolean("authorizationPending")&&!requests.busy()){put("authorizationPending",false);put("screen","account");send("account","GET","/accounts/me",null);}}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(transferRefresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(transferRefresh);for(AlertDialog dialog:dialogs)dialog.dismiss();dialogs.clear();artwork.close();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("hubMode",mode);
        out.putBoolean("kakaoPending",requests.kakaoPending());
        requests.synchronize(this,base);out.putString("hubOwner",requests.fingerprint(this,base));
        for(String key:SAVED_TEXT)if(requests.data.has(key))out.putString(key,requests.data.optString(key));
        JSONArray edited=new JSONArray(),edits=requests.data.optJSONArray("edits");
        if(edits!=null)for(int i=0;i<edits.length();i++){JSONObject row=edits.optJSONObject(i);if(row!=null&&row.optBoolean("edited"))edited.put(row);}
        out.putString("edits",edited.toString());
        out.putString("selections",ExportReview.savedSelections(requests.data.optJSONArray("selections")).toString());
        for(String key:new String[]{"libraryItems","librarySelections","batchImports"})if(requests.data.has(key))out.putString(key,array(key).toString());
        super.onSaveInstanceState(out);
    }

    private void reload() {
        if(base.isEmpty()||!ApiClient.consentAccepted(this,base))return;
        String screen=value("screen");
        if(screen.equals("account"))send("account","GET","/accounts/me",null);
        else if(screen.equals("library")&&!value("libraryLoaded").equals("true")){
            if(!value("authorizationNotice").isEmpty()){put("libraryConnect","true");send("account","GET","/accounts/me",null);}
            else loadLibraryPage("",true);
        }
        else if(screen.equals("history"))send("history","GET","/imports/playlists?summary=true",null);
        else if(screen.equals("export")&&!value("importId").isEmpty()){put("exportStopped","true");send("exportReview","GET","/matches/"+Uri.encode(value("importId"))+"?targetProvider="+Uri.encode(value("targetProvider")),null);}
        else if(screen.equals("individual")&&!value("importId").isEmpty()){put("exportStopped","true");
            send("individualRestore","GET","/matches/"+Uri.encode(value("importId"))+"?targetProvider=youtube",null);}
        else if(screen.equals("transfer")&&!value("transferId").isEmpty())send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null);
        else if((screen.equals("preview")||screen.equals("review")||screen.equals("sync"))&&!value("importId").isEmpty()) {
            if(screen.equals("review"))send("review","GET","/matches/"+Uri.encode(value("importId"))+"?targetProvider="+Uri.encode(value("targetProvider")),null);
            else send("import","GET","/imports/playlists/"+Uri.encode(value("importId")),null);
        }
    }
    private void back() {
        if(requests.busy()){if(requests.kakaoPending()){requests.cancelKakao(getApplicationContext());return;}message("처리가 끝날 때까지 잠시 기다려 주세요.");return;}
        if(value("screen").equals(mode)){finish();return;}
        put("screen",mode);render();reload();
    }
    private void render() {
        headerTitle.setText(mode.equals("account")?"내 계정":"목록 만들기");((View)headerTitle.getParent()).findViewWithTag("historyAction").setVisibility(mode.equals("create")?View.VISIBLE:View.GONE);
        controls.clear();connectionNotices.clear();content.removeAllViews();bottomBar.removeAllViews();bottomBar.setVisibility(View.GONE);retry=null;status=text("",14,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setVisibility(View.GONE);content.addView(status);
        if(mode.equals("welcome")){welcome();busy(requests.busy());return;}
        if(base.isEmpty()) {heading("TapTap 시작하기");paragraph("이용 안내를 확인하면 계정과 음악 서비스 기능을 사용할 수 있어요. 연결은 앱이 자동으로 처리해요.");
            addPrimaryButton(content,"서비스 이용 시작",()->{setResult(RESULT_OK,new Intent().putExtra("connectServer",true).putExtra("mode",mode));finish();});
            addButton(content,"추천 화면으로 돌아가기",this::finish);return;}
        if(!ApiClient.consentAccepted(this,base)){heading("서비스 이용 안내를 확인해 주세요");paragraph("계정 연결과 음악 서비스 기능을 사용하기 전에 약관과 개인정보 안내를 확인할 수 있어요.");addPrimaryButton(content,"이용 안내 확인",this::serviceConsent);addButton(content,"이 기기의 음악으로 돌아가기",()->{ApiClient.declineConsent(this,base);finish();});return;}
        switch(value("screen")) {
            case "account":account();break;
            case "library":library();break;
            case "export":export();break;
            case "batch":batchResult();break;
            case "history":history();break;
            case "preview":preview();break;
            case "review":review();break;
            case "individual":individualVideos();break;
            case "transfer":transfer();break;
            case "sync":sync();break;
            default:create();
        }
        busy(requests.busy());
        if((value("screen").equals("library")||value("screen").equals("account")&&value("authorizationNoticeProvider").isEmpty())&&!value("authorizationNotice").isEmpty())message(value("authorizationNotice"));
    }

    private void create() {
        heading("음악을 한곳에");paragraph("영상 속 음악부터 내 플레이리스트까지 모아보세요.");
        LinearLayout video=card(content);video.addView(text("영상에서 음악 목록 만들기",20,true));video.addView(text("주소만 넣으면 영상 설명이나 댓글의 시간표를 찾아요. 곡 정보를 확인한 뒤 내 목록에 저장할 수 있어요.",13,false));
        EditText url=createField(video,"영상 주소","YouTube 영상 주소","videoUrl",2048,false);
        addPrimaryButton(video,"영상에서 곡 목록 가져오기",()->convertVideo(url,null));
        LinearLayout manual=column();
        MaterialButton expand=button("",()->{});expand.setStrokeWidth(0);expand.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));expand.setTextColor(getColor(R.color.muted));
        expand.setOnClickListener(v->{
            if(requests.busy())return;
            boolean show=manual.getVisibility()!=View.VISIBLE;put("timelineExpanded",String.valueOf(show));manual.setVisibility(show?View.VISIBLE:View.GONE);expand.setText(show?"직접 입력 닫기":"직접 시간표 입력");
        });
        video.addView(expand,new LinearLayout.LayoutParams(-1,-2));video.addView(manual,new LinearLayout.LayoutParams(-1,-2));
        boolean expanded=value("timelineExpanded").equals("true");manual.setVisibility(expanded?View.VISIBLE:View.GONE);expand.setText(expanded?"직접 입력 닫기":"직접 시간표 입력");
        manual.addView(text("설명을 가져오기 어렵거나 직접 정리한 시간표가 있을 때 사용해요.",13,false));
        EditText timeline=createField(manual,"시간표","영상 설명의 시간표 붙여넣기","timeline",65536,true);
        addButton(manual,"입력한 시간표로 만들기",()->convertVideo(url,timeline));
        if(value("sourceProvider").isEmpty())put("sourceProvider","youtube");
        LinearLayout service=card(content);service.addView(text("내 플레이리스트 가져오기",20,true));service.addView(text("음악 계정을 연결하고 가져올 목록을 체크하세요. 선택한 목록은 내 목록에 각각 저장해요.",13,false));service.addView(text("음악 서비스",12,true));providerPicker(service,"sourceProvider");
        addPrimaryButton(service,"내 플레이리스트 선택",()->openLibrary(provider("sourceProvider")));
        if(array("batchImports").length()>0)addButton(service,"이전 가져오기 결과",()->{put("screen","batch");render();});
        LinearLayout link=column();MaterialButton linkToggle=button("",()->{});linkToggle.setStrokeWidth(0);linkToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));linkToggle.setTextColor(getColor(R.color.muted));
        boolean linkVisible=value("linkExpanded").equals("true");link.setVisibility(linkVisible?View.VISIBLE:View.GONE);linkToggle.setText(linkVisible?"링크 입력 닫기":"링크로 직접 가져오기");
        linkToggle.setOnClickListener(v->{if(requests.busy())return;boolean show=link.getVisibility()!=View.VISIBLE;put("linkExpanded",String.valueOf(show));link.setVisibility(show?View.VISIBLE:View.GONE);linkToggle.setText(show?"링크 입력 닫기":"링크로 직접 가져오기");});
        service.addView(linkToggle,new LinearLayout.LayoutParams(-1,-2));service.addView(link,new LinearLayout.LayoutParams(-1,-2));
        createField(link,"목록 주소","플레이리스트 주소","listUrl",2048,false);createField(link,"목록 이름","새 목록 이름","listTitle",80,false);
        addButton(link,"플레이리스트 가져오기",()->{
            if(provider("sourceProvider").isEmpty()){message("지원하는 음악 서비스를 선택해 주세요.");return;}
            if(value("listUrl").trim().isEmpty()){message("가져올 플레이리스트 주소를 입력해 주세요.");return;}
            beginSource(provider("sourceProvider")+":"+value("listUrl").trim()+":"+value("listTitle").trim(),"importRequestId");
            String id=value("importRequestId");
            put("edits",new JSONArray());put("selections",new JSONArray());
            send("import","POST","/imports/playlists",json("id",id,"provider",provider("sourceProvider"),"url",value("listUrl").trim(),"title",value("listTitle").trim()));
        });
        MaterialButton connection=button("계정과 음악 서비스 연결",()->{put("screen","account");render();reload();});connection.setStrokeWidth(0);connection.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));connection.setTextColor(getColor(R.color.muted));content.addView(connection,new LinearLayout.LayoutParams(-1,-2));
    }

    private void convertVideo(EditText url,EditText timeline) {
        String address=url.getText().toString().trim(),rows=timeline==null?null:timeline.getText().toString();
        if(address.isEmpty()){url.setError("영상 주소를 입력해 주세요.");return;}
        if(rows!=null&&rows.trim().isEmpty()){timeline.setError("시간표를 입력해 주세요.");return;}
        beginSource("youtube:"+address+":"+(rows==null?"automatic":"manual:"+rows),"conversionRequestId");
        JSONObject body=json("id",value("conversionRequestId"),"url",address);if(rows!=null)set(body,"timeline",rows);
        // The converted list takes its own title; a name left from an earlier import or link form must not name it.
        put("listTitle","");put("edits",new JSONArray());put("selections",new JSONArray());send("conversion","POST","/conversions/youtube",body);
    }

    private void openLibrary(String provider) {
        if(!supportedProvider(provider)){message("지원하는 음악 서비스를 선택해 주세요.");return;}
        if(!provider.equals(value("libraryProvider"))){clearLibrary();put("libraryProvider",provider);}
        put("screen","library");render();scrollToTop();loadLibraryPage("",true);
    }
    private void clearLibrary(){for(String key:new String[]{"libraryItems","librarySelections","libraryCursor","libraryRequestCursor","libraryReset","libraryLoaded"})requests.data.remove(key);}
    private void loadLibraryPage(String cursor,boolean reset) {
        String provider=value("libraryProvider");if(!supportedProvider(provider)){message("음악 서비스를 다시 선택해 주세요.");return;}
        put("libraryRequestCursor",cursor);put("libraryReset",String.valueOf(reset));
        send("library","GET","/connections/"+provider+"/playlists"+(cursor.isEmpty()?"":"?cursor="+Uri.encode(cursor)),null);
    }
    private void library() {
        String provider=value("libraryProvider");boolean loaded=value("libraryLoaded").equals("true");heading("내 플레이리스트");paragraph(providerName(provider)+"에서 직접 만든 목록을 가져와요.");
        if(provider.equals("youtube"))paragraph("좋아요·자동 생성 목록은 보이지 않을 수 있어요.");
        Runnable connect=()->{
            clearLibrary();requests.data.remove("batchImports");requests.data.remove("batchActiveId");put("libraryConnect","true");
            authorize(provider);
        };
        if(!loaded)addButton(content,providerName(provider)+" 계정 연결",connect);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);content.addView(tools);
        TextView counter=text(array("librarySelections").length()+"개 선택",14,true);counter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);tools.addView(counter,new LinearLayout.LayoutParams(0,-2,1));
        MaterialButton refresh=button("목록 새로고침",()->loadLibraryPage("",true));refresh.setTextSize(13);refresh.setMinWidth(0);refresh.setStrokeWidth(0);refresh.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));refresh.setTextColor(getColor(R.color.muted));tools.addView(refresh);
        addPrimaryButton(content,"선택한 목록 앱에 저장",this::startLibraryImport);
        addButton(content,"다른 플랫폼으로 보내기",this::startExport);
        JSONArray rows=array("libraryItems");
        if(rows.length()==0)paragraph(value("libraryLoaded").equals("true")?"연결된 계정에 가져올 플레이리스트가 없어요.":"계정을 연결하면 내 플레이리스트를 불러올 수 있어요.");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;String id=row.optString("id"),title=row.optString("title","이름 없는 플레이리스트");
            View line=LayoutInflater.from(this).inflate(R.layout.item_media_row,content,false);content.addView(line);
            android.widget.ImageView thumb=line.findViewById(R.id.row_artwork);thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER);thumb.setImageResource(R.drawable.ic_library);
            line.findViewById(R.id.row_trailing).setVisibility(View.GONE);
            CheckBox check=line.findViewById(R.id.row_check);check.setVisibility(View.VISIBLE);TapTapUi.checkbox(check);check.setContentDescription("선택: "+title);check.setChecked(find(array("librarySelections"),"id",id)!=null);controls.add(check);
            int count=row.optInt("itemCount",-1);String privacy=row.optString("privacyStatus");
            ((TextView)line.findViewById(R.id.row_title)).setText(title);
            ((TextView)line.findViewById(R.id.row_subtitle)).setText((count<0?"곡 수 확인 필요":count+"곡")+" · "+(privacy.equals("private")?"비공개":privacy.equals("public")?"공개":privacy.equals("unlisted")?"일부 공개":"공개 상태 확인 필요"));
            line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);controls.add(line);
            line.setOnClickListener(v->{if(!requests.busy())check.setChecked(!check.isChecked());});
            check.setOnCheckedChangeListener((v,checked)->{
                JSONArray selected=array("librarySelections");
                if(checked&&selected.length()>=20){check.setChecked(false);message("한 번에 20개 목록까지 선택할 수 있어요.");return;}
                if(checked){if(find(selected,"id",id)==null)selected.put(json("id",id,"title",title,"sourceUrl",row.optString("sourceUrl")));}
                else{JSONArray remaining=new JSONArray();for(int j=0;j<selected.length();j++){JSONObject item=selected.optJSONObject(j);if(item!=null&&!item.optString("id").equals(id))remaining.put(item);}put("librarySelections",remaining);}
                counter.setText(array("librarySelections").length()+"개 선택");
            });
        }
        if(!value("libraryCursor").isEmpty())addButton(content,"다음 목록 불러오기",()->loadLibraryPage(value("libraryCursor"),false));
        if(rows.length()>=200)paragraph("200개 목록까지 표시해요. 다른 목록은 링크로 직접 가져올 수 있어요.");
        if(loaded)addButton(content,providerName(provider)+" 계정 연결",connect);
    }
    private void startLibraryImport() {
        JSONArray selected=array("librarySelections");String provider=value("libraryProvider");
        if(!supportedProvider(provider)){message("음악 서비스를 다시 선택해 주세요.");return;}
        if(selected.length()==0||selected.length()>20){message("가져올 목록을 1~20개 선택해 주세요.");return;}
        // ponytail: bound queue/Bundle size; larger imports can run in separate batches.
        JSONArray queue=new JSONArray();for(int i=0;i<selected.length();i++) {
            JSONObject row=selected.optJSONObject(i);if(row==null)continue;String title=row.optString("title").trim();if(title.isEmpty())title="가져온 음악";
            if(title.length()>80){title=title.substring(0,80);if(Character.isHighSurrogate(title.charAt(title.length()-1)))title=title.substring(0,title.length()-1);}
            queue.put(json("requestId",UUID.randomUUID().toString(),"provider",provider,"url",row.optString("sourceUrl"),"title",title,"status","PENDING"));
        }
        clearSource();requests.data.remove("sourceKey");put("batchImports",queue);put("screen","batch");render();scrollToTop();nextLibraryImport();
    }
    private void nextLibraryImport() {
        JSONArray queue=array("batchImports");for(int i=0;i<queue.length();i++) {
            JSONObject row=queue.optJSONObject(i);if(row==null||row.optString("status").equals("DONE")||row.optString("status").equals("EMPTY"))continue;
            set(row,"status","IMPORTING");set(row,"notice","");put("batchActiveId",row.optString("requestId"));put("screen","batch");render();
            send("batchImport","POST","/imports/playlists",json("id",row.optString("requestId"),"provider",row.optString("provider"),"url",row.optString("url"),"title",row.optString("title")));return;
        }
        requests.data.remove("batchActiveId");put("librarySelections",new JSONArray());render();
    }
    private void startExport() {
        JSONArray selected=array("librarySelections");String source=value("libraryProvider");
        if(selected.length()!=1){message("보내기는 한 번에 한 목록씩 할 수 있어요.");return;}
        String target="";for(String provider:PROVIDERS)if(!provider.equals(source))target=provider;
        if(!supportedProvider(source)||target.isEmpty()){message("음악 서비스를 다시 선택해 주세요.");return;}
        JSONObject row=selected.optJSONObject(0);String title=row==null?"":row.optString("title").trim();if(title.isEmpty())title="가져온 음악";
        if(title.length()>80){title=title.substring(0,80);if(Character.isHighSurrogate(title.charAt(title.length()-1)))title=title.substring(0,title.length()-1);}
        clearSource();requests.data.remove("sourceKey");put("targetProvider",target);put("listTitle",title);put("exportSourceUrl",row==null?"":row.optString("sourceUrl"));put("exportStopped","false");put("exportExpanded","false");
        send("exportAccount","GET","/connections",null);
    }
    private void beginExportImport() {
        put("importRequestId",UUID.randomUUID().toString());put("screen","export");render();scrollToTop();
        send("exportImport","POST","/imports/playlists",json("id",value("importRequestId"),"provider",value("libraryProvider"),"url",value("exportSourceUrl"),"title",value("listTitle").trim()));
    }
    private boolean exportMatching(){
        if(!value("matchFlow").equals("export"))return false;
        put("matchFlow","");put("exportSourceUrl","");put("exportStopped","false");put("exportExpanded","false");put("screen","export");render();scrollToTop();nextExportMatch(0);return true;
    }
    private void nextExportMatch(int offset){send("exportMatch","POST","/matches",json("importId",value("importId"),"targetProvider",value("targetProvider"),"offset",offset));}
    private void export() {
        JSONObject review=requests.data.optJSONObject("review");String target=providerName(value("targetProvider"));
        heading(target+(value("targetProvider").equals("spotify")?"로":"으로")+" 보내기");
        if(value("importId").isEmpty()){paragraph("목록을 가져오고 있어요");return;}
        JSONArray items=review==null?null:review.optJSONArray("items");int total=items==null?previewEntries(requests.data.optJSONObject("imported")).length():items.length(),next=review==null?0:review.optInt("nextOffset",-1);
        if(next>=0) {
            paragraph(total+"곡 중 "+next+"곡 찾는 중");
            if(value("exportStopped").equals("true"))addPrimaryButton(content,"이어서 찾기",()->{put("exportStopped","false");render();nextExportMatch(next);});
            else{MaterialButton stop=button("중단",()->{});controls.remove(stop);stop.setOnClickListener(v->{put("exportStopped","true");render();});content.addView(stop,new LinearLayout.LayoutParams(-1,-2));}
        }
        if(items==null)return;
        field(content,"새 목록 이름","listTitle",80,false);
        JSONArray selections=array("selections");ExportReview.Groups groups=ExportReview.group(items,selections);
        if(!groups.needsReview().isEmpty()){content.addView(text("확인이 필요한 곡 ("+groups.needsReview().size()+")",16,true));
            for(JSONObject item:groups.needsReview()){int index=item.optInt("sourceIndex");LinearLayout box=card(content);box.addView(text((index+1)+". "+song(item.optJSONObject("source")),15,true));
                JSONArray candidates=item.optJSONArray("candidates");JSONObject top=candidates==null||candidates.length()==0?null:candidates.optJSONObject(0);JSONObject track=top==null?null:top.optJSONObject("track");
                if(track!=null)box.addView(artworkLine(box,track,"추천 후보: "+song(track)+(track.optString("album").isEmpty()?"":" · "+track.optString("album"))));
                else box.addView(text(item.optString("status").equals("NOT_SEARCHED")?"후보 조회 전 · 이어서 찾기를 눌러 주세요":"후보 없음",13,false));
                LinearLayout actions=new LinearLayout(this);box.addView(actions,new LinearLayout.LayoutParams(-1,-2));
                if(track!=null){MaterialButton pick=button("이 곡으로",()->{select(index,track,false);render();});actions.addView(pick,new LinearLayout.LayoutParams(0,-2,1));}
                if(candidates!=null&&candidates.length()>0){MaterialButton other=button("다른 후보",()->chooseCandidate(item,index));actions.addView(other,new LinearLayout.LayoutParams(0,-2,1));}
                MaterialButton skip=button("제외",()->{select(index,null,true);render();});actions.addView(skip,new LinearLayout.LayoutParams(0,-2,1));
            }}
        if(!groups.excluded().isEmpty()){content.addView(text("찾지 못한 곡 ("+groups.excluded().size()+") · 제외됨",16,true));
            for(JSONObject item:groups.excluded()){int index=item.optInt("sourceIndex");LinearLayout box=card(content);box.addView(artworkLine(box,item.optJSONObject("source"),(index+1)+". "+song(item.optJSONObject("source"))));box.addView(text("전송에서 제외됨",13,false));addButton(box,"다른 후보",()->chooseCandidate(item,index));}}
        if(!groups.selected().isEmpty()){boolean open=value("exportExpanded").equals("true");
            MaterialButton toggle=button("선택된 곡 ("+groups.selected().size()+")",()->{put("exportExpanded",String.valueOf(!value("exportExpanded").equals("true")));render();});toggle.setContentDescription("선택된 곡 "+groups.selected().size()+"곡 "+(open?"접기":"펼치기"));content.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
            if(open)for(JSONObject item:groups.selected()){int index=item.optInt("sourceIndex");JSONObject chosen=ExportReview.find(selections,index);LinearLayout box=card(content);JSONObject selected=selectedTrack(item,chosen);
                box.addView(artworkLine(box,selected==null?item.optJSONObject("source"):selected,(index+1)+". "+song(item.optJSONObject("source"))));
                box.addView(text(selectionLabel(item,chosen),15,true));
                addButton(box,"다른 후보",()->chooseCandidate(item,index));
                if(value("targetProvider").equals("youtube"))addButton(box,"선택한 YouTube 영상 확인",()->open("https://www.youtube.com/watch?v="+Uri.encode(chosen.optString("targetId"))));}
        }
        MaterialButton send=button(ExportReview.sendLabel(target,review,selections),this::approveTransfer);TapTapUi.primary(send);if(!ExportReview.canSend(review,selections)){controls.remove(send);send.setEnabled(false);}bottomBar.addView(send,new LinearLayout.LayoutParams(-1,-2));bottomBar.setVisibility(View.VISIBLE);
    }
    private void batchResult() {
        heading("가져오기 결과");JSONArray queue=array("batchImports");int completed=0;boolean remaining=false;
        for(int i=0;i<queue.length();i++){JSONObject row=queue.optJSONObject(i);if(row==null)continue;if(row.optString("status").equals("DONE"))completed++;else if(!row.optString("status").equals("EMPTY"))remaining=true;}
        paragraph(queue.length()+"개 중 "+completed+"개 저장 완료");
        for(int i=0;i<queue.length();i++) {
            JSONObject row=queue.optJSONObject(i);if(row==null)continue;LinearLayout box=card(content);box.addView(text(row.optString("title"),17,true));String state=row.optString("status");
            box.addView(text(state.equals("DONE")?row.optInt("size")+"곡 · 저장 완료":state.equals("EMPTY")?"가져올 곡 없음":state.equals("FAILED")?"가져오기 실패":state.equals("IMPORTING")?"가져오는 중":"대기 중",13,false));
            if(!row.optString("notice").isEmpty())box.addView(text(row.optString("notice"),13,false));
            if(state.equals("DONE"))addButton(box,"가져온 곡 확인",()->{
                clearSource();requests.data.remove("sourceKey");put("importId",row.optString("requestId"));put("listTitle",row.optString("title"));put("screen","preview");render();send("import","GET","/imports/playlists/"+Uri.encode(row.optString("requestId")),null);
            });
        }
        if(remaining)addPrimaryButton(content,"남은 목록 다시 가져오기",this::nextLibraryImport);
        addButton(content,"다른 플레이리스트 선택",()->openLibrary(value("libraryProvider")));
    }

    private void history() {
        heading("이전 음악 목록과 전송");paragraph("같은 계정의 저장된 작업을 다시 열어 검토하고, 원본 변경이나 남은 전송을 확인해요.");
        paragraph("기록만 삭제하면 내 목록에 저장한 음악과 음악 서비스의 목록은 유지돼요.");
        addButton(content,"기록 새로고침",this::reload);
        JSONObject history=requests.data.optJSONObject("history");if(history==null){paragraph("기록을 확인하고 있어요.");return;}
        heading("변환하거나 가져온 목록");JSONArray imports=history.optJSONArray("imports");
        if(imports==null||imports.length()==0)paragraph("아직 변환하거나 가져온 목록이 없어요.");
        if(imports!=null)for(int i=0;i<imports.length();i++) {
            JSONObject row=imports.optJSONObject(i);if(row==null)continue;String id=row.optString("id");if(id.isEmpty())continue;
            LinearLayout box=card(content);box.addView(text(row.optString("title","가져온 음악"),17,true));
            JSONArray items=row.optJSONArray("items");box.addView(text(providerName(row.optString("provider"))+" · "+row.optInt("size",items==null?0:items.length())+"곡"+(row.optBoolean("needsRefresh")?" · 원본 다시 확인 필요":""),13,false));
            addButton(box,"목록 검토 · 변경 확인",()->{
                clearSource();requests.data.remove("sourceKey");put("importId",id);put("listTitle",row.optString("title"));
                if(!row.optString("targetProvider").isEmpty())put("targetProvider",row.optString("targetProvider"));
                put("screen","preview");render();send("import","GET","/imports/playlists/"+Uri.encode(id),null);
            });
            addButton(box,"기록만 삭제",()->deleteHistory("import",id));
        }
        heading("플랫폼 전송 기록");JSONArray transfers=history.optJSONArray("transfers");
        if(transfers==null||transfers.length()==0)paragraph("아직 플랫폼으로 보낸 기록이 없어요.");
        if(transfers!=null)for(int i=0;i<transfers.length();i++) {
            JSONObject row=transfers.optJSONObject(i);if(row==null)continue;String id=row.optString("id");if(id.isEmpty())continue;
            LinearLayout box=card(content);box.addView(text(row.optString("title","플랫폼 전송"),17,true));String state=row.optString("status");
            box.addView(text(providerName(row.optString("provider"))+" · "+(state.equals("COMPLETED")?"전송 완료":state.equals("RUNNING")||state.equals("PENDING")?"진행 중":"남은 전송 확인 필요"),13,false));
            addButton(box,"전송 결과 · 이어서 보내기",()->{put("transferId",id);requests.data.remove("transfer");transferPolls=0;put("screen","transfer");render();reload();});
            addButton(box,"기록만 삭제",()->deleteHistory("transfer",id));
        }
    }

    private void deleteHistory(String kind,String id) {
        dialog().setTitle("이 기록을 삭제할까요?").setMessage("기록만 지워요. 내 목록에 저장한 음악과 음악 서비스의 목록은 유지돼요.")
                .setPositiveButton("기록만 삭제",(d,w)->{put("deletingKind",kind);put("deletingId",id);send("historyDeleted","DELETE",(kind.equals("import")?"/imports/playlists/":"/transfers/")+Uri.encode(id),null);})
                .setNegativeButton("취소",null).show();
    }

    private void welcome() {
        content.setPadding(dp(28),dp(48),dp(28),dp(32));
        TextView brand=text("TapTap",40,true);content.addView(brand);
        TextView subtitle=text("지금 이 순간,\n나에게 맞는 음악",28,true);subtitle.setPadding(0,dp(28),0,dp(12));content.addView(subtitle);
        paragraph("듣고 싶은 음악을 말하고,\n마음에 드는 곡은 내 목록에 모아보세요.");
        LinearLayout introduction=card(content);
        introduction.addView(text("내 음악을 이어가는 가장 쉬운 방법",16,true));
        introduction.addView(text("카카오로 로그인하면 다른 기기에서도\n저장한 음악 목록을 이어서 사용할 수 있어요.",14,false));
        MaterialButton login=kakaoButton(this::welcomeKakao);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,dp(56));size.setMargins(0,dp(32),0,0);content.addView(login,size);
        MaterialButton guest=button("게스트로 시작하기",this::beginGuest);guest.setTextColor(getColor(R.color.ink));guest.setStrokeWidth(0);
        content.addView(guest,new LinearLayout.LayoutParams(-1,dp(56)));
        paragraph("게스트로도 음악을 둘러볼 수 있어요.\n계정 메뉴에서 언제든 로그인할 수 있어요.");
        if(requests.data.optBoolean("kakaoInterrupted"))paragraph("카카오 로그인이 중단되었어요. 다시 시도하거나 게스트로 시작해 주세요.");
    }
    private MaterialButton kakaoButton(Runnable action) {
        MaterialButton login=button("카카오 로그인",action);login.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFEE500));login.setTextColor(0xD9000000);login.setStrokeWidth(0);login.setCornerRadius(dp(12));login.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.NORMAL);login.setIconResource(R.drawable.ic_kakao_symbol);login.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK));login.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);login.setIconPadding(dp(12));return login;
    }
    private final androidx.activity.result.ActivityResultLauncher<String> welcomePermission=registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),granted->{
        if(granted){if(value("welcomeAction").equals("guest"))beginGuest();else beginKakao(false);}
        else if(value("welcomeAction").equals("guest")){getSharedPreferences("settings",MODE_PRIVATE).edit().remove("server").putBoolean("serverSelectionMade",true).apply();enterApp();}
        else message("근처 기기 권한이 필요해요. 다시 로그인하거나 게스트로 시작할 수 있어요.");
    });
    private void welcomeKakao() {
        if(base.isEmpty()){message("로그인 서버 설정을 확인해 주세요. 게스트로는 계속 사용할 수 있어요.");return;}
        if(!ApiClient.consentAccepted(this,base)){
            TapTapUi.consent(this,dialogs,base,()->{try{ApiClient.acceptConsent(this,base);welcomeKakao();}catch(IllegalStateException error){message(error.getMessage());}},()->message("로그인을 취소했어요. 게스트로도 시작할 수 있어요."),this::open);return;
        }
        if(awaitWelcomePermission("kakao"))return;
        beginKakao(false);
    }
    private boolean awaitWelcomePermission(String action){
        if(android.os.Build.VERSION.SDK_INT>=37&&ApiClient.localHost(java.net.URI.create(base).getHost())&&checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)!=android.content.pm.PackageManager.PERMISSION_GRANTED){put("welcomeAction",action);welcomePermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK);return true;}return false;
    }
    private void beginGuest(){
        if(base.isEmpty()){enterApp();return;}
        if(!ApiClient.consentAccepted(this,base)){TapTapUi.consent(this,dialogs,base,()->{try{ApiClient.acceptConsent(this,base);beginGuest();}catch(IllegalStateException error){message(error.getMessage());}},()->{ApiClient.declineConsent(this,base);enterApp();},this::open);return;}
        if(awaitWelcomePermission("guest"))return;
        send("guest","GET","/accounts/me",null);
    }
    private void enterApp(){startActivity(new Intent(this,MainActivity.class));finish();}

    private void account() {
        JSONObject account=requests.data.optJSONObject("account"),profile=account==null?null:account.optJSONObject("profile");
        if(profile==null) {
            heading("내 음악을 이어가요");paragraph("카카오 계정으로 로그인하고, 다른 기기에서도 내 음악 목록을 이어서 관리해요.");
            if(account!=null&&account.optBoolean("sessionExpired"))paragraph("로그인이 만료됐어요. 다시 로그인하면 내 목록을 이어서 사용할 수 있어요.");
            LinearLayout kakao=card(content);kakao.addView(text("간편하게 시작하기",17,true));
            kakao.addView(kakaoButton(()->beginKakao(false)),new LinearLayout.LayoutParams(-1,dp(56)));
            kakao.addView(text("카카오톡이 없으면 카카오 계정으로 로그인할 수 있어요.",13,false));
            if(requests.data.optBoolean("kakaoInterrupted"))kakao.addView(text("카카오 로그인이 중단되었어요. 로그인 버튼을 다시 눌러 주세요.",13,false));
            MaterialButton emailToggle=button(value("emailExpanded").equals("true")?"이메일 로그인 닫기":"이메일로 로그인",()->{put("emailExpanded",String.valueOf(!value("emailExpanded").equals("true")));render();});emailToggle.setStrokeWidth(0);emailToggle.setTextColor(getColor(R.color.muted));content.addView(emailToggle,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout form=card(content);((View)form.getParent()).setVisibility(value("emailExpanded").equals("true")?View.VISIBLE:View.GONE);EditText email=createField(form,"이메일","이메일 주소","email",254,false);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            form.addView(text("비밀번호",12,true));EditText password=secret(form,"비밀번호 (15자 이상)");createField(form,"이름 · 회원가입 시","이름","name",80,false);
            addPrimaryButton(form,"로그인",()->authenticate(false,email,password));addButton(form,"회원가입",()->authenticate(true,email,password));
            if(account!=null)addButton(content,"이 기기의 서버 데이터 삭제",()->dialog().setTitle("서버 데이터를 삭제할까요?")
                .setMessage("로그인 없이 이 기기에서 서버에 저장한 목록·반응·음악 서비스 연결 정보를 삭제해요. 되돌릴 수 없어요. 외부 음악 서비스의 원본 목록과 이 기기에만 저장한 목록은 유지돼요. 음악 서비스에 허용한 앱 권한은 해당 서비스의 설정에서도 관리할 수 있어요.")
                .setPositiveButton("서버 데이터 삭제",(d,w)->send("signedOut","DELETE","/accounts/me",null)).setNegativeButton("취소",null).show());
        } else {
            heading(profile.optString("name","내 계정"));if(!profile.optString("email").isEmpty())paragraph(profile.optString("email"));
            boolean kakao=profile.optString("authProvider").equals("kakao");if(kakao)paragraph("카카오 계정으로 로그인됨");
            addButton(content,"로그아웃",()->send("signedOut","DELETE","/accounts/session",null));
            addButton(content,"계정 삭제",()->{
                if(kakao){dialog().setTitle("계정을 삭제할까요?").setMessage("카카오 계정을 다시 확인한 뒤 TapTap 계정과 연결 정보를 삭제해요. 삭제 후에는 되돌릴 수 없어요.").setPositiveButton("카카오로 확인 후 삭제",(d,w)->beginKakao(true)).setNegativeButton("취소",null).show();return;}
                LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);EditText password=secret(box,"현재 비밀번호");
                dialog().setTitle("계정을 삭제할까요?").setMessage("계정과 연결 정보가 삭제돼요. 삭제 후에는 되돌릴 수 없어요.").setView(box)
                        .setPositiveButton("계정 삭제",(d,w)->send("signedOut","DELETE","/accounts/me",json("password",password.getText().toString()))).setNegativeButton("취소",null).show();
            });
        }
        heading("음악 서비스 연결");
        JSONObject configuration=account==null?null:account.optJSONObject("configuration");
        JSONArray configured=configuration==null?new JSONArray():configuration.optJSONArray("providers");
        JSONArray connections=account==null?new JSONArray():account.optJSONArray("connections");
        for(int i=0;i<PROVIDERS.length;i++) {
            String provider=PROVIDERS[i];LinearLayout row=card(content);row.addView(text(PROVIDER_NAMES[i],17,true));
            JSONObject capability=find(configured,"provider",provider),connection=find(connections,"provider",provider);
            boolean enabled=capability!=null&&capability.optBoolean("configured"),connected=connection!=null&&connection.optBoolean("connected");
            row.addView(text(connected?"연결됨":enabled?"연결할 수 있어요":"서버에서 연결 설정이 필요해요",13,false));
            if(capability!=null&&!capability.optString("notice").isEmpty())row.addView(text(capability.optString("notice"),13,false));
            if(!enabled&&capability!=null&&!capability.optString("setupMessage").isEmpty())addButton(row,"필요한 연결 설정 확인",()->dialog().setTitle(providerName(provider)+" 연결 설정").setMessage(capability.optString("setupMessage")).setPositiveButton("확인",null).show());
            if(connected)addButton(row,"내 플레이리스트 선택",()->openLibrary(provider));
            if(connected||enabled)addButton(row,connected?"다시 인증하기":"브라우저에서 연결",()->authorize(provider));
            TextView notice=text("",14,false);
            notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            row.addView(notice);
            connectionNotices.put(provider,notice);
            updateConnectionNotice(provider,provider.equals(value("authorizationNoticeProvider"))?value("authorizationNotice"):"");
            if(connected)addButton(row,"연결 해제",()->send("disconnected","DELETE","/connections/"+provider,null));
        }
        addButton(content,"연결 상태 새로고침",()->send("account","GET","/accounts/me",null));
    }
    private void beginKakao(boolean delete){
        if(!TapTapApplication.kakaoConfigured()){
            dialog().setTitle("카카오 로그인 설정이 필요해요").setMessage("앱의 카카오 네이티브 앱 키를 설정한 뒤 다시 설치해 주세요. 카카오 개발자 설정에 앱 패키지와 키 해시도 등록해야 해요.").setPositiveButton("확인",null).show();return;
        }
        requests.data.remove("kakaoInterrupted");put("kakaoAction",delete?"delete":"login");send("kakaoConfiguration","GET","/accounts/kakao/config",null);
    }
    private void authorize(String provider){
        put("authorizationProvider",provider);
        authorizationMessage("연결을 시작하고 있어요…");
        send("auth","POST","/connections/"+provider+"/start",null);
    }
    private void authorizationMessage(String message){
        String provider=value("authorizationProvider"),previous=value("authorizationNoticeProvider");
        put("authorizationNoticeProvider",provider);
        put("authorizationNotice",message);
        if(!previous.equals(provider))updateConnectionNotice(previous,"");
        if(connectionNotices.containsKey(provider))updateConnectionNotice(provider,message);
        else message(message);
    }
    private void updateConnectionNotice(String provider,String message){
        TextView notice=connectionNotices.get(provider);
        if(notice==null)return;
        notice.setText(message);
        notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void serviceConsent(){TapTapUi.consent(this,dialogs,base,()->{try{ApiClient.acceptConsent(this,base);render();if(requests.data.optBoolean("authorizationPending"))refreshAuthorization();else reload();}catch(IllegalStateException error){message(error.getMessage());}},()->{ApiClient.declineConsent(this,base);finish();},this::open);}
    private void authenticate(boolean register,EditText email,EditText password) {
        String address=email.getText().toString().trim(),secret=password.getText().toString();
        if(address.isEmpty()){email.setError("이메일을 입력해 주세요.");return;}
        if(secret.length()<15||secret.length()>128){password.setError("비밀번호는 15~128자로 입력해 주세요.");return;}
        JSONObject body=json("email",address,"password",secret);if(register)set(body,"name",value("name").trim());
        send("signedIn","POST",register?"/accounts/register":"/accounts/login",body);
    }

    private void preview() {
        JSONObject imported=requests.data.optJSONObject("imported");if(imported==null){paragraph("가져온 곡 정보를 확인하고 있어요.");return;}
        heading(imported.optString("title","가져온 음악"));
        if(imported.optBoolean("truncated"))paragraph("가져온 "+previewEntries(imported).length()+"곡을 확인하고 저장할 곡을 선택해 주세요.");
        if(!imported.optString("notice").isEmpty())paragraph(imported.optString("notice"));
        field(content,"새 목록 이름","listTitle",80,false);
        JSONArray entries=previewEntries(imported);boolean timeline=imported.has("entries");
        if(timeline) {
            List<JSONObject> ambiguous=new ArrayList<>();
            for(int i=0;i<entries.length();i++) {
                JSONObject row=entries.optJSONObject(i);
                if(row==null)continue;
                JSONArray reasons=row.optJSONArray("reviewReasons");
                if(reasons==null)continue;
                for(int j=0;j<reasons.length();j++){
                    if("아티스트·곡명 순서 확인".equals(reasons.optString(j))){
                        ambiguous.add(edit(row.optInt("sourceIndex",i),row));
                        break;
                    }
                }
            }
            if(!ambiguous.isEmpty())addButton(content,"곡명과 아티스트 순서 바꾸기",()->{
                for(JSONObject row:ambiguous){
                    String title=row.optString("title");
                    set(row,"title",row.optString("artist"));
                    set(row,"artist",title);
                    set(row,"edited",true);
                }
                render();
            });
        }
        List<CheckBox> includes=new ArrayList<>();CheckBox all=new CheckBox(this);all.setText("전체 선택");TapTapUi.checkbox(all);controls.add(all);content.addView(all);
        MaterialButton save=button("",this::saveImport);
        Runnable counted=()->{int n=0;for(CheckBox c:includes)if(c.isChecked())n++;save.setText("선택한 곡을 앱 목록에 저장 ("+n+"곡)");all.setChecked(n>0&&n==includes.size());};
        all.setOnClickListener(v->{boolean on=all.isChecked();for(CheckBox c:includes)c.setChecked(on);counted.run();});
        View[] expanded={null};
        for(int i=0;i<entries.length();i++) {
            JSONObject row=entries.optJSONObject(i);if(row==null)continue;int index=row.optInt("sourceIndex",i);
            JSONObject track=row.optJSONObject("track"),edit=edit(index,track==null?row:track);
            View item=LayoutInflater.from(this).inflate(R.layout.item_import_track,content,false);item.setTag("importRow "+(index+1));content.addView(item);LinearLayout box=item.findViewById(R.id.import_body);
            View header=item.findViewById(R.id.import_header);TextView heading=header.findViewById(R.id.row_title),meta=header.findViewById(R.id.row_subtitle),number=header.findViewById(R.id.row_index);
            number.setText(String.valueOf(index+1));number.setVisibility(View.VISIBLE);header.findViewById(R.id.row_trailing).setVisibility(View.GONE);
            artwork.loadTrack(header.findViewById(R.id.row_artwork),track==null?row:track);
            String label=timeline?row.optString("timestamp"):track==null?"":track.optString("album");
            Runnable summary=()->{String title=edit.optString("title").trim(),artist=edit.optString("artist").trim(),line=artist.isEmpty()?"아티스트 입력 필요":artist;heading.setText(title.isEmpty()?"곡명 입력 필요":title);meta.setText(label.isEmpty()?line:line+" · "+label);header.setContentDescription((index+1)+". "+heading.getText()+", "+meta.getText());};summary.run();
            if(row.optBoolean("needsReview"))item.findViewById(R.id.import_badge).setVisibility(View.VISIBLE);
            CheckBox include=header.findViewById(R.id.row_check);include.setVisibility(View.VISIBLE);TapTapUi.checkbox(include);include.setContentDescription((index+1)+"번 곡 포함");
            if(timeline&&row.optInt("seconds",row.optInt("startSeconds",-1))<0&&!edit.has("excluded"))set(edit,"excluded",true);
            if(timeline&&!edit.has("seconds"))set(edit,"seconds",row.optInt("seconds",row.optInt("startSeconds",-1)));
            include.setChecked(!edit.optBoolean("excluded"));header.setAlpha(edit.optBoolean("excluded")?0.5f:1f);
            include.setOnCheckedChangeListener((v,checked)->{set(edit,"excluded",!checked);set(edit,"edited",true);header.setAlpha(checked?1f:0.5f);counted.run();});controls.add(include);includes.add(include);
            // Edit fields are built on first expansion only, so long lists stay light.
            Runnable build=()->{
                if(box.getChildCount()>0)return;
                if(timeline&&!row.optString("sourceRow").isEmpty())addButton(box,"원문과 시간 보기",()->dialog().setTitle("영상 설명 원문").setMessage(row.optString("sourceRow")).setPositiveButton("닫기",null).show());
                rowField(box,"곡명",edit,"title").addTextChangedListener(watcher(text->summary.run()));rowField(box,"아티스트",edit,"artist").addTextChangedListener(watcher(text->summary.run()));
                if(timeline){EditText time=rowField(box,"시작 시간 (초)",edit,"seconds");time.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);}
                String sourceUrl=row.optString("sourceUrl",track==null?"":track.optString("sourceUrl"));
                if(timeline)addButton(box,"원본 영상의 이 시간 열기",()->{
                    try{String video=imported.optString("videoId");if(!video.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("영상 주소를 확인해 주세요.");open("https://www.youtube.com/watch?v="+video+"&t="+timelineSeconds(imported,edit)+"s");}
                    catch(IllegalArgumentException error){message(error.getMessage());}
                });
                else if(!sourceUrl.isEmpty())addButton(box,"원본 곡 열기",()->open(sourceUrl));
            };
            String key=imported.optString("id")+"#"+index;
            Runnable show=()->{build.run();box.setVisibility(View.VISIBLE);expanded[0]=box;};
            header.setOnClickListener(v->{if(requests.busy())return;boolean open=box.getVisibility()!=View.VISIBLE;if(expanded[0]!=null)expanded[0].setVisibility(View.GONE);expanded[0]=null;put("importExpanded",open?key:"");if(open)show.run();});controls.add(header);
            if(key.equals(value("importExpanded")))show.run();
        }
        counted.run();
        if(timeline)addPrimaryButton(content,"개별 YouTube 영상 찾기",()->{put("matchFlow","individual");put("targetProvider","youtube");if(value("listTitle").trim().isEmpty())put("listTitle",imported.optString("title","영상 속 음악").substring(0,Math.min(65,imported.optString("title","영상 속 음악").length()))+" · 개별 영상");saveImport(true);});
        content.addView(save,new LinearLayout.LayoutParams(-1,-2));
        heading("다른 플랫폼으로 옮기기");providerPicker(content,"targetProvider");
        paragraph("수정하고 선택한 곡을 앱 목록에 저장한 뒤 대상 서비스에서 같은 곡을 찾아요.");
        addButton(content,"대상 플랫폼의 곡 후보 찾기",()->{put("matchFlow","export");saveImport(true);});
        if(imported.has("reviews")&&supportedProvider(imported.optString("targetProvider")))addButton(content,"저장된 곡 후보 검토 이어보기",()->{put("targetProvider",imported.optString("targetProvider"));send("review","GET","/matches/"+Uri.encode(value("importId"))+"?targetProvider="+Uri.encode(value("targetProvider")),null);});
        if(!imported.has("entries")&&supportedProvider(imported.optString("provider")))addButton(content,"목록 변경 미리보기",()->send("sync","POST","/sync/"+Uri.encode(value("importId"))+"/preview",null));
    }
    private void saveImport(){saveImport(false);}
    private void saveImport(boolean thenMatch) {
        if(thenMatch&&provider("targetProvider").isEmpty()){message("지원하는 음악 서비스를 선택해 주세요.");return;}
        String title=value("listTitle").trim();if(title.isEmpty()){message("새 목록 이름을 입력해 주세요.");return;}
        JSONObject imported=requests.data.optJSONObject("imported");JSONArray entries=previewEntries(imported),edits=new JSONArray();int included=0;
        for(int i=0;i<entries.length();i++) {
            JSONObject row=entries.optJSONObject(i);if(row==null)continue;JSONObject track=row.optJSONObject("track"),edit=edit(row.optInt("sourceIndex",i),track==null?row:track);
            if(!edit.optBoolean("excluded")) {
                if(edit.optString("title").trim().isEmpty()){message("포함할 곡의 곡명을 입력하거나 곡을 제외해 주세요.");return;}
                if(imported.has("entries"))try{set(edit,"seconds",timelineSeconds(imported,edit));}catch(IllegalArgumentException error){message(error.getMessage());return;}
                included++;
            }
            JSONObject checked=json("sourceIndex",edit.optInt("sourceIndex"),"title",edit.optString("title"),"artist",edit.optString("artist"),"excluded",edit.optBoolean("excluded"));
            if(imported.has("entries")&&!edit.optBoolean("excluded"))set(checked,"seconds",edit.optInt("seconds"));
            edits.put(checked);
        }
        if(included==0){message("저장할 곡을 한 곡 이상 포함해 주세요.");return;}
        if(value("saveId").isEmpty())put("saveId",UUID.randomUUID().toString());
        boolean reviewOnly=thenMatch&&imported.has("entries");
        send(thenMatch?(reviewOnly?"reviewedForMatch":"savedForMatch"):"saved","POST","/imports/playlists/"+Uri.encode(value("importId"))+(reviewOnly?"/review":"/save"),json("title",title,"playlistId",value("saveId"),"entries",edits));
    }
    private int timelineSeconds(JSONObject imported,JSONObject edit) {
        int seconds;try{seconds=Integer.parseInt(edit.optString("seconds"));}catch(NumberFormatException error){throw new IllegalArgumentException("시작 시간을 초 단위로 입력하거나 곡을 제외해 주세요.");}
        int duration=imported.optInt("durationSeconds",-1);if(seconds<0||(duration>0?seconds>=duration:seconds>21600))throw new IllegalArgumentException("시작 시간이 영상 범위를 벗어났어요. 시간을 수정하거나 곡을 제외해 주세요.");return seconds;
    }

    private void findIndividualVideos(int offset) {
        send("individualMatch","POST","/matches",json("importId",value("importId"),"targetProvider","youtube","offset",offset));
    }

    private void individualVideos() {
        heading("개별 YouTube 영상");
        JSONObject review=requests.data.optJSONObject("review");
        JSONArray items=review==null?null:review.optJSONArray("items");
        int next=review==null?0:review.optInt("nextOffset",-1);
        if(next>=0||items==null){
            paragraph(items==null?"곡마다 YouTube 영상을 찾고 있어요.":items.length()+"곡 중 "+next+"곡 확인");
            // Each page spends YouTube search quota, so the search can stop between pages.
            if(value("exportStopped").equals("true"))addButton(content,"영상 찾기 이어서",()->{put("exportStopped","false");render();findIndividualVideos(Math.max(0,next));});
            else{MaterialButton stop=button("중단",()->{});controls.remove(stop);stop.setOnClickListener(v->{put("exportStopped","true");render();});content.addView(stop,new LinearLayout.LayoutParams(-1,-2));}
            return;
        }
        int found=0;
        put("selections",new JSONArray());
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            JSONArray candidates=item.optJSONArray("candidates");
            JSONObject first=candidates==null?null:candidates.optJSONObject(0);
            JSONObject track=first==null?null:first.optJSONObject("track");
            boolean missing=track==null||track.optString("externalId").isEmpty();
            select(item.optInt("sourceIndex",i),track,missing);
            if(!missing)found++;
        }
        paragraph("영상 "+found+"곡 · 찾지 못한 곡 "+(items.length()-found)+"곡");
        if(found>0){
            field(content,"새 목록 이름","listTitle",80,false);
            addPrimaryButton(content,"개별 영상 "+found+"곡 내 목록에 저장",()->approveTransfer(true));
        }
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            JSONObject selection=find(array("selections"),"sourceIndex",item.optInt("sourceIndex",i));
            LinearLayout box=card(content);
            JSONObject selected=selectedTrack(item,selection);
            box.addView(artworkLine(box,selected==null?item.optJSONObject("source"):selected,(i+1)+". "+song(item.optJSONObject("source"))));
            if(selection.optBoolean("excluded"))box.addView(text("찾지 못함",13,false));
            else {
                box.addView(text(selection.optString("label"),13,false));
                addButton(box,"YouTube 영상 열기",()->open("https://www.youtube.com/watch?v="+Uri.encode(selection.optString("targetId"))));
            }
        }
    }

    private void review() {
        JSONObject review=requests.data.optJSONObject("review");if(review==null){paragraph("대상 서비스의 곡 후보를 확인하고 있어요.");return;}
        heading("옮길 곡 확인");paragraph("각 곡의 후보를 선택하거나 제외해 주세요. 라이브·리믹스·커버 등 버전이 다르면 직접 확인해야 해요.");
        JSONArray items=review.optJSONArray("items");if(items==null)items=new JSONArray();
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;int index=item.optInt("sourceIndex",i);JSONObject source=item.optJSONObject("source");LinearLayout box=card(content);
            JSONObject chosen=find(requests.data.optJSONArray("selections"),"sourceIndex",index),selected=selectedTrack(item,chosen);
            box.addView(artworkLine(box,selected==null?source:selected,(index+1)+". "+song(source)));
            String label="곡 후보 상태를 확인하지 못했어요. 후보를 직접 확인해 주세요.";
            if(chosen!=null)label=chosen.optBoolean("excluded")?"전송에서 제외됨":"선택한 곡: "+selectionLabel(item,chosen);
            else switch(item.optString("status")) {
                case "MATCHED":label="일치 후보 있음 · 선택해 주세요";break;
                case "NEEDS_REVIEW":label="확인 필요 · 곡과 버전을 비교해 주세요";break;
                case "UNKNOWN":JSONArray candidates=item.optJSONArray("candidates");label=candidates==null||candidates.length()==0?"찾지 못함 · 이 곡을 제외할 수 있어요":"확인 필요 · 일치하는 곡을 확인하지 못했어요";break;
                case "NOT_SEARCHED":label="후보 조회 전 · 다음 곡의 후보를 찾아 주세요";break;
            }
            box.addView(text(label,13,false));
            addButton(box,"후보 선택 또는 제외",()->chooseCandidate(item,index));
            if(chosen!=null&&!chosen.optBoolean("excluded")&&provider("targetProvider").equals("youtube"))addButton(box,"선택한 YouTube 영상 확인",()->open("https://www.youtube.com/watch?v="+Uri.encode(chosen.optString("targetId"))));
        }
        if(review.optInt("nextOffset",-1)>=0)addButton(content,"다음 곡의 후보 찾기",()->send("review","POST","/matches",json("importId",value("importId"),"targetProvider",provider("targetProvider"),"offset",review.optInt("nextOffset"))));
        addButton(content,"일치한 후보를 한 번에 선택",()->{
            dialog().setTitle("일치한 후보를 선택할까요?").setMessage("같은 곡으로 확인된 후보를 선택해요. 나머지 곡은 직접 선택하거나 제외해야 해요.")
                    .setPositiveButton("선택",(d,w)->{JSONArray rows=review.optJSONArray("items");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i),selected=row==null?null:row.optJSONObject("selected");if(row!=null&&row.optString("status").equals("MATCHED")&&selected!=null)select(row.optInt("sourceIndex",i),selected,false);}render();}).setNegativeButton("취소",null).show();
        });
        field(content,"새 목록 이름","listTitle",80,false);
        if(provider("targetProvider").equals("youtube"))addPrimaryButton(content,"선택한 개별 영상을 내 목록에 저장",()->approveTransfer(true));
        addButton(content,"선택 확인 후 플레이리스트 보내기",this::approveTransfer);
    }
    private void chooseCandidate(JSONObject item,int index) {
        JSONArray candidates=item.optJSONArray("candidates");if(candidates==null)candidates=new JSONArray();
        String[] labels=new String[candidates.length()+1];
        for(int i=0;i<candidates.length();i++){JSONObject candidate=candidates.optJSONObject(i);JSONObject track=candidate==null?null:candidate.optJSONObject("track");labels[i]=song(track)+(track==null||track.optString("album").isEmpty()?"":" · "+track.optString("album"));}
        labels[candidates.length()]="이 곡은 전송에서 제외";JSONArray choices=candidates;
        var adapter=new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convertView,ViewGroup parent){
                JSONObject candidate=choices.optJSONObject(position);
                if(candidate==null)return super.getView(position,null,parent);
                return artworkLine(parent,candidate.optJSONObject("track"),labels[position]);
            }
        };
        dialog().setTitle("곡과 버전을 확인해 주세요").setAdapter(adapter,(d,which)->{
            if(which==choices.length())select(index,null,true);else {JSONObject candidate=choices.optJSONObject(which);select(index,candidate==null?null:candidate.optJSONObject("track"),false);}render();
        }).setNegativeButton("취소",null).show();
    }
    private void select(int index,JSONObject track,boolean excluded) {
        if(!excluded&&(track==null||track.optString("externalId").isEmpty())){message("선택할 수 있는 곡을 확인하지 못했어요.");return;}
        JSONArray selections=array("selections");JSONObject selection=find(selections,"sourceIndex",index);
        if(selection==null){selection=new JSONObject();set(selection,"sourceIndex",index);selections.put(selection);}
        set(selection,"targetId",excluded?"":track.optString("externalId"));set(selection,"excluded",excluded);set(selection,"label",excluded?"":song(track));set(selection,"auto",false);
    }
    private void approveTransfer(){approveTransfer(false);}
    private void approveTransfer(boolean local) {
        JSONObject review=requests.data.optJSONObject("review");JSONArray items=review==null?null:review.optJSONArray("items"),selections=array("selections");int included=0;
        if(items==null||selections.length()!=items.length()){message("모든 곡에 대해 후보를 선택하거나 제외해 주세요.");return;}
        JSONArray confirmed=new JSONArray();
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i),selection=item==null?null:find(selections,"sourceIndex",item.optInt("sourceIndex",i));if(selection==null){message("모든 곡에 대해 후보를 선택하거나 제외해 주세요.");return;}if(!selection.optBoolean("excluded"))included++;confirmed.put(json("sourceIndex",selection.optInt("sourceIndex"),"targetId",selection.optString("targetId"),"excluded",selection.optBoolean("excluded")));}
        if(included==0||value("listTitle").trim().isEmpty()){message("보낼 곡과 새 목록 이름을 확인해 주세요.");return;}
        send(local?"reviewSavedLocally":"reviewSaved","PUT","/matches/"+Uri.encode(value("importId")),json("targetProvider",provider("targetProvider"),"selections",confirmed));
    }
    private void transfer() {
        JSONObject transfer=requests.data.optJSONObject("transfer");heading("플레이리스트 전송 결과");
        if(transfer==null){paragraph("전송 기록을 확인하고 있어요.");addButton(content,"전송 상태 확인",()->send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null));return;}
        if(!supportedProvider(transfer.optString("provider"))){paragraph("이 음악 서비스의 전송은 더 이상 지원하지 않아요.");return;}
        JSONArray items=transfer.optJSONArray("items");int failed=0,success=0,pending=0,unknown=0;
        JSONObject review=requests.data.optJSONObject("review");JSONArray reviewItems=review!=null&&review.optString("importId").equals(transfer.optString("importId"))?review.optJSONArray("items"):null;
        for(int pass=0;pass<2&&items!=null;pass++)for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;String state=item.optString("status");if(state.equals("ADDED")!=(pass==1))continue;
            if(state.equals("ADDED"))success++;else if(state.equals("FAILED"))failed++;else if(state.equals("PENDING"))pending++;else if(state.equals("UNKNOWN"))unknown++;
            if(!item.has("sourceIndex"))set(item,"sourceIndex",i);
            JSONObject image=json("platform",transfer.optString("provider"),"externalId",item.optString("targetId"),"artworkUrl",item.optString("artworkUrl"));
            content.addView(artworkLine(content,image,ExportReview.transferLine(item,reviewItems)));
        }
        JSONArray excluded=transfer.optJSONArray("excludedSourceIndices");int excludedCount=excluded==null?0:excluded.length();
        if(excluded!=null)for(int i=0;i<excluded.length();i++){JSONObject row=ExportReview.find(reviewItems,excluded.optInt(i)),source=row==null?null:row.optJSONObject("source");content.addView(artworkLine(content,source,(source==null?(excluded.optInt(i)+1)+"번째 곡":song(source))+" · 사용자가 제외"));}
        paragraph("완료 "+success+"곡 · 실패 "+failed+"곡 · 제외 "+excludedCount+"곡 · 대기 "+pending+"곡 · 확인 필요 "+unknown+"곡");
        if(!transfer.optString("notice").isEmpty())paragraph(transfer.optString("notice"));
        if(!value("exportSourceUrl").isEmpty()&&transfer.optString("importId").equals(value("importId")))paragraph("앱의 내 목록에도 사본이 저장됐어요.");
        String playlistUrl=transfer.optString("playlistUrl");Uri playlist=Uri.parse(playlistUrl);String provider=transfer.optString("provider");
        String host=provider.equals("spotify")?"open.spotify.com":provider.equals("youtube")?"www.youtube.com":"";
        if(!host.isEmpty()&&"https".equals(playlist.getScheme())&&host.equals(playlist.getHost())&&playlist.getUserInfo()==null&&(playlist.getPort()==-1||playlist.getPort()==443))addButton(content,"생성된 목록 열기",()->open(playlistUrl));
        if(!transfer.optString("status").equals("RUNNING")&&!transfer.optString("status").equals("PENDING")&&(failed+pending+unknown)>0)addButton(content,"남은 곡 이어서 보내기",()->dialog().setTitle("남은 곡을 보낼까요?").setMessage("완료한 곡은 건너뛰고 대기·실패한 곡을 보내요. 결과가 불확실한 곡은 먼저 서비스에서 확인해요.").setPositiveButton("계속 보내기",(d,w)->{transferPolls=0;send("transfer","POST","/transfers/"+Uri.encode(value("transferId"))+"/retry",json("confirmed",true));}).setNegativeButton("취소",null).show());
        addButton(content,"전송 상태 새로고침",()->{transferPolls=0;send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null);});
    }
    private void sync() {
        JSONObject preview=requests.data.optJSONObject("sync");heading("원본 목록의 변경 내용");if(preview==null){paragraph("변경 내용을 확인하고 있어요.");return;}
        for(String key:new String[]{"added","removed","changed"}) {
            Object change=preview.opt(key);JSONArray rows=change instanceof JSONArray?(JSONArray)change:null;
            paragraph((key.equals("added")?"추가":key.equals("removed")?"삭제":"정보 변경")+" · "+(rows==null?preview.optInt(key):rows.length())+"곡");
            if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null){
                JSONObject track=row.optJSONObject("track");
                if(track==null)track=row;
                content.addView(artworkLine(content,track,song(track)));
            }}
        }
        JSONObject after=preview.optJSONObject("after");JSONArray afterItems=after==null?null:after.optJSONArray("items");
        if(afterItems!=null){heading("반영 후 곡 목록");for(int i=0;i<afterItems.length();i++){JSONObject row=afterItems.optJSONObject(i);if(row!=null)content.addView(artworkLine(content,row.optJSONObject("track"),(i+1)+". "+song(row.optJSONObject("track"))));}}
        addButton(content,"이 변경 내용을 반영",()->dialog().setTitle("가져온 목록에 반영할까요?").setMessage("위에서 확인한 추가·삭제·정보 변경을 반영해요.").setPositiveButton("반영",(d,w)->send("syncApplied","POST","/sync/"+Uri.encode(value("importId"))+"/apply",json("confirmed",true,"previewId",preview.optString("previewId")))).setNegativeButton("취소",null).show());
    }

    private void delivered(String route,JSONObject value) {
        switch(route) {
            case "guest":enterApp();return;
            case "kakaoConfiguration":
                if(!value.optBoolean("configured")){dialog().setTitle("카카오 로그인 설정이 필요해요").setMessage(value.optString("setupMessage","서버의 카카오 앱 설정을 먼저 확인해 주세요.")).setPositiveButton("확인",null).show();return;}
                requests.startKakao(this,base,value("kakaoAction").equals("delete"));return;
            case "kakaoFallback":requests.fallbackKakao(this);return;
            case "account":
                put("account",value);
                if(requests.data.optBoolean("authorizationPending")){render();return;}
                String authorizationProvider=value("authorizationProvider");
                if(!authorizationProvider.isEmpty()&&!value("authorizationRejected").equals("true")){
                    JSONObject linked=find(value.optJSONArray("connections"),"provider",authorizationProvider);
                    put("authorizationNoticeProvider",authorizationProvider);
                    boolean connected=linked!=null&&linked.optBoolean("connected");String outcome=value("authorizationStatus"),reason=value("authorizationReason");
                    if(outcome.equals("cancelled"))put("authorizationNotice","음악 계정 연결을 취소했어요."+(connected?" 기존 연결은 유지돼요.":" 다시 연결할 수 있어요."));
                    else if(outcome.equals("error"))put("authorizationNotice",authorizationFailure(reason)+(connected?" 기존 연결은 유지돼요.":""));
                    else put("authorizationNotice",connected?providerName(authorizationProvider)+" 연결을 확인했어요.":"음악 계정 연결을 확인하지 못했어요. 다시 연결해 주세요.");
                    requests.forgetAuthorization(this);requests.data.remove("authorizationProvider");requests.data.remove("authorizationStatus");requests.data.remove("authorizationReason");
                }
                if(value("exportConnect").equals("true")){
                    put("exportConnect","false");JSONObject connection=find(value.optJSONArray("connections"),"provider",value("targetProvider"));
                    if(connection!=null&&connection.optBoolean("connected")){beginExportImport();return;}
                    put("screen","library");render();if(value("authorizationNotice").isEmpty())message("음악 계정 연결을 마치지 못했어요. 다시 연결해 주세요.");return;
                }
                if(value("libraryConnect").equals("true")){
                    put("libraryConnect","false");JSONObject connection=find(value.optJSONArray("connections"),"provider",value("libraryProvider"));
                    if(connection!=null&&connection.optBoolean("connected")){openLibrary(value("libraryProvider"));return;}
                    put("screen","library");render();if(value("authorizationNotice").isEmpty())message("음악 계정 연결을 마치지 못했어요. 다시 연결해 주세요.");return;
                }
                put("screen","account");break;
            case "signedIn":if(mode.equals("welcome")){enterApp();return;}
            case "signedOut":requests.forgetAuthorization(this);requests.clearData();put("screen","account");render();send("account","GET","/accounts/me",null);return;
            case "history":put("history",value);put("screen","history");break;
            case "historyDeleted":
                if(value("deletingKind").equals("import")&&value("deletingId").equals(value("importId"))){clearSource();requests.data.remove("sourceKey");}
                else if(value("deletingKind").equals("transfer")&&value("deletingId").equals(value("transferId"))){requests.data.remove("transferId");requests.data.remove("transferKey");requests.data.remove("transfer");}
                requests.data.remove("deletingKind");requests.data.remove("deletingId");requests.data.remove("history");put("screen","history");render();reload();return;
            case "auth":
                try{requests.rememberAuthorization(this,base,value("authorizationProvider"),value("screen").equals("library")&&!value("exportConnect").equals("true"));}
                catch(IllegalStateException error){authorizationMessage(error.getMessage());return;}
                if(open(value.optString("authorizationUrl"),this::authorizationMessage))authorizationMessage("브라우저에서 연결을 마친 뒤 돌아와 주세요.");
                else{requests.forgetAuthorization(this);put("libraryConnect","false");put("authorizationPending",false);}
                return;
            case "disconnected":
                clearLibrary();requests.data.remove("batchImports");requests.data.remove("batchActiveId");put("libraryConnect","false");
                if(value.optBoolean("providerRevocationRequired")&&!value.optString("manageAccessUrl").isEmpty())dialog().setTitle("서비스 권한도 확인해 주세요").setMessage("TapTap 연결은 해제했어요. 음악 서비스의 연결 앱 설정에서도 권한을 해제할 수 있어요.").setPositiveButton("권한 설정 열기",(d,w)->open(value.optString("manageAccessUrl"))).setNegativeButton("닫기",null).show();
                send("account","GET","/accounts/me",null);return;
            case "library": {
                if(!value.optString("provider").equals(value("libraryProvider"))){message("음악 서비스가 변경됐어요. 목록을 다시 불러와 주세요.");return;}
                if(value("libraryReset").equals("true"))put("librarySelections",new JSONArray());
                JSONArray rows=value("libraryReset").equals("true")?new JSONArray():array("libraryItems"),page=value.optJSONArray("items");
                if(page!=null)for(int i=0;i<page.length()&&rows.length()<200;i++){
                    JSONObject row=page.optJSONObject(i);if(row==null||row.optString("id").isEmpty()||find(rows,"id",row.optString("id"))!=null)continue;
                    rows.put(json("id",row.optString("id"),"title",row.optString("title"),"itemCount",row.optInt("itemCount",-1),"privacyStatus",row.optString("privacyStatus"),"sourceUrl",row.optString("sourceUrl")));
                }
                String next=value.optString("nextCursor");if(rows.length()>=200||next.equals(value("libraryRequestCursor")))next="";
                put("libraryItems",rows);put("libraryCursor",next);put("libraryLoaded","true");put("screen","library");break;
            }
            case "exportAccount": {
                JSONObject connection=find(value.optJSONArray("connections"),"provider",value("targetProvider"));
                if(connection!=null&&connection.optBoolean("connected")){beginExportImport();return;}
                put("exportConnect","true");authorize(value("targetProvider"));return;
            }
            case "exportImport": {
                JSONArray items=value.optJSONArray("items");
                if(!value.optBoolean("saved")||items==null||items.length()==0){put("screen","library");render();message("가져올 곡이 없어요.");return;}
                put("imported",value);put("importId",value.optString("id"));if(value("listTitle").trim().isEmpty())put("listTitle",value.optString("title"));
                if(!value.optString("localPlaylistId").isEmpty()){put("saveId",value.optString("localPlaylistId"));setResult(RESULT_OK,new Intent().putExtra("playlistId",value.optString("localPlaylistId")));}
                put("screen","export");render();nextExportMatch(0);return;
            }
            case "exportMatch": {
                put("review",value);put("selections",ExportReview.autoSelect(value.optJSONArray("items"),array("selections")));
                int next=value.optInt("nextOffset",-1);put("screen","export");
                if(next>=0&&!value("exportStopped").equals("true")){render();nextExportMatch(next);return;}
                break;
            }
            case "exportReview":put("review",value);put("selections",ExportReview.autoSelect(value.optJSONArray("items"),array("selections")));put("screen","export");break;
            case "batchImport": {
                JSONObject row=find(array("batchImports"),"requestId",value("batchActiveId"));
                if(row==null||!row.optString("requestId").equals(value.optString("id"))){failure(route,new IllegalStateException("가져오기 결과를 확인하지 못했어요."));return;}
                boolean saved=value.optBoolean("saved")&&!value.optString("localPlaylistId").isEmpty();set(row,"status",saved?"DONE":"EMPTY");set(row,"localPlaylistId",value.optString("localPlaylistId"));set(row,"notice",value.optString("notice"));
                JSONArray items=value.optJSONArray("reviewedItems");if(items==null)items=value.optJSONArray("items");set(row,"size",items==null?0:items.length());
                if(saved)setResult(RESULT_OK,new Intent().putExtra("playlistId",value.optString("localPlaylistId")));
                nextLibraryImport();return;
            }
            case "conversion":case "import":
                if(route.equals("conversion")&&previewEntries(value).length()==0){put("screen","create");put("timelineExpanded","true");render();message(value.optString("notice","해당 영상은 변환할 수 없음"));return;}
                boolean fresh=!value.optString("id").equals(value("importId"));
                if(fresh){requests.data.remove("saveId");requests.data.remove("transferId");requests.data.remove("transferKey");}
                put("imported",value);put("importId",value.optString("id"));if(!value.optString("localPlaylistId").isEmpty()){put("saveId",value.optString("localPlaylistId"));setResult(RESULT_OK,new Intent().putExtra("playlistId",value.optString("localPlaylistId")));}if(value("listTitle").trim().isEmpty()&&!value.optString("title").isEmpty())put("listTitle",value.optString("title"));
                if(value.has("reviewedItems")) {
                    JSONArray reviewed=value.optJSONArray("reviewedItems"),originals=previewEntries(value);
                    for(int i=0;i<originals.length();i++){JSONObject row=originals.optJSONObject(i);if(row==null)continue;int index=row.optInt("sourceIndex",i);if(find(array("edits"),"sourceIndex",index)!=null)continue;JSONObject saved=find(reviewed,"sourceIndex",index),track=saved==null?null:saved.optJSONObject("track"),original=row.optJSONObject("track");if(original==null)original=row;
                        JSONObject restored=json("sourceIndex",index,"excluded",saved==null,"edited",false,"title",track==null?original.optString("title"):track.optString("title"),"artist",track==null?original.optString("artist"):track.optString("artist"));
                        if(saved!=null&&saved.has("timestamp"))set(restored,"seconds",saved.optInt("timestamp"));array("edits").put(restored);
                    }
                }
                put("screen","preview");break;
            case "review":put("review",value);restoreApprovals(value);put("screen","review");break;
            case "individualMatch":
                put("review",value);
                int nextVideoOffset=value.optInt("nextOffset",-1);
                if(nextVideoOffset>=0&&!value("exportStopped").equals("true")){render();findIndividualVideos(nextVideoOffset);return;}
                render();scrollToTop();return;
            case "individualRestore":put("review",value);break;
            case "reviewedForMatch":
                put("selections",new JSONArray());if(exportMatching())return;
                if(value("matchFlow").equals("individual")){
                    requests.data.remove("review");put("exportStopped","false");put("screen","individual");render();scrollToTop();findIndividualVideos(0);return;
                }
                send("review","POST","/matches",json("importId",value("importId"),"targetProvider",provider("targetProvider")));return;
            case "reviewSavedLocally":put("review",value);send("matchedSaved","POST","/matches/"+Uri.encode(value("importId"))+"/save",json("title",value("listTitle").trim()));return;
            case "savedForMatch":
                put("saveId",value.optString("id"));setResult(RESULT_OK,new Intent().putExtra("playlistId",value.optString("id")));put("selections",new JSONArray());if(exportMatching())return;send("review","POST","/matches",json("importId",value("importId"),"targetProvider",provider("targetProvider")));return;
            case "reviewSaved":
                put("review",value);dialog().setTitle("확인한 곡을 전송할까요?").setMessage("선택한 곡으로 "+providerName(provider("targetProvider"))+"에 ‘"+value("listTitle").trim()+"’ 목록을 만들어요. 제외한 곡은 보내지 않아요."+(value("screen").equals("export")?"\n자동 선택 "+ExportReview.autoCount(array("selections"))+"곡 포함":""))
                    .setPositiveButton("전송",(d,w)->{String key=transferKey();if(!key.equals(value("transferKey"))||value("transferId").isEmpty()){put("transferKey",key);put("transferId",UUID.randomUUID().toString());}transferPolls=0;put("screen","transfer");render();send("transfer","POST","/transfers",json("id",value("transferId"),"importId",value("importId"),"targetProvider",provider("targetProvider"),"title",value("listTitle").trim(),"confirmed",true));}).setNegativeButton("취소",null).show();return;
            case "transfer":put("transfer",value);put("screen","transfer");scheduleTransferRefresh();break;
            case "sync":put("sync",value);put("screen","sync");break;
            case "syncApplied":put("imported",value);put("screen","preview");put("edits",new JSONArray());break;
            case "matchedSaved":case "saved": {
                if(route.equals("matchedSaved"))put("matchedSaveId",value.optString("id"));
                if(route.equals("saved"))put("saveId",value.optString("id"));
                String savedPlaylist=value.optString("id"),savedBase=base,savedOwner=requests.fingerprint(this,base);
                setResult(RESULT_OK,new Intent().putExtra("playlistId",savedPlaylist));
                dialog().setTitle("내 목록에 저장했어요").setMessage("저장한 음악은 추천 화면의 내 목록에서 확인할 수 있어요.").setPositiveButton("내 목록으로",(d,w)->{
                    setResult(RESULT_OK,new Intent().putExtra("openPlaylist",true).putExtra("playlistId",savedPlaylist).putExtra("serverBase",savedBase).putExtra("owner",savedOwner));finish();
                }).setNegativeButton("계속 보기",null).show();message("내 목록에 저장했어요.");return;
            }
        }
        render();
        if(route.equals("library")&&value("libraryReset").equals("true"))scrollToTop();
    }
    private String authorizationFailure(String reason){
        switch(reason){
            case "access_denied":return "음악 서비스 권한이 허용되지 않았어요. 다시 연결하고 필요한 권한을 허용해 주세요.";
            case "session_expired":return "음악 계정 연결 요청이 만료됐어요. 앱에서 연결을 다시 시작해 주세요.";
            case "provider_unauthorized":return "음악 서비스 인증을 확인하지 못했어요. 계정을 다시 연결해 주세요.";
            case "provider_forbidden":return "음악 서비스가 연결을 허용하지 않았어요. 계정의 권한과 YouTube 채널 설정을 확인해 주세요.";
            case "provider_rate_limited":return "음악 서비스 요청이 많아요. 잠시 후 다시 연결해 주세요.";
            case "provider_unavailable":return "음악 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
            default:return "음악 계정 연결을 완료하지 못했어요. 앱에서 다시 연결해 주세요.";
        }
    }
    private void scrollToTop(){content.post(()->{if(content.getParent() instanceof NestedScrollView)((NestedScrollView)content.getParent()).scrollTo(0,0);});}
    private void failure(String route,Exception error) {
        if(route.equals("kakaoAuthorization")){clearRetry();message(error.getMessage());return;}
        if(route.equals("signedIn")||route.equals("signedOut")){clearRetry();message(error.getMessage());return;}
        if(route.equals("identity")){put("screen",mode);render();message(error.getMessage());addButton(content,"현재 계정으로 다시 확인",this::reload);return;}
        if(route.equals("historyDeleted")&&error instanceof ApiClient.ApiError&&((ApiClient.ApiError)error).statusCode==404){delivered(route,new JSONObject());return;}
        String message=error instanceof ApiClient.ApiError?error.getMessage():"서버와 연결하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.";
        if(route.equals("batchImport")){JSONObject row=find(array("batchImports"),"requestId",value("batchActiveId"));if(row!=null){set(row,"status","FAILED");set(row,"notice",message);}put("screen","batch");render();message("가져오기를 멈췄어요. 저장한 목록은 유지되며 남은 목록만 다시 가져올 수 있어요.");return;}
        if(error instanceof ApiClient.ApiError&&((ApiClient.ApiError)error).statusCode==409&&message.contains("다시 연결")){message(message);showRecovery("음악 서비스 다시 연결",()->{mode="account";put("screen","account");render();reload();});return;}
        if(route.equals("library")){put("screen","library");render();message(message);return;}
        if(route.equals("individualMatch")||route.equals("individualRestore")){put("exportStopped","true");render();message(message);return;}
        if(route.equals("exportMatch")||route.equals("exportReview")){put("exportStopped","true");put("screen","export");render();message(message);return;}
        if(route.equals("exportImport")||route.equals("exportAccount")){put("screen","library");render();message(message);return;}
        if(route.equals("auth")){
            put("libraryConnect","false");put("exportConnect","false");
            authorizationMessage(message);
            return;
        }
        message(message);
    }
    private void clearRetry(){if(retry!=null){content.removeView(retry);controls.remove(retry);retry=null;}}
    private void showRecovery(String label,Runnable action){clearRetry();retry=button(label,action);content.addView(retry,new LinearLayout.LayoutParams(-1,-2));}
    private void beginSource(String source,String idKey){String key=UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();if(!key.equals(value("sourceKey"))){clearSource();put("sourceKey",key);}if(value(idKey).isEmpty())put(idKey,UUID.randomUUID().toString());}
    private void clearSource(){for(String key:new String[]{"importId","imported","importRequestId","conversionRequestId","saveId","transferId","transferKey","transfer","review","sync"})requests.data.remove(key);put("edits",new JSONArray());put("selections",new JSONArray());}
    private String transferKey(){JSONArray sorted=new JSONArray();JSONObject review=requests.data.optJSONObject("review");JSONArray items=review==null?null:review.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);JSONObject selected=item==null?null:find(array("selections"),"sourceIndex",item.optInt("sourceIndex",i));if(selected!=null)sorted.put(json("sourceIndex",selected.optInt("sourceIndex"),"targetId",selected.optString("targetId"),"excluded",selected.optBoolean("excluded")));}return UUID.nameUUIDFromBytes((value("importId")+":"+provider("targetProvider")+":"+value("listTitle").trim()+":"+sorted).getBytes(StandardCharsets.UTF_8)).toString();}
    private void restoreApprovals(JSONObject review){JSONArray items=review.optJSONArray("items");if(items==null)return;for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null||!item.optBoolean("approved")||find(array("selections"),"sourceIndex",item.optInt("sourceIndex",i))!=null)continue;boolean excluded=item.optString("status").equals("USER_EXCLUDED");if(excluded||item.optJSONObject("selected")!=null)select(item.optInt("sourceIndex",i),item.optJSONObject("selected"),excluded);}}
    private JSONObject selectedTrack(JSONObject item,JSONObject selection) {
        if(selection==null||selection.optBoolean("excluded"))return null;
        String id=selection.optString("targetId");
        JSONArray candidates=item.optJSONArray("candidates");
        if(candidates!=null)for(int i=0;i<candidates.length();i++){
            JSONObject candidate=candidates.optJSONObject(i);
            JSONObject track=candidate==null?null:candidate.optJSONObject("track");
            if(track!=null&&id.equals(track.optString("externalId")))return track;
        }
        JSONObject selected=item.optJSONObject("selected");
        return selected!=null&&id.equals(selected.optString("externalId"))?selected:null;
    }
    private View artworkLine(ViewGroup parent,JSONObject track,String label) {
        View row=LayoutInflater.from(this).inflate(R.layout.item_media_row,parent,false);
        row.setClickable(false);
        row.setFocusable(false);
        TextView title=row.findViewById(R.id.row_title);
        title.setText(label);
        title.setMaxLines(2);
        row.findViewById(R.id.row_subtitle).setVisibility(View.GONE);
        row.findViewById(R.id.row_trailing).setVisibility(View.GONE);
        artwork.loadTrack(row.findViewById(R.id.row_artwork),track);
        return row;
    }
    private String selectionLabel(JSONObject item,JSONObject selection){if(!selection.optString("label").isEmpty())return selection.optString("label");JSONObject track=selectedTrack(item,selection);return track==null?"곡 정보 확인 필요":song(track);}
    private void scheduleTransferRefresh(){handler.removeCallbacks(transferRefresh);JSONObject transfer=requests.data.optJSONObject("transfer");if(resumed&&transfer!=null&&supportedProvider(transfer.optString("provider"))&&(transfer.optString("status").equals("RUNNING")||transfer.optString("status").equals("PENDING"))&&transferPolls<20)handler.postDelayed(transferRefresh,2000);}
    private void send(String route,String method,String path,JSONObject body){if(base.isEmpty()){message("서비스 이용 안내를 확인해 주세요.");return;}if(!ApiClient.consentAccepted(this,base)){render();return;}if(body!=null&&(body.has("provider")&&!supportedProvider(body.optString("provider"))||body.has("targetProvider")&&!supportedProvider(body.optString("targetProvider")))){message("지원하는 음악 서비스를 선택해 주세요.");return;}requests.send(getApplicationContext(),base,route,method,path,body);}
    private void busy(boolean loading){if(progress==null)return;if(loading)clearRetry();progress.setVisibility(loading?View.VISIBLE:View.GONE);for(View control:controls)control.setEnabled(!loading);if(navigation!=null)for(int i=0;i<navigation.getMenu().size();i++)navigation.getMenu().getItem(i).setEnabled(!loading);if(headerTitle!=null){ViewGroup header=(ViewGroup)headerTitle.getParent();for(int i=0;i<header.getChildCount();i++)if(header.getChildAt(i) instanceof MaterialButton)header.getChildAt(i).setEnabled(!loading||i==0&&requests.kakaoPending());}if(loading)message(requests.kakaoPending()?"카카오 로그인 확인 중이에요. 돌아가기를 누르면 취소할 수 있어요.":"처리 중이에요…");else if(status!=null&&(status.getText().toString().equals("처리 중이에요…")||status.getText().toString().startsWith("카카오 로그인 확인 중"))){status.setText("");status.setVisibility(View.GONE);}}
    private void message(String message){if(status!=null){status.setText(message==null?"처리하지 못했어요.":message);status.setBackground(TapTapUi.rounded(this,R.color.surface,14,true));status.setPadding(dp(14),dp(12),dp(14),dp(12));status.setVisibility(View.VISIBLE);
        TextView shown=status;shown.post(()->{if(content.getParent() instanceof NestedScrollView)((NestedScrollView)content.getParent()).scrollTo(0,Math.max(0,shown.getTop()-dp(8)));});}}

    private LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private LinearLayout card(LinearLayout parent){MaterialCardView card=new MaterialCardView(this);card.setRadius(dp(20));card.setCardElevation(0);card.setCardBackgroundColor(getColor(R.color.surface));card.setStrokeWidth(dp(1));card.setStrokeColor(getColor(R.color.line));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(12),0,dp(8));parent.addView(card,params);LinearLayout box=column();box.setPadding(dp(18),dp(16),dp(18),dp(16));card.addView(box);return box;}
    private TextView text(String label,int size,boolean bold){AppCompatTextView view=new AppCompatTextView(this);view.setText(label);view.setTextSize(size);view.setTextColor(getColor(bold?R.color.ink:R.color.muted));view.setPadding(0,dp(6),0,dp(6));TapTapUi.font(view,bold);view.setLineSpacing(dp(3),1);return view;}
    private void heading(String label){content.addView(text(label,22,true));}
    private void paragraph(String label){content.addView(text(label,14,false));}
    private MaterialButton button(String label,Runnable action){MaterialButton button=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);button.setText(label);TapTapUi.button(button);button.setOnClickListener(v->{if(!requests.busy())action.run();});controls.add(button);return button;}
    private void addButton(LinearLayout box,String label,Runnable action){box.addView(button(label,action),new LinearLayout.LayoutParams(-1,-2));}
    private void addPrimaryButton(LinearLayout box,String label,Runnable action){MaterialButton button=button(label,action);TapTapUi.primary(button);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.setMargins(0,dp(12),0,0);box.addView(button,size);}
    private EditText createField(LinearLayout box,String label,String hint,String key,int max,boolean multiline){TextView caption=text(label,12,true);LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);spacing.setMargins(0,dp(10),0,dp(4));box.addView(caption,spacing);EditText view=field(box,hint,key,max,multiline);fillInput(view);view.setTextColor(getColor(R.color.ink));view.setHintTextColor(getColor(R.color.muted));view.setId(View.generateViewId());caption.setLabelFor(view.getId());return view;}
    private void fillInput(View view){if(view instanceof EditText){TapTapUi.input((EditText)view);return;}view.setBackground(TapTapUi.rounded(this,R.color.input_surface,14,true));view.setPadding(dp(14),0,dp(14),0);view.setMinimumHeight(dp(56));}
    private EditText field(LinearLayout box,String hint,String key,int max,boolean multiline){AppCompatEditText view=input(hint,max,multiline);view.setText(value(key));view.addTextChangedListener(watcher(text->put(key,text)));controls.add(view);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.setMargins(0,dp(6),0,dp(6));box.addView(view,size);return view;}
    private EditText rowField(LinearLayout box,String hint,JSONObject row,String key){AppCompatEditText view=input(hint,key.equals("seconds")?8:300,false);view.setId(View.generateViewId());TextView caption=text(hint,12,true);caption.setLabelFor(view.getId());box.addView(caption);view.setText(row.optString(key));if(!view.getText().toString().equals(row.optString(key))){set(row,key,view.getText().toString());set(row,"edited",true);}view.addTextChangedListener(watcher(text->{set(row,key,text);set(row,"edited",true);}));controls.add(view);box.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private AppCompatEditText input(String hint,int max,boolean multiline){AppCompatEditText view=new AppCompatEditText(this);view.setHint(hint);view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});view.setSingleLine(!multiline);if(multiline){view.setMinLines(3);view.setMaxLines(6);view.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);}TapTapUi.input(view);return view;}
    private EditText secret(LinearLayout box,String hint){AppCompatEditText view=input(hint,128,false);view.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);view.setSaveEnabled(false);controls.add(view);box.addView(view);return view;}
    private TextWatcher watcher(java.util.function.Consumer<String> action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){action.accept(s.toString());}public void afterTextChanged(Editable s){}};}
    private View providerPicker(LinearLayout box,String key){
        if(!value(key).isEmpty()&&!supportedProvider(value(key))){
            box.addView(text("이전에 선택한 서비스는 지원하지 않아요. 음악 서비스를 다시 선택해 주세요.",13,false));
            MaterialButton choose=button("음악 서비스 선택",()->dialog().setTitle("음악 서비스를 선택해 주세요").setItems(PROVIDER_NAMES,(d,which)->{put(key,PROVIDERS[which]);render();}).setNegativeButton("취소",null).show());box.addView(choose,new LinearLayout.LayoutParams(-1,-2));return choose;
        }
        LinearLayout toggle=new LinearLayout(this);MaterialButton[] options=new MaterialButton[PROVIDERS.length];
        for(int i=0;i<PROVIDERS.length;i++){String provider=PROVIDERS[i];options[i]=button(PROVIDER_NAMES[i],()->{put(key,provider);selectProvider(options,key);});options[i].setCheckable(true);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-2,1);if(i>0)size.setMarginStart(dp(8));toggle.addView(options[i],size);}
        selectProvider(options,key);box.addView(toggle,new LinearLayout.LayoutParams(-1,-2));return toggle;
    }
    private void selectProvider(MaterialButton[] options,String key){for(int i=0;i<options.length;i++){boolean selected=PROVIDERS[i].equals(provider(key));TapTapUi.button(options[i]);if(selected)TapTapUi.primary(options[i]);options[i].setChecked(selected);}}
    private boolean supportedProvider(String provider){for(String supported:PROVIDERS)if(supported.equals(provider))return true;return false;}
    private String provider(String key){String selected=value(key);return selected.isEmpty()?"spotify":supportedProvider(selected)?selected:"";}
    private String providerName(String provider){for(int i=0;i<PROVIDERS.length;i++)if(PROVIDERS[i].equals(provider))return PROVIDER_NAMES[i];return "지원하지 않는 음악 서비스";}
    private String serverBase(){String selected=getSharedPreferences("settings",MODE_PRIVATE).getString("server","");if(!selected.isEmpty())try{return ApiClient.validate(selected);}catch(IllegalArgumentException ignored){}return "";}
    private MaterialAlertDialogBuilder dialog(){return TapTapUi.dialog(this,dialogs);}
    private int tabId(){return mode.equals("account")?R.id.nav_account:R.id.nav_create;}
    private boolean open(String url){return open(url,this::message);}
    private boolean open(String url,java.util.function.Consumer<String> onFailure){try{Uri uri=Uri.parse(url);if(uri.getHost()==null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&ApiClient.localHost(uri.getHost())))throw new IllegalArgumentException();startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));return true;}catch(ActivityNotFoundException e){onFailure.accept("주소를 열 앱이나 브라우저가 없어요.");}catch(Exception e){onFailure.accept("연결 주소를 확인할 수 없어요.");}return false;}
    private String song(JSONObject track){return ExportReview.song(track);}
    private JSONArray previewEntries(JSONObject imported){if(imported==null)return new JSONArray();JSONArray entries=imported.optJSONArray("entries");if(entries==null)entries=imported.optJSONArray("items");return entries==null?new JSONArray():entries;}
    private JSONObject edit(int index,JSONObject original){JSONArray edits=array("edits");JSONObject edit=find(edits,"sourceIndex",index);if(edit==null){edit=json("sourceIndex",index,"title",original.optString("title"),"artist",original.optString("artist"));edits.put(edit);}return edit;}
    private JSONObject find(JSONArray rows,String key,Object value){if(rows==null)return null;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&String.valueOf(row.opt(key)).equals(String.valueOf(value)))return row;}return null;}
    private String value(String key){return requests.data.optString(key,"");}
    private JSONArray array(String key){JSONArray value=requests.data.optJSONArray(key);if(value==null){value=new JSONArray();put(key,value);}return value;}
    private void put(String key,Object value){set(requests.data,key,value);}
    private static void set(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static JSONObject json(Object... entries){JSONObject result=new JSONObject();for(int i=0;i<entries.length;i+=2)set(result,(String)entries[i],entries[i+1]);return result;}
    private void restoreArray(Bundle saved,String key){try{if(saved.containsKey(key))put(key,new JSONArray(saved.getString(key)));}catch(Exception ignored){}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
