package com.mptaptap.playlisthub;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.Insets;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.mptaptap.core.Recommender;
import com.mptaptap.core.PlaylistText;
import com.mptaptap.core.Track;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArtworkLoader artwork = new ArtworkLoader();
    private List<Track> catalogue = new ArrayList<>();
    private List<Recommender.Pick> picks = new ArrayList<>();
    private Map<String, Integer> feedback = new HashMap<>();
    private Set<String> savedTracks = new HashSet<>();
    private LocalStore local;
    private ApiClient api;
    private SharedPreferences settings;
    private String storageIdentity="";
    private boolean returningFromHub;
    private Intent pendingHubPlaylist;
    private String pendingHubMode;
    private String hubServerAddress;
    private boolean pendingHubSettings;
    private AlertDialog settingsDialog;
    private boolean foregroundSeen;
    private boolean foregroundActive;
    private ApiClient foregroundRefreshSource;
    private String foregroundRefreshIdentity;
    private List<String> conversation=new ArrayList<>();
    private EditText refinementPrompt;
    private Map<String,android.widget.CheckBox> refinementChoices=new HashMap<>();
    private String restoredRefinement;
    private Set<String> restoredKeeps=new HashSet<>();
    private ViewPager2 pager;
    private CardAdapter cards;
    private EditText prompt;
    private boolean busy;
    private Snackbar failureBar;
    private boolean metadataReady;
    private final Object taskOwner=new Object();
    private PendingTask pendingTask;
    private RecommendationRequest recommendation;
    private boolean librarySelected;
    private String playlistId;
    private String libraryQuery = "";
    private String resultMode = "basic";
    private String resultNotice = "";
    private boolean hasResults;
    private int replacementPosition=-1,replacementKeptCount;
    private int navigationInset;
    private Recommender.Conditions resultConditions = new Recommender.Conditions();
    private Set<String> hiddenTracks = new HashSet<>();
    private String restoredPlaylistId;
    private int restoredLibraryScroll;
    private WebView player;
    private AlertDialog playerDialog;
    private final Set<AlertDialog> dialogs=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Runnable pendingConnection;
    private String pendingPermissionBase;
    private boolean pendingServerConnection;
    private final ActivityResultLauncher<String> localPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::completeLocalPermission);
    private final ActivityResultLauncher<Intent> hub = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
        Intent data=result.getData();
        if(result.getResultCode()!=RESULT_OK||data==null)return;
        String selectedTab=data.getStringExtra("selectTab");if("library".equals(selectedTab)||"recommend".equals(selectedTab)){((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId("library".equals(selectedTab)?R.id.nav_library:R.id.nav_recommend);return;}
        if(data.getBooleanExtra("connectServer",false)){
            String mode=data.getStringExtra("mode");
            if(!"account".equals(mode)&&!"create".equals(mode)&&!"community".equals(mode))return;
            pendingHubMode=mode;pendingHubSettings=true;returningFromHub=false;
            if(hubServerAddress==null&&(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                    &&("ranchu".equals(android.os.Build.HARDWARE)||"goldfish".equals(android.os.Build.HARDWARE)))hubServerAddress="http://10.0.2.2:8080";
            findViewById(R.id.main).post(this::continuePendingHub);return;
        }
        if(!data.getBooleanExtra("openPlaylist",false))return;
        pendingHubPlaylist=new Intent(data);returningFromHub=true;
        if(foregroundActive&&settings!=null){foregroundRefreshSource=api;foregroundRefreshIdentity=RecommendationRequest.identity(api);findViewById(R.id.main).post(this::refreshForeground);}
    });

    private void completeLocalPermission(boolean granted) {
        if(android.os.Build.VERSION.SDK_INT>=37)granted=checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        Runnable action=pendingConnection;pendingConnection=null;
        pendingPermissionBase=null;pendingServerConnection=false;
        if(granted){if(action!=null)action.run();return;}
        cancelHubConnection();
        if(api!=null&&ApiClient.localHost(java.net.URI.create(api.base).getHost())){settings.edit().remove("server").apply();api=null;clearStorageResults();}
        refreshData(()->{if(librarySelected)loadLibrary();});
        dialogBuilder().setTitle("서버에 연결하지 않았어요")
            .setMessage("로컬 AI 서버 연결에는 근처 기기 권한이 필요해요. 저장한 목록은 계속 사용할 수 있어요.").setPositiveButton("확인",null).show();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); EdgeToEdge.enable(this); setContentView(R.layout.activity_main);
        getWindow().setNavigationBarColor(getColor(R.color.navigation_surface));
        ((BottomNavigationView)findViewById(R.id.navigation)).setItemActiveIndicatorEnabled(true);
        recommendation=new ViewModelProvider(this).get(RecommendationRequest.class);
        pendingTask=new ViewModelProvider(this).get(PendingTask.class);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            navigationInset=bars.bottom;
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            if(pager!=null)updateDiscoverAppearance();
            return WindowInsetsCompat.CONSUMED;
        });
        local = new LocalStore(this); settings = getSharedPreferences("settings", MODE_PRIVATE);
        String base = settings.getString("server", "");
        storageIdentity=base.isEmpty()?"local":getSharedPreferences("server_credentials",MODE_PRIVATE).getString(base,"");
        String storageOwner=ApiClient.ownerFingerprint(base,storageIdentity);
        boolean sameStorage=state==null||base.equals(state.getString("storageBase",base))&&storageOwner.equals(state.getString("storageOwner",
            ApiClient.ownerFingerprint(state.getString("storageBase",base),state.getString("storageIdentity",storageIdentity))));
        if(!sameStorage||!settings.getString("conversationOwner","").equals(storageOwner))settings.edit().remove("conversation").apply();
        try {JSONArray history=new JSONArray(settings.getString("conversation","[]"));for(int i=Math.max(0,history.length()-8);i<history.length();i++)conversation.add(history.getString(i));}catch(Exception ignored) { }
        if(state!=null){returningFromHub=state.getBoolean("returningFromHub");pendingHubPlaylist=state.getParcelable("pendingHubPlaylist");pendingPermissionBase=state.getString("pendingPermissionBase");pendingServerConnection=state.getBoolean("pendingServerConnection");
            pendingHubMode=state.getString("pendingHubMode");hubServerAddress=state.getString("hubServerAddress");pendingHubSettings=state.getBoolean("pendingHubSettings");}
        prompt = findViewById(R.id.prompt); prompt.setText(settings.getString("prompt", ""));
        if (state != null && sameStorage) {
            replacementPosition=state.getInt("replacementPosition",-1);replacementKeptCount=state.getInt("replacementKeptCount");
            restoredRefinement=state.getString("refinementDraft");
            try{JSONArray kept=new JSONArray(state.getString("refinementKeeps","[]"));for(int i=0;i<kept.length();i++)restoredKeeps.add(kept.getString(i));}catch(Exception ignored){}
            libraryQuery = state.getString("libraryQuery", "");
            restoredPlaylistId = state.getString("playlistId");
            restoredLibraryScroll = state.getInt("libraryScroll");
        }
        pager = findViewById(R.id.pager); cards = new CardAdapter(); pager.setAdapter(cards);
        findViewById(R.id.library_scroll).setBackground(TapTapUi.rounded(this,R.color.background,28,false));findViewById(R.id.library_scroll).setClipToOutline(true);
        findViewById(R.id.recommend_scroll).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> resizeCard());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int index) { stopPlayer(); updateCounter(); }
            @Override public void onPageScrollStateChanged(int state) { if(state==ViewPager2.SCROLL_STATE_IDLE)updateDiscoverAppearance(); }
        });
        findViewById(R.id.recommend).setOnClickListener(v -> recommend());
        findViewById(R.id.edit_request).setOnClickListener(v -> {
            findViewById(R.id.request_panel).setVisibility(View.VISIBLE);
            findViewById(R.id.results).setVisibility(View.GONE); findViewById(R.id.empty).setVisibility(View.GONE);
            findViewById(R.id.card_navigation).setVisibility(View.GONE);
            ((androidx.core.widget.NestedScrollView)findViewById(R.id.recommend_scroll)).scrollTo(0, 0);
            updateDiscoverAppearance();
            prompt.requestFocus(); prompt.setSelection(prompt.length());
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(prompt, InputMethodManager.SHOW_IMPLICIT);
        });
        findViewById(R.id.save_results).setOnClickListener(v -> {
            List<Track> tracks=new ArrayList<>(); for (Recommender.Pick pick:picks) tracks.add(pick.track);
            chooseSaveAll(tracks);
        });
        findViewById(R.id.refine_request).setOnClickListener(v->refineRequest());
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.connect_ai).setOnClickListener(v -> connectService());
        findViewById(R.id.connect_ai_results).setOnClickListener(v -> connectService());
        findViewById(R.id.example_rain).setOnClickListener(v -> prompt.setText("비 오는 밤에 듣기 좋은 잔잔한 음악"));
        findViewById(R.id.example_study).setOnClickListener(v -> prompt.setText("공부할 때 집중할 수 있는 가사 없는 피아노 음악"));
        findViewById(R.id.example_workout).setOnClickListener(v -> prompt.setText("운동할 때 듣기 좋은 신나는 음악"));
        findViewById(R.id.previous).setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() - 1, true));
        findViewById(R.id.next).setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() + 1, true));
        ((BottomNavigationView)findViewById(R.id.navigation)).setOnItemSelectedListener(item -> {
            hideKeyboard();
            if(item.getItemId()==R.id.nav_create||item.getItemId()==R.id.nav_community||item.getItemId()==R.id.nav_account){
                openHub(item.getItemId()==R.id.nav_create?"create":item.getItemId()==R.id.nav_community?"community":"account",null);return false;
            }
            librarySelected = item.getItemId() == R.id.nav_library; stopPlayer();
            findViewById(R.id.recommend_page).setVisibility(librarySelected ? View.GONE : View.VISIBLE);
            findViewById(R.id.library_scroll).setVisibility(librarySelected ? View.VISIBLE : View.GONE);
            updateDiscoverAppearance();
            if (librarySelected && !busy) {
                playlistId = null;
                if (restoredPlaylistId == null) loadLibrary();
                else { String id=restoredPlaylistId; restoredPlaylistId=null; openPlaylist(id); }
            }
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy && librarySelected) return;
                if (librarySelected && playlistId != null) { playlistId = null; loadLibrary(); }
                else if (librarySelected) ((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_recommend);
                else if (hasResults && findViewById(R.id.request_panel).getVisibility()==View.VISIBLE) { hideKeyboard(); showResults(); }
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        try {
            catalogue = TrackJson.list(new JSONArray(ApiClient.read(getAssets().open("tracks.json"))));
            if (state != null && sameStorage && state.getBoolean("hasResults")) {
                picks = TrackJson.picks(new JSONArray(state.getString("picks")));
                resultMode = state.getString("mode", "basic"); resultNotice = state.getString("notice", "");
                resultConditions = TrackJson.conditions(new JSONObject(state.getString("conditions")));
                JSONArray hidden = new JSONArray(state.getString("hiddenTracks", "[]"));
                for (int i=0;i<hidden.length();i++) hiddenTracks.add(hidden.getString(i));
                showResults(); pager.setCurrentItem(state.getInt("index"), false);
                if (state.getBoolean("editingRequest")) {
                    findViewById(R.id.request_panel).setVisibility(View.VISIBLE);findViewById(R.id.results).setVisibility(View.GONE);findViewById(R.id.empty).setVisibility(View.GONE);
                    findViewById(R.id.card_navigation).setVisibility(View.GONE);
                }
            }
        } catch (Exception e) { showError("곡 목록을 불러오지 못했어요.", null); }
        if (!base.isEmpty()) {
            try { api = new ApiClient(this, base); catalogue.clear(); } catch (Exception e) { showError("저장된 서버 주소를 확인해 주세요.", null); }
        }
        recommendation.states().observe(this, pending->{
            updateBusy();
            if(pending.result==null&&pending.error==null)return;
            recommendation.consume(pending);
            if(!pending.base.equals(api==null?"":api.base))return;
            if(!pending.identity.equals(RecommendationRequest.identity(api))){
                if(pending.error instanceof ApiClient.ApiError&&((ApiClient.ApiError)pending.error).statusCode==401&&api!=null&&api.identity().isEmpty()){
                    clearStorageResults();showError("로그인 또는 서버 연결을 다시 확인해 주세요.",()->openHub("account",null));
                }return;
            }
            if(pending.error!=null)showError(errorMessage(pending.error),()->{if(!busy)recommendation.retry(api);});
            else showRecommendation(pending.result);
        });
        if(state!=null&&sameStorage&&state.getBoolean("requestPending")&&!recommendation.current().loading
                &&recommendation.current().result==null&&recommendation.current().error==null)
            { try {String snapshot=state.getString("pendingRecommendation");if(snapshot!=null){
                recommendation.restore(new JSONObject(snapshot),api);
                findViewById(R.id.main).post(()->{if(!isDestroyed()&&!isFinishing())showError("진행하던 추천 요청이 남아 있어요. 같은 조건과 선택한 곡으로 다시 요청할 수 있어요.",()->{if(!busy&&metadataReady)recommendation.retry(api);});});
            }}catch(Exception ignored){} }
        pendingTask.states().observe(this, pending->{
            updateBusy();
            if(pending==null||pending.loading||pending.owner==taskOwner)return;
            pendingTask.consume(pending);
            refreshAfterRecreation(state,pending.error);
        });
        if(pendingPermissionBase!=null) {
            if(pendingServerConnection) {
                ApiClient candidate=new ApiClient(this,pendingPermissionBase);
                pendingConnection=()->task(()->storeConnection(candidate),data->applyStorage(candidate,data),null,false,candidate);
            } else pendingConnection=()->refreshAfterRecreation(state,null);
            if(checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)==android.content.pm.PackageManager.PERMISSION_GRANTED)completeLocalPermission(true);
        } else if(pendingTask.current()==null)refreshAfterRecreation(state,null);
    }

    private void refreshAfterRecreation(Bundle state,Exception interruptedError) {
        String base=settings.getString("server","");
        if(!base.equals(api==null?"":api.base)) {
            try {api=base.isEmpty()?null:new ApiClient(this,base);conversation.clear();settings.edit().remove("conversation").apply();clearStorageResults();}
            catch(Exception error){showError("저장된 서버 주소를 확인해 주세요.",null);return;}
        }
        networkPermission(api==null?"":api.base,()->refreshData(()->{
            if(pendingHubMode!=null){
                if(interruptedError==null&&reopenPendingHub())return;
                showPendingHubSettings();
            }
            if(!openPendingHubPlaylist()&&state!=null&&state.getBoolean("library"))((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_library);
            if(interruptedError!=null)showError(errorMessage(interruptedError),null);
        }));
    }

    private void refreshData(Runnable success) {
        ApiClient source=api;
        task(()->readMetadata(source),data->{applyMetadata(data);success.run();},()->refreshData(success),true);
    }

    private Object[] readMetadata(ApiClient source) throws Exception {
        List<Track> tracks;Map<String,Integer> reactions;
        if(source!=null) {
            source.connect();tracks=TrackJson.list(source.array("/catalogue"));
            JSONObject rows=source.object("GET","/me/feedback",null);
            Map<String,Integer> f=new HashMap<>();rows.keys().forEachRemaining(id->f.put(id,rows.optInt(id)));reactions=f;
        } else {
            tracks=TrackJson.list(new JSONArray(ApiClient.read(getAssets().open("tracks.json"))));reactions=local.feedback();
        }
        return new Object[]{tracks,reactions,savedIds(source)};
    }

    private void applyMetadata(Object[] data) {
        catalogue=(List<Track>)data[0];feedback=(Map<String,Integer>)data[1];savedTracks=(Set<String>)data[2];
        mergeRecommendationTracks(false);
        metadataReady=true;updateBusy();
        storageIdentity=api==null?"local":api.identity();
        if(restoredRefinement!=null&&hasResults)findViewById(R.id.main).post(this::refineRequest);
    }

    private Object[] storeConnection(ApiClient source) throws Exception {
        Object[] data=readMetadata(source);
        if(source!=null&&!source.ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 연결을 다시 확인해 주세요.");
        SharedPreferences.Editor change=settings.edit().putBoolean("serverSelectionMade",true);
        if(source==null)change.remove("server");else change.putString("server",source.base);
        if(!change.commit())throw new IllegalStateException("음악 설정을 저장하지 못했어요.");
        return data;
    }

    private void applyStorage(ApiClient source,Object[] data) {
        if(source!=null&&!source.ownsCurrentSession()){showError("계정이 변경되었어요. 새 계정에서 연결을 다시 확인해 주세요.",null);return;}
        api=source;storageIdentity=api==null?"local":api.identity();conversation.clear();settings.edit().remove("conversation").apply();
        clearStorageResults();applyMetadata(data);if(!reopenPendingHub()&&librarySelected)loadLibrary();
    }

    private Set<String> savedIds(ApiClient source) throws Exception {
        Set<String> ids = new HashSet<>();
        if (source != null) { JSONArray rows = source.array("/me/saved-tracks"); for (int i=0;i<rows.length();i++) ids.add(rows.getString(i)); }
        else {
            JSONArray lists = local.playlists();
            for (int i=0;i<lists.length();i++) {
                JSONArray items = local.playlist(lists.getJSONObject(i).getString("id")).getJSONArray("items");
                for (int j=0;j<items.length();j++) ids.add(items.getJSONObject(j).getJSONObject("track").getString("id"));
            }
        }
        return ids;
    }

    private void recommend() {
        if(busy||!metadataReady)return;
        String request = prompt.getText().toString().trim();
        TextInputLayout field = findViewById(R.id.prompt_layout);
        try { Recommender.parse(request); field.setError(null); }
        catch (IllegalArgumentException e) { field.setError(e.getMessage()); return; }
        if(api==null){settings.edit().putString("prompt",request).apply();hideKeyboard();connectService();return;}
        replacementPosition=-1;hideKeyboard(); stopPlayer(); settings.edit().putString("prompt", request).apply();
        recommendation.start(request,api);
    }

    private void refineRequest() {
        if(busy||!hasResults)return;
        if(api==null){connectService();return;}
        LinearLayout box=column();
        box.addView(text("이전 요청을 기억하며 추천을 바꿔요. 유지할 곡을 선택할 수 있어요.",14,false));
        for(String previous:conversation)box.addView(text("나 · "+previous,13,false));
        EditText next=new AppCompatEditText(this);next.setHint("예: 더 신나게, 피아노는 제외해줘");next.setMinLines(2);
        next.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});box.addView(next);
        if(restoredRefinement!=null){next.setText(restoredRefinement);restoredRefinement=null;}
        refinementPrompt=next;refinementChoices.clear();
        List<android.widget.CheckBox> choices=new ArrayList<>();
        List<Recommender.Pick> current=new ArrayList<>(picks);
        for(Recommender.Pick pick:current){android.widget.CheckBox check=new androidx.appcompat.widget.AppCompatCheckBox(this);
            check.setText("유지 · "+pick.track.title);check.setMinHeight(dp(48));check.setChecked(restoredKeeps.contains(pick.track.id));
            refinementChoices.put(pick.track.id,check);choices.add(check);box.addView(check);}
        restoredKeeps.clear();
        AlertDialog dialog=dialogBuilder().setTitle("이어서 요청하기").setView(scroll(box)).setPositiveButton("추천 바꾸기",null).setNegativeButton("취소",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String request=next.getText().toString().trim();try{com.mptaptap.core.Conversation.parse(resultConditions,conversation,request);next.setError(null);}catch(Exception e){next.setError(e.getMessage());return;}
            List<String> kept=new ArrayList<>();for(int i=0;i<choices.size();i++)if(choices.get(i).isChecked())kept.add(current.get(i).track.id);
            dialog.dismiss();hideKeyboard();stopPlayer();
            replacementPosition=-1;recommendation.start(request,api,new ArrayList<>(conversation),kept,new ArrayList<>(hiddenTracks),resultConditions);
        }));dialog.setOnDismissListener(d->{if(refinementPrompt==next){refinementPrompt=null;refinementChoices.clear();}});dialog.show();
    }

    private void openHub(String mode,String id) {
        cancelHubConnection();
        stopPlayer();returningFromHub=true;
        Intent intent=new Intent(this,HubActivity.class).putExtra("mode",mode);
        if(id!=null)intent.putExtra("playlistId",id);
        hub.launch(intent);
    }

    private void cancelHubConnection(){pendingHubMode=null;hubServerAddress=null;pendingHubSettings=false;}
    private void continuePendingHub(){if(!reopenPendingHub())showPendingHubSettings();}
    private void showPendingHubSettings(){
        if(!pendingHubSettings||pendingHubMode==null||!foregroundActive||busy||isDestroyed()||isFinishing())return;
        pendingHubSettings=false;connectService();
    }
    private boolean reopenPendingHub(){
        if(pendingHubMode==null||api==null||!metadataReady||!foregroundActive||busy||isDestroyed()||isFinishing()||!api.ownsCurrentSession()
                ||!api.base.equals(settings.getString("server","")))return false;
        String mode=pendingHubMode;cancelHubConnection();
        if(settingsDialog!=null)settingsDialog.dismiss();
        openHub(mode,null);return true;
    }

    private boolean openPendingHubPlaylist() {
        if(pendingHubPlaylist==null||!metadataReady)return false;
        Intent result=pendingHubPlaylist;pendingHubPlaylist=null;
        String base=api==null?"":api.base,id=result.getStringExtra("playlistId");
        if(id==null||id.isEmpty()||!base.equals(result.getStringExtra("serverBase"))
                ||!ApiClient.ownerFingerprint(base,api==null?"local":api.identity()).equals(result.getStringExtra("owner"))){
            showError("계정 또는 서버가 변경되어 저장한 목록을 열 수 없어요.",null);return false;
        }
        restoredPlaylistId=id;restoredLibraryScroll=0;
        ((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_library);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();foregroundActive=true;boolean initial=!foregroundSeen;foregroundSeen=true;
        if(pendingHubMode!=null)findViewById(R.id.main).post(this::continuePendingHub);
        if(settings==null||!returningFromHub&&(initial||api==null||!metadataReady))return;returningFromHub=false;
        foregroundRefreshSource=api;foregroundRefreshIdentity=RecommendationRequest.identity(api);
        findViewById(R.id.main).post(this::refreshForeground);
    }
    @Override protected void onPause(){foregroundActive=false;super.onPause();}
    private void refreshForeground() {
        if(!foregroundActive||isDestroyed()||isFinishing()||busy||foregroundRefreshIdentity==null)return;
        ApiClient source=foregroundRefreshSource;String owner=foregroundRefreshIdentity;
        foregroundRefreshSource=null;foregroundRefreshIdentity=null;
        String selected=settings.getString("server","").trim().replaceAll("/+$","");
        if(api!=source||!owner.equals(RecommendationRequest.identity(source)))return;
        if(!selected.equals(source==null?"":source.base)){refreshAfterRecreation(null,null);return;}
        String identity=api==null?"local":api.identity();
        if(!identity.equals(storageIdentity)){storageIdentity=identity;conversation.clear();settings.edit().remove("conversation").apply();clearStorageResults();}
        refreshData(()->{storageIdentity=api==null?"local":api.identity();if(!openPendingHubPlaylist()&&librarySelected){if(playlistId==null)loadLibrary();else openPlaylist(playlistId);}});
    }

    private void showRecommendation(Object[] result) {
        dismissFailure();
        if(result.length>4){conversation=new ArrayList<>((List<String>)result[4]);settings.edit().putString("conversation",new JSONArray(conversation).toString())
            .putString("conversationOwner",ApiClient.ownerFingerprint(api==null?"":api.base,storageIdentity)).apply();}
        picks = new ArrayList<>((List<Recommender.Pick>)result[0]);
        int nextIndex=0;
        if(replacementPosition>=0&&picks.size()>replacementKeptCount){nextIndex=Math.min(replacementPosition,picks.size()-1);picks.add(nextIndex,picks.remove(replacementKeptCount));}
        replacementPosition=-1;int selectedIndex=nextIndex;
        resultMode = (String)result[1]; resultNotice = (String)result[2];
        boolean newTracks=mergeRecommendationTracks(true);
        resultConditions = (Recommender.Conditions)result[3]; hiddenTracks.clear();
        showResults(); List<Recommender.Pick> shown=picks;
        findViewById(R.id.results).post(() -> {
            if(isDestroyed()||isFinishing()||picks!=shown)return;
            // A replacement stays on the changed card after Activity state restoration.
            hideKeyboard();pager.setCurrentItem(selectedIndex,false);
            ((androidx.core.widget.NestedScrollView)findViewById(R.id.recommend_scroll)).scrollTo(0,findViewById(R.id.results).getTop());
        });
        // Newly verified records are registered on the server; refresh the library's searchable tracks.
        if(api!=null&&newTracks)refreshData(()->{cards.notifyDataSetChanged();updateCounter();});
    }

    private boolean mergeRecommendationTracks(boolean replace) {
        Map<String,Track> rows=new java.util.LinkedHashMap<>();for(Track track:catalogue)rows.put(track.id,track);
        boolean added=false;
        for(Recommender.Pick pick:picks) {
            if(!pick.track.usable())continue;
            if(!rows.containsKey(pick.track.id)){rows.put(pick.track.id,pick.track);added=true;}
            else if(replace)rows.put(pick.track.id,pick.track);
        }
        catalogue=new ArrayList<>(rows.values());return added;
    }

    private void showResults() {
        hasResults=true; findViewById(R.id.request_panel).setVisibility(View.GONE);
        findViewById(R.id.results).setVisibility(View.VISIBLE);
        findViewById(R.id.empty).setVisibility(picks.isEmpty() ? View.VISIBLE : View.GONE);
        String emptyNotice=api==null?"AI 추천이 연결되지 않았어요.\nAI 추천을 연결하면 같은 요청으로 새 곡을 찾을 수 있어요."
            :resultNotice.isEmpty()?"조건에 맞는 곡을 확인하지 못했어요. 같은 요청으로 다시 시도해 보세요.":resultNotice;
        ((TextView)findViewById(R.id.empty)).setText(hiddenTracks.isEmpty()?emptyNotice:"이번 추천에서 모든 곡을 제외했어요.\n요청을 수정해 새로 추천받을 수 있어요.");
        ((TextView)findViewById(R.id.notice)).setText(resultMode.equals("openai")?"AI 추천":"이전 기본 추천 결과 · AI 연결 필요");
        TextView detail=findViewById(R.id.result_detail);
        detail.setText(resultNotice+(hiddenTracks.isEmpty()?"":"\n이번 결과만 수정했어요. 좋아요·싫어요는 그대로예요."));
        detail.setVisibility(!picks.isEmpty()&&!detail.getText().toString().isEmpty()?View.VISIBLE:View.GONE);
        List<String> conditions=new ArrayList<>();
        for (String tag:resultConditions.tags) conditions.add(Recommender.label(tag));
        for (String tag:resultConditions.excludedTags) conditions.add(Recommender.label(tag)+" 제외");
        if(!resultConditions.language.isEmpty()&&!(resultConditions.language.equals("none")&&resultConditions.tags.contains("instrumental")))conditions.add(Recommender.languageLabel(resultConditions.language));
        ((TextView)findViewById(R.id.condition_summary)).setText(conditions.isEmpty() ? "다양한 음악" : android.text.TextUtils.join(" · ",conditions));
        findViewById(R.id.condition_summary).setContentDescription("추천 조건: "+((TextView)findViewById(R.id.condition_summary)).getText());
        pager.setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.card_navigation).setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.save_results).setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.save_results).setEnabled(!busy && metadataReady && !picks.isEmpty());
        ((MaterialButton)findViewById(R.id.save_results)).setText("모두 저장");
        findViewById(R.id.save_results).setContentDescription("추천 "+picks.size()+"곡 모두 저장");
        updateDiscoverAppearance();
        cards.notifyDataSetChanged(); updateCounter(); findViewById(R.id.results).post(this::resizeCard);
    }

    private void resizeCard() {
        if (findViewById(R.id.results).getVisibility()!=View.VISIBLE || picks.isEmpty()) return;
        int available=findViewById(R.id.recommend_scroll).getHeight()-pager.getTop()-findViewById(R.id.results_header).getHeight()-dp(12);
        int height=Math.max(dp(180), available);
        if (pager.getLayoutParams().height!=height) { pager.getLayoutParams().height=height;pager.requestLayout(); }
    }

    private void updateDiscoverAppearance() {
        if(pager==null)return;
        boolean showing=hasResults&&findViewById(R.id.results).getVisibility()==View.VISIBLE&&!librarySelected;
        findViewById(R.id.connect_ai_results).setVisibility(api==null&&showing?View.VISIBLE:View.GONE);
        findViewById(R.id.header_caption).setVisibility(showing?View.GONE:View.VISIBLE);
        findViewById(R.id.notice).setVisibility(showing?View.VISIBLE:View.GONE);
        findViewById(R.id.counter).setVisibility(showing?View.VISIBLE:View.GONE);
        TextView title=findViewById(R.id.header_title),caption=findViewById(R.id.header_caption);
        title.setText(librarySelected?"내 음악":showing?"오늘의 추천":"TapTap");
        caption.setText(librarySelected?"좋아하는 음악을 모아두는 곳":"하루를 닮은 음악");
        int ink=getColor(R.color.discover_ink);
        title.setTextColor(ink);caption.setTextColor(getColor(R.color.discover_muted));
        ((MaterialButton)findViewById(R.id.settings)).setIconTint(android.content.res.ColorStateList.valueOf(ink));
        Drawable background;
        if(librarySelected)background=getDrawable(R.drawable.discover_background);
        else {
            Track visible=showing&&!picks.isEmpty()?picks.get(Math.min(pager.getCurrentItem(),picks.size()-1)).track:null;
            String imageKey=visible==null?null:ArtworkLoader.key(visible.youtubeVideoId,visible.artworkUrl);
            Bitmap photo=visible==null?null:artwork.peek(visible.youtubeVideoId,visible.artworkUrl);
            View visibleArtwork=imageKey==null?null:pager.findViewWithTag(imageKey);
            if(photo==null&&visibleArtwork instanceof ImageView&&((ImageView)visibleArtwork).getDrawable() instanceof BitmapDrawable)
                photo=((BitmapDrawable)((ImageView)visibleArtwork).getDrawable()).getBitmap();
            if(photo==null)background=getDrawable(R.drawable.discover_background);
            else {
                float red=0,green=0,blue=0,total=0;float[] hsv=new float[3];
                // A small sample follows the artwork's colour without analysing it for recommendations.
                for(int y=1;y<10;y++)for(int x=1;x<10;x++){
                    int pixel=photo.getPixel(x*photo.getWidth()/10,y*photo.getHeight()/10);
                    Color.colorToHSV(pixel,hsv);float weight=hsv[1]*hsv[2];
                    if(hsv[2]<.18f||weight<.08f)continue;
                    red+=Color.red(pixel)*weight;green+=Color.green(pixel)*weight;blue+=Color.blue(pixel)*weight;total+=weight;
                }
                if(total==0)background=getDrawable(R.drawable.discover_background);
                else {
                    Color.colorToHSV(Color.rgb(Math.round(red/total),Math.round(green/total),Math.round(blue/total)),hsv);
                    hsv[1]=Math.max(.4f,Math.min(.66f,hsv[1]));hsv[2]=1f;int top=Color.HSVToColor(hsv);
                    int middle=ColorUtils.blendARGB(top,Color.rgb(43,25,39),.46f);
                    hsv[0]=(hsv[0]+22)%360;hsv[1]=.66f;hsv[2]=.24f;int bottom=Color.HSVToColor(hsv);
                    background=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,middle,bottom});
                }
            }
        }
        LayerDrawable layers=new LayerDrawable(new Drawable[]{background,new ColorDrawable(getColor(R.color.navigation_surface))});
        layers.setLayerGravity(1,android.view.Gravity.BOTTOM);layers.setLayerHeight(1,navigationInset);
        findViewById(R.id.main).setBackground(layers);
        androidx.core.view.WindowInsetsControllerCompat bars=ViewCompat.getWindowInsetsController(findViewById(R.id.main));
        if(bars!=null){
            bars.setAppearanceLightStatusBars(true);bars.setAppearanceLightNavigationBars(false);
        }
    }

    private void editResult(Track track) {
        if (busy||!metadataReady) return;
        dialogBuilder().setTitle(track.title)
            .setMessage("이번 추천 결과만 바꿔요. 싫어요로 기록하지 않아요.")
            .setPositiveButton("같은 조건의 다른 곡", (d,w) -> changeResult(track, true))
            .setNeutralButton("이번 추천에서 제외", (d,w) -> changeResult(track, false))
            .setNegativeButton("취소", null).show();
    }

    private void changeResult(Track track, boolean replace) {
        if (busy||!metadataReady) return;
        int index=-1;
        for (int i=0;i<picks.size();i++) {
            if (picks.get(i).track.id.equals(track.id)) index=i;
        }
        if (index<0) return;
        if(replace) {
            if(api==null){connectService();return;}
            List<String> kept=new ArrayList<>();
            for(Recommender.Pick pick:picks)if(!pick.track.id.equals(track.id)&&feedback.getOrDefault(pick.track.id,0)!=-1)kept.add(pick.track.id);
            Set<String> excluded=new HashSet<>(hiddenTracks);excluded.add(track.id);
            replacementPosition=index;replacementKeptCount=kept.size();stopPlayer();
            recommendation.start(prompt.getText().toString(),api,new ArrayList<>(conversation),kept,new ArrayList<>(excluded),resultConditions);
            return;
        }
        List<Recommender.Pick> before=picks, changed=new ArrayList<>(picks);
        Set<String> beforeHidden=new HashSet<>(hiddenTracks); int beforeIndex=pager.getCurrentItem();
        changed.remove(index);
        stopPlayer(); picks=changed; hiddenTracks.add(track.id); showResults();
        pager.setCurrentItem(Math.max(0, Math.min(beforeIndex, picks.size()-1)), false);
        snackbar("이번 추천에서 제외했어요.", Snackbar.LENGTH_LONG)
            .setAction("되돌리기", v -> {
                if (busy || picks!=changed) return;
                stopPlayer(); picks=before; hiddenTracks=beforeHidden; showResults(); pager.setCurrentItem(beforeIndex, false);
            }).show();
    }

    private void updateCounter() {
        int index = picks.isEmpty() ? 0 : Math.min(pager.getCurrentItem() + 1, picks.size());
        TextView counter=findViewById(R.id.counter);
        counter.setContentDescription(picks.isEmpty()?"추천 곡 없음":"총 "+picks.size()+"곡 중 "+index+"번째 곡");
        counter.setText(index + " / " + picks.size());
        updateDiscoverAppearance();
        findViewById(R.id.previous).setEnabled(!busy && index > 1);
        findViewById(R.id.next).setEnabled(!busy && index > 0 && index < picks.size());
        findViewById(R.id.previous).setAlpha(!busy && index>1 ? 1f : 0.45f);
        findViewById(R.id.next).setAlpha(!busy && index>0 && index<picks.size() ? 1f : 0.45f);
        MaterialButton like=findViewById(R.id.like), dislike=findViewById(R.id.dislike);
        like.setEnabled(!busy && metadataReady && index>0);dislike.setEnabled(!busy && metadataReady && index>0);
        MaterialButton listen=findViewById(R.id.listen);listen.setEnabled(!busy && index>0);
        listen.setAlpha(!busy && index>0 ? 1f : 0.45f);
        if (index>0 && index<=picks.size()) {
            Track track=picks.get(index-1).track;int reaction=feedback.getOrDefault(track.id,0);
            like.setContentDescription(reaction==1?"좋아요 취소":"좋아요");dislike.setContentDescription(reaction==-1?"싫어요 취소":"싫어요");
            like.setIconResource(reaction==1?R.drawable.ic_heart_filled:R.drawable.ic_heart);
            dislike.setIconResource(reaction==-1?R.drawable.ic_dislike_filled:R.drawable.ic_dislike);
            like.setSelected(reaction==1);dislike.setSelected(reaction==-1);
            like.setOnClickListener(v->react(track,reaction==1?0:1));dislike.setOnClickListener(v->react(track,reaction==-1?0:-1));
            listen.setContentDescription(track.title+" · YouTube로 듣기");listen.setOnClickListener(v->listen(track));
        }
    }

    private void updateMode() {
        String notice=metadataReady?(api==null?"이용 안내를 확인하면 요청에 맞는 음악을 찾을 수 있어요.":"한 문장으로 음악을 찾아보세요.")
            :busy?"음악 데이터를 확인 중이에요.":"음악을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
        ((TextView)findViewById(R.id.mode_label)).setText((api==null?"시작하기 · ":"온라인 추천 · ")+notice);
        findViewById(R.id.connect_ai).setVisibility(api==null||!busy&&!metadataReady?View.VISIBLE:View.GONE);
        findViewById(R.id.connect_ai).setEnabled(!busy);
        findViewById(R.id.connect_ai_results).setEnabled(!busy);
    }

    private void react(Track track, int reaction) {
        if(!metadataReady)return;
        task(() -> {
            if (api == null) local.react(track.id, reaction);
            else api.request(reaction == 0 ? "DELETE" : "PUT", "/me/feedback/" + track.id, reaction == 0 ? null : new JSONObject().put("reaction", reaction));
            return reaction;
        }, value -> {
            if (value == 0) feedback.remove(track.id); else feedback.put(track.id, value);
            cards.notifyDataSetChanged();updateCounter();
            snackbar(value == 0 ? "반응을 취소했어요." : "다음 추천에 반영할게요.", Snackbar.LENGTH_SHORT).show();
        }, () -> react(track, reaction));
    }

    private JSONArray playlists() throws Exception { return api == null ? local.playlists() : api.array("/playlists"); }
    private JSONObject playlist(String id) throws Exception { return api == null ? local.playlist(id) : api.object("GET", "/playlists/" + id, null); }

    private void chooseSave(Track track) {
        chooseSaveAll(java.util.Collections.singletonList(track));
    }

    private void chooseSaveAll(List<Track> tracks) {
        if (!metadataReady||tracks.isEmpty()) return;
        task(this::playlists, lists -> {
            String[] labels = new String[lists.length()+1];
            for (int i=0;i<lists.length();i++) labels[i] = lists.optJSONObject(i).optString("title") + " · " + lists.optJSONObject(i).optInt("size") + "곡";
            labels[lists.length()] = "+ 새 플레이리스트";
            dialogBuilder().setTitle("어디에 저장할까요?").setItems(labels, (dialog, which) -> {
                if (which == lists.length()) editPlaylist(tracks, null, "");
                else save(tracks, lists.optJSONObject(which).optString("id"));
            }).setNegativeButton("취소", null).show();
        }, () -> chooseSaveAll(tracks));
    }

    private void editPlaylist(List<Track> firstTracks, String id, String currentTitle) {
        EditText name = new AppCompatEditText(this); name.setHint("예: 비 오는 날의 음악"); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setSingleLine(true); name.setText(currentTitle); name.setSelection(name.length());
        LinearLayout box = column(); box.addView(name);
        TextView failure=text("",14,false);failure.setTextColor(getColor(R.color.error_ink));failure.setVisibility(View.GONE);failure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);box.addView(failure);
        AlertDialog dialog = dialogBuilder().setTitle(id == null ? "새 플레이리스트" : "목록 이름 변경").setView(box).setPositiveButton(id == null ? "만들기" : "변경", null).setNegativeButton("취소", null).create();
        JSONObject[] createdPlaylist=new JSONObject[1];
        String creationId=id==null?java.util.UUID.randomUUID().toString():id;
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(busy)return;
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("이름을 입력해 주세요."); return; }
            name.setError(null);failure.setVisibility(View.GONE);name.setEnabled(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            task(() -> {
                try {
                    if(id==null) {
                        if(createdPlaylist[0]==null)createdPlaylist[0]=api==null?local.create(title):api.object("POST","/playlists",new JSONObject().put("id",creationId).put("title",title));
                        if(firstTracks!=null)saveTracks(firstTracks,createdPlaylist[0].getString("id"));
                        return createdPlaylist[0];
                    }
                    if (api == null) local.rename(id, title); else api.request("PUT", "/playlists/"+id, new JSONObject().put("title", title));
                    return new JSONObject().put("id", id);
                } catch(Exception error) {
                    runOnUiThread(()->{if(!isDestroyed()&&dialog.isShowing()){
                        failure.setText(errorMessage(error));failure.setVisibility(View.VISIBLE);name.setError(failure.getText());name.setEnabled(createdPlaylist[0]==null);box.setFocusableInTouchMode(true);box.requestFocus();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if(createdPlaylist[0]!=null)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("저장 다시 시도");
                    }});
                    throw error;
                }
            }, created -> {
                dialog.dismiss();
                if (id != null) openPlaylist(id);
                else if(firstTracks!=null) {
                    List<String> saved=new ArrayList<>();for(Track track:firstTracks)saved.add(track.id);showSaved(saved);
                } else loadLibrary();
            }, null);
        })); dialog.show();
    }

    private List<String> saveTracks(List<Track> tracks,String id) throws Exception {
        List<String> ids=new ArrayList<>();for(Track track:tracks)ids.add(track.id);
        if(api==null)local.saveAll(id,tracks);
        else api.request("POST","/playlists/"+id+"/items/batch",new JSONObject().put("trackIds",new JSONArray(ids)));
        return ids;
    }
    private void save(List<Track> tracks, String id) {
        task(()->saveTracks(tracks,id),this::showSaved,()->save(tracks,id));
    }
    private void showSaved(List<String> saved) {
        savedTracks.addAll(saved);cards.notifyDataSetChanged();
        snackbar("플레이리스트에 "+saved.size()+"곡을 저장했어요.",Snackbar.LENGTH_LONG)
            .setAction("목록 보기",v->((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_library)).show();
    }

    private void loadLibrary() {
        if (!librarySelected) return;
        task(this::playlists, lists -> {
            if (!librarySelected || playlistId != null) return;
            LinearLayout box = findViewById(R.id.library); box.removeAllViews();
            box.addView(text("내 플레이리스트", 27, true));
            box.addView(text(api == null ? "좋아하는 음악을 이 기기에 모아두세요." : "내가 저장한 음악을 다시 들어보세요.", 14, false));
            MaterialButton create=button("+ 새 플레이리스트", v -> editPlaylist(null, null, ""));TapTapUi.primary(create);box.addView(create);
            MaterialButton searchTracks=button("곡명·아티스트로 찾기", v -> searchCatalogue());searchTracks.setEnabled(metadataReady);box.addView(searchTracks);
            LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
            EditText search = searchField("내 목록 이름 검색", q -> { libraryQuery=q; renderLists(rows, lists, q); });
            box.addView(search); box.addView(rows); search.setText(libraryQuery); renderLists(rows, lists, libraryQuery);
            restoreLibraryScroll();
        }, this::loadLibrary);
    }

    private void openPlaylist(String id) {
        task(() -> {
            try { return playlist(id); }
            catch (ApiClient.ApiError e) { if (e.statusCode==404) return null; throw e; }
            catch (IllegalArgumentException e) { if (api==null) return null; throw e; }
        }, data -> {
            if (!librarySelected) return;
            if (data==null) { playlistId=null; loadLibrary(); showError("이 목록은 이미 삭제됐어요.", null); return; }
            playlistId = id; LinearLayout box = findViewById(R.id.library); box.removeAllViews();
            box.addView(button("‹ 내 목록으로", v -> { playlistId=null; loadLibrary(); }));
            box.addView(text(data.optString("title"), 27, true));
            JSONArray items = data.optJSONArray("items");
            box.addView(text(items.length() + "곡", 14, false));
            LinearLayout actions=new LinearLayout(this);
            actions.addView(button("이름 변경", v -> editPlaylist(null, id, data.optString("title"))),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
            LinearLayout.LayoutParams deleteSize=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);deleteSize.setMarginStart(dp(8));
            actions.addView(button("목록 삭제", v -> dialogBuilder().setTitle("이 목록을 삭제할까요?")
                .setMessage(data.optString("title") + "\n" + items.length() + "곡이 목록에서 제거돼요.")
                .setPositiveButton("삭제", (d,w) -> deletePlaylist(id)).setNegativeButton("취소", null).show()),deleteSize);
            box.addView(actions);
            MaterialButton share=button("목록 텍스트 공유", v -> sharePlaylist(data)); share.setEnabled(items.length()>0); box.addView(share);
            if(api!=null)box.addView(button("공개 설정 · 공유 링크",v->openHub("community",id)));
            if (items.length() == 0) box.addView(text("아직 곡이 없어요. 추천 화면에서 음악을 담아보세요.", 16, false));
            for (int i=0;i<items.length();i++) {
                JSONObject row = items.optJSONObject(i);
                if(row.has("trackId") && row.isNull("trackId") && row.optJSONObject("track")!=null){
                    JSONObject external=row.optJSONObject("track");String item=row.optString("id");int index=i;
                    box.addView(button((i+1)+". "+external.optString("title","다시 확인 필요")+"\n"+external.optString("artist")+" · 가져온 곡",v->externalItemActions(id,item,external,index,items.length())));continue;
                }
                try {
                    Track track = TrackJson.read(row.getJSONObject("track")); String item = row.getString("id"); int index=i;
                    box.addView(button((i+1) + ". " + track.title + "\n" + track.artist, v -> itemActions(id, item, track, index, items.length())));
                } catch (Exception e) { box.addView(text("곡 정보를 불러오지 못했어요.", 14, false)); }
            }
            restoreLibraryScroll();
        }, () -> openPlaylist(id));
    }

    private void restoreLibraryScroll() {
        int position=restoredLibraryScroll; restoredLibraryScroll=0;
        findViewById(R.id.library_scroll).post(() -> ((androidx.core.widget.NestedScrollView)findViewById(R.id.library_scroll)).scrollTo(0, position));
    }

    private void sharePlaylist(JSONObject data) {
        try {
            JSONArray items=data.getJSONArray("items");StringBuilder content=new StringBuilder(data.getString("title")).append("\n\n");
            for(int i=0;i<items.length();i++){
                JSONObject row=items.getJSONObject(i),track=row.getJSONObject("track");
                if(!row.has("trackId")||!row.isNull("trackId"))TrackJson.read(track);
                content.append(i+1).append(". ").append(track.optString("title","다시 확인 필요")).append(" — ").append(track.optString("artist")).append("\n");
                String source=track.optString("sourceUrl");if(!source.isEmpty())content.append(source).append("\n");
            }
            Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, data.getString("title"))
                .putExtra(Intent.EXTRA_TEXT, content.toString());
            stopPlayer(); startActivity(Intent.createChooser(share, "목록 텍스트 공유"));
        } catch (ActivityNotFoundException e) { showError("공유할 수 있는 앱이 없어요.", null); }
        catch (Exception e) { showError("공유할 곡의 출처를 확인할 수 없어요.", null); }
    }

    private void renderLists(LinearLayout rows, JSONArray lists, String query) {
        rows.removeAllViews(); int count=0;
        for (int i=0;i<lists.length();i++) {
            JSONObject row=lists.optJSONObject(i);
            if (!Track.matches(row.optString("title"), query)) continue;
            String id=row.optString("id"); count++;
            MaterialButton playlist=button(row.optString("title") + "\n" + row.optInt("size") + "곡", v -> openPlaylist(id));playlist.setIconResource(R.drawable.ic_library);rows.addView(playlist);
        }
        if (count==0) rows.addView(text(query.trim().isEmpty() ? "아직 저장한 목록이 없어요.\n추천 곡에서 ‘목록에 저장’을 눌러보세요." : "검색한 이름의 목록이 없어요.", 16, false));
    }

    private EditText searchField(String hint, Consumer<String> change) {
        EditText field=new AppCompatEditText(this); field.setSingleLine(true); field.setHint(hint);
        TapTapUi.input(field);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.setMargins(0,dp(12),0,dp(12));field.setLayoutParams(size);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { change.accept(s.toString()); }
            public void afterTextChanged(Editable text) {}
        });
        return field;
    }

    private void searchCatalogue() {
        if(!metadataReady)return;
        LinearLayout box=column(), rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        AlertDialog searchDialog=dialogBuilder().setTitle("곡 찾기").setView(scroll(box)).setNegativeButton("닫기",null).create();
        Consumer<String> render=q -> {
            rows.removeAllViews(); int count=0;
            for (Track t:catalogue) if (Track.matches(t.title+" "+t.artist, q)) {
                count++; rows.addView(button(t.title+"\n"+t.artist, v -> dialogBuilder().setTitle(t.title)
                    .setItems(new String[]{"들어보기", "목록에 저장", "다른 플랫폼에서 열기", "곡 정보·출처"}, (d,w) -> {
                        searchDialog.dismiss();
                        if (w==0) listen(t); else if (w==1) chooseSave(t); else if (w==2) external(t); else source(t);
                    }).setNegativeButton("닫기", null).show()));
            }
            rows.addView(text(count==0 ? "일치하는 곡이 없어요. 다른 곡명이나 아티스트를 입력해 보세요." : count+"곡 · 검증된 곡 목록에서 검색", 14, false));
        };
        box.addView(searchField("곡명 또는 아티스트 검색", render)); box.addView(rows); render.accept("");
        searchDialog.show();
    }

    private void itemActions(String id, String item, Track track, int index, int count) {
        List<String> actions=new ArrayList<>(java.util.Arrays.asList("들어보기", "다른 플랫폼에서 열기", "곡 정보·출처"));
        if (index>0) actions.add("위로 이동"); if (index<count-1) actions.add("아래로 이동"); actions.add("이 목록에서 제거");
        dialogBuilder().setTitle(track.title).setItems(actions.toArray(new String[0]), (d,w) -> {
            String action=actions.get(w);
            if (action.equals("들어보기")) listen(track);
            else if (action.equals("다른 플랫폼에서 열기")) external(track);
            else if (action.equals("곡 정보·출처")) source(track);
            else if (action.equals("위로 이동") || action.equals("아래로 이동")) moveItem(id, item, action.equals("위로 이동") ? -1 : 1);
            else dialogBuilder().setTitle("이 목록에서 제거할까요?").setMessage(track.title)
                .setPositiveButton("제거", (a,b) -> remove(id, item)).setNegativeButton("취소", null).show();
        }).setNegativeButton("닫기", null).show();
    }

    private void externalItemActions(String id,String item,JSONObject track,int index,int count) {
        String url=track.optString("sourceUrl"),title=track.optString("title","다시 확인 필요");
        List<String> actions=new ArrayList<>(java.util.Arrays.asList("원본에서 열기","곡 정보·출처","곡 정보 수정"));
        if(index>0)actions.add("위로 이동");if(index<count-1)actions.add("아래로 이동");actions.add("이 목록에서 제거");
        Uri origin=Uri.parse(url);String video=origin.isHierarchical()?origin.getQueryParameter("v"):null;
        boolean youtube="https".equals(origin.getScheme())&&"www.youtube.com".equals(origin.getHost())&&video!=null&&video.matches("[A-Za-z0-9_-]{11}");
        if(youtube)actions.add(0,"여기서 들어보기");
        dialogBuilder().setTitle(title).setItems(actions.toArray(new String[0]),(d,w)->{
            String action=actions.get(w);
            if(action.equals("여기서 들어보기")){int seconds=track.optInt("timestamp",0);String t=origin.getQueryParameter("t");
                try{if(t!=null)seconds=Integer.parseInt(t.replaceFirst("s$",""));}catch(Exception ignored){}
                listenVideo(title,video,Math.max(0,Math.min(86400,seconds)),()->openUrl(url));}
            else if(action.equals("원본에서 열기"))openUrl(url);
            else if(action.equals("곡 정보·출처"))dialogBuilder().setTitle("가져온 곡 정보")
                .setMessage(title+"\n"+track.optString("artist")+"\n\n"+track.optString("sourceProvider",track.optString("platform","외부 목록"))
                    +"\n원본: "+track.optString("sourceRow")+"\n\n추천 학습과 개인화에는 사용하지 않아요.")
                .setPositiveButton("원본 열기",(a,b)->openUrl(url)).setNegativeButton("닫기",null).show();
            else if(action.equals("곡 정보 수정")){
                LinearLayout box=column();EditText name=new AppCompatEditText(this),artist=new AppCompatEditText(this),time=new AppCompatEditText(this);
                name.setHint("곡명");name.setText(title);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});box.addView(text("곡명",12,true));box.addView(name);
                artist.setHint("아티스트");artist.setText(track.optString("artist"));artist.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});box.addView(text("아티스트",12,true));box.addView(artist);
                time.setHint("영상 시작 위치 · 초");time.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);if(youtube){time.setText(String.valueOf(track.optInt("timestamp",0)));box.addView(text("영상 시작 위치 · 초",12,true));box.addView(time);}
                TextView failure=text("",14,false);failure.setTextColor(getColor(R.color.error_ink));failure.setVisibility(View.GONE);failure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);box.addView(failure);
                AlertDialog edit=dialogBuilder().setTitle("가져온 곡 수정").setView(scroll(box)).setPositiveButton("저장",null).setNegativeButton("취소",null).create();
                edit.setOnShowListener(a->edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    if(busy)return;
                    final JSONObject body=new JSONObject();try{if(name.getText().toString().trim().isEmpty()){name.setError("곡명을 입력해 주세요.");return;}
                        body.put("title",name.getText().toString().trim()).put("artist",artist.getText().toString().trim());
                        if(youtube)body.put("seconds",Integer.parseInt(time.getText().toString()));
                    }catch(Exception error){time.setError("시작 위치를 초 단위로 입력해 주세요.");return;}
                    failure.setVisibility(View.GONE);name.setEnabled(false);artist.setEnabled(false);time.setEnabled(false);edit.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    task(()->{try{return api.object("PUT","/playlists/"+id+"/items/"+item,body);}catch(Exception error){
                        runOnUiThread(()->{if(!isDestroyed()&&edit.isShowing()){
                            failure.setText(errorMessage(error));failure.setVisibility(View.VISIBLE);name.setEnabled(true);artist.setEnabled(true);time.setEnabled(true);edit.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        }});throw error;
                    }},done->{edit.dismiss();openPlaylist(id);},null);
                }));edit.show();
            }
            else if(action.equals("위로 이동")||action.equals("아래로 이동"))moveItem(id,item,action.equals("위로 이동")?-1:1);
            else dialogBuilder().setTitle("이 목록에서 제거할까요?").setMessage(title).setPositiveButton("제거",(a,b)->remove(id,item)).setNegativeButton("취소",null).show();
        }).setNegativeButton("닫기",null).show();
    }

    private void moveItem(String id, String item, int direction) {
        task(() -> {
            JSONArray items=playlist(id).getJSONArray("items"); List<String> order=new ArrayList<>();
            for (int i=0;i<items.length();i++) order.add(items.getJSONObject(i).getString("id"));
            int from=order.indexOf(item), to=from+direction;
            if (from<0) throw new IllegalArgumentException("곡이 목록에서 제거됐어요. 목록을 다시 열어주세요.");
            if (to>=0 && to<order.size()) {
                java.util.Collections.swap(order, from, to);
                if (api==null) local.reorder(id, order);
                else api.request("PUT", "/playlists/"+id+"/items/order", new JSONObject().put("itemIds", new JSONArray(order)));
            }
            return true;
        }, done -> openPlaylist(id), () -> openPlaylist(id));
    }

    private void deletePlaylist(String id) {
        task(() -> {
            if (api==null) local.delete(id);
            else {
                try { api.request("DELETE", "/playlists/"+id, null); }
                catch (ApiClient.ApiError e) { if (e.statusCode!=404) throw e; }
            }
            return savedIds(api);
        }, ids -> { savedTracks=ids; cards.notifyDataSetChanged(); playlistId=null; loadLibrary(); }, () -> deletePlaylist(id));
    }

    private void remove(String id, String item) {
        task(() -> { if (api == null) local.remove(id, item); else api.request("DELETE", "/playlists/"+id+"/items/"+item, null); return savedIds(api); }, ids -> {
            savedTracks=ids; cards.notifyDataSetChanged(); openPlaylist(id);
        }, () -> remove(id,item));
    }

    private void external(Track track) {
        String q = Uri.encode(track.title + " " + track.artist);
        boolean direct = !track.youtubeVideoId.isEmpty();
        View choices=LayoutInflater.from(this).inflate(R.layout.platform_picker,null);
        ((TextView)choices.findViewById(R.id.platform_track)).setText(track.title+" · "+track.artist);
        ((TextView)choices.findViewById(R.id.youtube_music_detail)).setText(direct?"공식 영상으로 듣기":"곡명·아티스트로 검색");
        AlertDialog dialog=dialogBuilder().setTitle("어디에서 들을까요?").setView(scroll(choices)).setNegativeButton("취소",null).create();
        View spotify=choices.findViewById(R.id.spotify_option),youtube=choices.findViewById(R.id.youtube_music_option);
        spotify.setContentDescription("Spotify · 곡명과 아티스트로 검색");
        youtube.setContentDescription("YouTube Music · "+(direct?"공식 영상으로 듣기":"곡명과 아티스트로 검색"));
        spotify.setOnClickListener(v->{dialog.dismiss();openUrl("https://open.spotify.com/search/"+q);});
        youtube.setOnClickListener(v->{dialog.dismiss();openUrl(direct?"https://music.youtube.com/watch?v="+track.youtubeVideoId:"https://music.youtube.com/search?q="+q);});
        dialog.show();
    }

    private void openUrl(String url) {
        stopPlayer(); Uri uri=Uri.parse(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null) { showError("안전한 연결 주소를 확인할 수 없어요.",null); return; }
        Intent intent=new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) { showError("링크를 열 수 있는 음악 앱이나 브라우저가 없어요.",null); }
    }

    private void source(Track t) {
        dialogBuilder().setTitle("곡 정보·출처")
            .setMessage(t.title+"\n"+t.artist+"\n\nMusicBrainz 핵심 메타데이터 · CC0\n음악 태그: "+(t.tagsSource.isEmpty()?"TapTap 직접 작성":t.tagsSource)+"\n\n음원 파일은 저장하지 않아요.")
            .setPositiveButton("출처 열기",(d,w)->openUrl(t.sourceUrl)).setNegativeButton("닫기",null).show();
    }

    private void listen(Track track) {
        stopPlayer();
        if (track.youtubeVideoId.isEmpty()) {
            if(api==null){unavailableVideo(track,"영상 자동 검색을 위한 서버 연결이 필요해요. 외부에서 직접 검색할 수 있어요.");return;}
            if(!ApiClient.consentAccepted(this,api.base)){networkPermission(api.base,()->listen(track));return;}
            ApiClient source=api;
            task(()->{
                try{return source.object("GET","/playback/tracks/"+track.id+"?includeCandidates=true",null);}
                catch(ApiClient.ApiError error){if(error.statusCode==401)throw error;return new JSONObject().put("message",error.getMessage()).put("statusCode",error.statusCode);}
            },result->{
                if(!foregroundActive)return;
                String video=result.optString("videoId");
                if(!video.matches("[A-Za-z0-9_-]{11}")){int status=result.optInt("statusCode");unavailableVideo(track,result.optString("message","영상 검색 결과를 확인하지 못했어요. 외부에서 직접 검색할 수 있어요."),status==429||status>=500);return;}
                if(result.optBoolean("requiresConfirmation",false)){
                    String candidate=result.optString("candidateTitle"),channel=result.optString("candidateChannel");
                    var dialog=dialogBuilder().setTitle("이 영상이 맞나요?").setMessage(candidate+"\n"+channel+"\n\n곡명과 가수가 일치하지만 공식 채널 여부는 확인하지 못했어요.")
                        .setPositiveButton("영상 재생",(d,w)->showPlayback(track.title,video,0,()->external(track),result))
                        .setNegativeButton("닫기",null);
                    JSONArray alternatives=result.optJSONArray("alternatives");
                    if(alternatives!=null&&alternatives.length()>0)dialog.setNeutralButton("다른 영상",(d,w)->choosePlaybackAlternative(track,alternatives));
                    dialog.show();return;
                }
                showPlayback(track.title,video,0,()->external(track),result);
            },()->listen(track));return;
        }
        listenVideo(track.title,track.youtubeVideoId,0,()->external(track));
    }
    private void choosePlaybackAlternative(Track track,JSONArray rows){
        java.util.List<JSONObject> options=new java.util.ArrayList<>();
        for(int i=0;i<Math.min(rows.length(),5);i++){
            JSONObject row=rows.optJSONObject(i);
            if(row!=null&&row.optString("videoId").matches("[A-Za-z0-9_-]{11}")&&row.optBoolean("playbackAllowed",false))options.add(row);
        }
        if(options.isEmpty()){unavailableVideo(track,"다른 재생 가능한 영상을 확인하지 못했어요.");return;}
        String[] labels=new String[options.size()];for(int i=0;i<options.size();i++)labels[i]=options.get(i).optString("candidateTitle")+"\n"+options.get(i).optString("candidateChannel");
        dialogBuilder().setTitle("다른 영상 선택").setItems(labels,(d,index)->{
            JSONObject row=options.get(index);showPlayback(track.title,row.optString("videoId"),0,()->external(track),row);
        }).setNegativeButton("닫기",null).show();
    }

    private void unavailableVideo(Track track,String message){unavailableVideo(track,message,false);}
    private void unavailableVideo(Track track,String message,boolean retry){
        var dialog=dialogBuilder().setTitle("영상 연결을 확인해 주세요").setMessage(message)
            .setPositiveButton("외부에서 검색",(d,w)->external(track)).setNegativeButton("닫기",null);
        if(retry)dialog.setNeutralButton("다시 시도",(d,w)->listen(track));
        dialog.show();
    }

    private void listenVideo(String title,String videoId,int seconds,Runnable fallback) {
        stopPlayer();if(!videoId.matches("[A-Za-z0-9_-]{11}")||seconds<0||seconds>86400){showError("영상 연결 정보를 확인해 주세요.",null);return;}
        if(api==null){externalVideo(title,videoId,seconds,"YouTube에서 이 영상을 재생할 수 있어요.");return;}
        if(!ApiClient.consentAccepted(this,api.base)){networkPermission(api.base,()->listenVideo(title,videoId,seconds,fallback));return;}
        ApiClient source=api;
        task(()->{try{return source.object("GET","/playback/youtube/"+videoId,null);}catch(ApiClient.ApiError error){if(error.statusCode==401)throw error;return null;}catch(Exception unavailable){return null;}},result->{
            if(!foregroundActive)return;
            showPlayback(title,videoId,seconds,fallback,result);
        },null);
    }
    private void showPlayback(String title,String videoId,int seconds,Runnable fallback,JSONObject result){
        if(result!=null&&videoId.equals(result.optString("videoId"))&&Boolean.FALSE.equals(result.opt("madeForKids"))
                &&Boolean.TRUE.equals(result.opt("embeddable"))&&Boolean.TRUE.equals(result.opt("playbackAllowed"))&&!result.optString("checkedAt").isEmpty()){
            embedVideo(title,videoId,seconds,fallback);return;
        }
        externalVideo(title,videoId,seconds,result!=null&&Boolean.TRUE.equals(result.opt("madeForKids"))?"이 영상은 YouTube에서 재생할 수 있어요.":"영상의 재생 정보를 확인하지 못했어요. YouTube에서 열 수 있어요.");
    }
    private void externalVideo(String title,String videoId,int seconds,String message){
        dialogBuilder().setTitle(title).setMessage(message).setPositiveButton("YouTube에서 열기",(d,w)->openUrl("https://www.youtube.com/watch?v="+videoId+(seconds==0?"":"&t="+seconds+"s"))).setNegativeButton("닫기",null).show();
    }
    private void embedVideo(String title,String videoId,int seconds,Runnable fallback){
        LinearLayout box=column(); TextView status=text("YouTube 영상 연결 중…",14,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); box.addView(status);
        try {
            player=new WebView(this); player.setContentDescription(title+" YouTube 공식 플레이어");
            player.getSettings().setJavaScriptEnabled(true); player.getSettings().setDomStorageEnabled(true);
            player.getSettings().setAllowFileAccess(false); player.getSettings().setAllowContentAccess(false);
            player.getSettings().setMediaPlaybackRequiresUserGesture(true);
            player.setWebChromeClient(new WebChromeClient(){ @Override public void onReceivedTitle(WebView view,String title){
                if(title!=null&&title.startsWith("TapTap: "))status.setText(title.substring(8));
            }});
            player.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                    if(request.isForMainFrame()){openUrl(request.getUrl().toString());return true;}return false;
                }
                @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                    if(request.isForMainFrame())status.setText("영상을 연결하지 못했어요. 외부에서 열 수 있어요.");
                }
            });
            box.addView(player,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(240)));
            String origin="https://"+getPackageName();
            String html=ApiClient.read(getAssets().open("player.html")).replace("__VIDEO__",videoId).replace("__ORIGIN__",origin).replace("start:0","start:"+seconds);
            player.loadDataWithBaseURL(origin+"/",html,"text/html","UTF-8",null);
        } catch(Exception | LinkageError e){stopPlayer();status.setText("플레이어를 준비하지 못했어요. 외부에서 열어보세요.");}
        playerDialog=dialogBuilder().setTitle(title).setView(box)
            .setPositiveButton("외부에서 열기",(d,w)->fallback.run()).setNegativeButton("닫기",null).create();
        playerDialog.setOnDismissListener(d->{if(playerDialog==d)stopPlayer();}); playerDialog.show();player.requestFocus(View.FOCUS_DOWN);
    }

    private void stopPlayer(){
        if(player!=null){
            WebView view=player;player=null;
            try {
                if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
                view.stopLoading();view.loadUrl("about:blank");view.onPause();
            } catch(RuntimeException | LinkageError ignored){}
            try {view.destroy();}catch(RuntimeException | LinkageError ignored){}
        }
        if(playerDialog!=null){AlertDialog dialog=playerDialog;playerDialog=null;dialog.dismiss();}
    }

    private void connectService(){
        if(busy)return;
        String base=api==null?TapTapApplication.defaultServer():api.base;
        if(base.isEmpty()){
            showError("서비스를 아직 준비하고 있어요. 잠시 후 다시 시도해 주세요.",this::connectService);return;
        }
        ApiClient candidate=new ApiClient(this,base);
        networkPermission(base,()->task(()->storeConnection(candidate),data->applyStorage(candidate,data),this::connectService,false,candidate),true);
    }

    private void showSettings(){
        if(settingsDialog!=null&&settingsDialog.isShowing())return;
        LinearLayout box=column(); box.addView(text("나의 음악 설정",23,true));
        box.addView(button("계정 · 플랫폼 연결",v->{if(settingsDialog!=null)settingsDialog.dismiss();openHub("account",null);}));
        box.addView(text("추천에는 직접 입력한 요청과 앱 안의 좋아요·싫어요만 사용해요. 저장·외부 열기를 청취 기록으로 간주하지 않아요.",14,false));
        MaterialButton reactions=button(metadataReady?"좋아요·싫어요 관리 ("+feedback.size()+")":"좋아요·싫어요를 불러오지 못했어요",v->manageFeedback());
        reactions.setEnabled(metadataReady);box.addView(reactions);
        box.addView(text("음악 서비스",18,true));
        box.addView(text(api==null?"서비스 이용 안내에 동의하면 추천과 계정 기능을 바로 사용할 수 있어요.":"앱이 음악 서비스에 자동으로 연결돼요. 연결이 끊기면 다시 시도할 수 있어요.",14,false));
        AlertDialog dialog=dialogBuilder().setView(scroll(box)).setNegativeButton("닫기",(d,w)->cancelHubConnection()).create();
        settingsDialog=dialog;dialog.setOnCancelListener(d->cancelHubConnection());
        dialog.setOnDismissListener(d->{if(settingsDialog==dialog)settingsDialog=null;});
        box.addView(button(api==null?"서비스 이용 시작":"연결 다시 시도",v->{dialog.dismiss();connectService();}));
        if(BuildConfig.DEBUG){
            LinearLayout developer=column();developer.setVisibility(View.GONE);
            box.addView(button("개발자 연결 설정",v->developer.setVisibility(developer.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));
            box.addView(developer);
            developer.addView(text("AI 추천 서버 연결",18,true));
            developer.addView(text(api==null?"AI 추천은 아직 연결되지 않았어요. 연결하면 입력한 요청으로 새 곡을 찾을 수 있어요.":"서버에 연결된 내 반응과 목록을 사용해요.",14,false));
            EditText address=new AppCompatEditText(this);address.setSingleLine(true);address.setHint("https://서버주소");address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);TapTapUi.input(address);
            String suggested=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&("ranchu".equals(android.os.Build.HARDWARE)||"goldfish".equals(android.os.Build.HARDWARE))?"http://10.0.2.2:8080":"";
            address.setText(api==null?(pendingHubMode!=null&&hubServerAddress!=null?hubServerAddress:suggested):api.base);developer.addView(address);
            address.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(pendingHubMode!=null)hubServerAddress=s.toString();}public void afterTextChanged(Editable s){}});
            TextView failure=text("",14,false);failure.setTextColor(getColor(R.color.error_ink));failure.setVisibility(View.GONE);failure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);developer.addView(failure);
            developer.addView(button("서버에 연결",v->{
                address.setError(null);failure.setVisibility(View.GONE);
                final ApiClient candidate;
                try {candidate=new ApiClient(this,address.getText().toString());}catch(Exception e){failure.setText(e.getMessage());failure.setVisibility(View.VISIBLE);address.setError(failure.getText());box.setFocusableInTouchMode(true);box.requestFocus();return;}
                networkPermission(candidate.base,()->task(()->{
                    try{
                        return storeConnection(candidate);
                    }catch(Exception error){
                        runOnUiThread(()->{if(!isDestroyed()&&dialog.isShowing()){failure.setText("서버에 연결하지 못했어요. 주소와 서버 실행 상태를 확인해 주세요.");failure.setVisibility(View.VISIBLE);address.setError(failure.getText());box.setFocusableInTouchMode(true);box.requestFocus();}});throw error;
                    }
                },data->{dialog.dismiss();applyStorage(candidate,data);},null,false,candidate),true);
            }));
            if(api!=null)developer.addView(button("AI 연결 해제 · 이 기기의 목록 사용",v->{
                if(busy)return;cancelHubConnection();dialog.dismiss();task(()->storeConnection(null),data->applyStorage(null,data),null);
            }));
        }
        box.addView(button("곡 데이터와 이용 안내",v->dialogBuilder().setTitle("추천 데이터")
            .setMessage("AI가 요청에 맞는 새 곡을 제안하고 MusicBrainz 핵심 메타데이터(CC0)로 실제 곡인지 확인해요. AI 연결이나 곡 검증에 실패하면 고정 곡 목록으로 대체하지 않고 재시도를 안내해요.\n\n외부 서비스에서 가져온 데이터는 추천에 사용하지 않아요. 영상 재생과 외부 연결은 네트워크·서비스·지역에 따라 달라질 수 있어요.")
            .setPositiveButton("출처·이용 조건",(d,w)->openUrl("https://musicbrainz.org/doc/About/Data_License")).setNegativeButton("닫기",null).show()));
        box.addView(text("앱 정보와 개인정보",18,true));
        box.addView(text("TapTap "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")",14,false));
        box.addView(button("개인정보 처리 안내",v->openServicePage("/privacy")));
        box.addView(button("계정·데이터 삭제 안내",v->dialogBuilder().setTitle("계정과 데이터 삭제")
            .setMessage("계정 화면에서 ‘계정 삭제’를 선택하고 비밀번호 또는 카카오 계정을 다시 확인하면 로그인한 TapTap 계정의 목록·반응·서비스 연결 정보를 삭제할 수 있어요. 외부 음악 서비스의 원본 플레이리스트는 유지돼요. 이 기기에만 저장한 목록은 서버 계정과 별개예요.")
            .setPositiveButton("계정 관리 열기",(d,w)->{dialog.dismiss();openHub("account",null);}).setNeutralButton("자세한 삭제 안내",(d,w)->openServicePage("/account-deletion")).setNegativeButton("닫기",null).show()));
        box.addView(button("오픈소스 라이선스",v->showLicenses()));
        if(!BuildConfig.SUPPORT_EMAIL.isEmpty())box.addView(button("문의하기",v->{try{startActivity(new Intent(Intent.ACTION_SENDTO,Uri.fromParts("mailto",BuildConfig.SUPPORT_EMAIL,null)).putExtra(Intent.EXTRA_SUBJECT,"TapTap "+BuildConfig.VERSION_NAME+" 문의"));}catch(ActivityNotFoundException error){showError("메일 앱을 찾을 수 없어요. 문의 주소: "+BuildConfig.SUPPORT_EMAIL,null);}}));
        dialog.show();
    }

    private void openServicePage(String path){
        String base=api==null?BuildConfig.API_BASE:api.base;
        if(base.isEmpty()){dialogBuilder().setTitle("서비스 안내").setMessage("서비스를 준비 중이에요. 잠시 후 다시 시도해 주세요. 음악 계정 연결은 선택 사항이에요.").setPositiveButton("확인",null).show();return;}
        openConsentLink(base+path);
    }
    private void openConsentLink(String address){
        Uri uri=Uri.parse(address);if("https".equals(uri.getScheme())){openUrl(address);return;}
        if(BuildConfig.DEBUG&&"http".equals(uri.getScheme())&&ApiClient.localHost(uri.getHost()))try{startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));return;}catch(ActivityNotFoundException ignored){}
        showError("서비스 안내를 열 브라우저가 없어요.",null);
    }

    private void showLicenses(){
        LinearLayout box=column();box.addView(text("앱에 포함된 오픈소스",18,true));
        box.addView(text("AndroidX · Material Components for Android · Kakao Android SDK · Kotlin · kotlinx.coroutines · kotlinx.serialization · OkHttp · Okio · Retrofit · Retrofit Kotlin Serialization Converter\nApache License 2.0",14,false));
        box.addView(button("포함 라이브러리와 저작권 안내",v->showLicense("오픈소스 고지","licenses/dependencies.txt")));
        box.addView(button("Apache License 2.0 전문",v->showLicense("Apache License 2.0","licenses/apache-2.0.txt")));
        box.addView(button("Pretendard · SIL Open Font License",v->showLicense("Pretendard","licenses/pretendard.txt")));
        dialogBuilder().setTitle("오픈소스 라이선스").setView(scroll(box)).setPositiveButton("닫기",null).show();
    }
    private void showLicense(String title,String asset){
        try{TextView license=text(ApiClient.read(getAssets().open(asset)),12,false);license.setTextIsSelectable(true);dialogBuilder().setTitle(title).setView(scroll(license)).setPositiveButton("닫기",null).show();}
        catch(Exception error){showError("라이선스 안내를 불러오지 못했어요.",null);}
    }

    private void manageFeedback(){
        if(!metadataReady)return;
        List<Track> reacted=new ArrayList<>();for(Track t:catalogue)if(feedback.containsKey(t.id))reacted.add(t);
        String[] labels=new String[reacted.size()];for(int i=0;i<labels.length;i++){Track t=reacted.get(i);labels[i]=(feedback.get(t.id)==1?"좋아요 · ":"싫어요 · ")+t.title+" — "+t.artist;}
        MaterialAlertDialogBuilder dialog=dialogBuilder().setTitle("반응을 눌러 취소하세요").setNegativeButton("닫기",null);
        if(labels.length==0)dialog.setMessage("아직 남긴 반응이 없어요. 추천 카드에서 좋아요·싫어요를 선택할 수 있어요.");
        else dialog.setItems(labels,(d,w)->react(reacted.get(w),0)).setNeutralButton("전체 초기화",(d,w)->dialogBuilder().setTitle("반응을 모두 지울까요?").setMessage("저장한 플레이리스트는 유지돼요.")
            .setPositiveButton("초기화",(a,b)->task(()->{if(api==null)local.clearFeedback();else api.request("DELETE","/me/feedback",null);return true;},done->{feedback.clear();cards.notifyDataSetChanged();updateCounter();},null)).setNegativeButton("취소",null).show());
        dialog.show();
    }

    private MaterialAlertDialogBuilder dialogBuilder(){return TapTapUi.dialog(this,dialogs);}

    private LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(16));return box;}
    private ScrollView scroll(View child){ScrollView view=new ScrollView(this);view.addView(child);return view;}
    private TextView text(String value,int size,boolean bold){TextView view=new AppCompatTextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(getColor(bold?R.color.ink:R.color.muted));view.setPadding(0,dp(10),0,dp(10));TapTapUi.font(view,bold);view.setLineSpacing(dp(3),1);return view;}
    private MaterialButton button(String label,View.OnClickListener action){MaterialButton view=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);view.setText(label);TapTapUi.button(view);view.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);view.setOnClickListener(v->{if(!busy)action.onClick(v);});return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private <T> void task(Callable<T> operation,Consumer<T> success,Runnable retry){
        task(operation,success,retry,false);
    }
    private <T> void task(Callable<T> operation,Consumer<T> success,Runnable retry,boolean refreshing){
        task(operation,success,retry,refreshing,null);
    }
    private <T> void task(Callable<T> operation,Consumer<T> success,Runnable retry,boolean refreshing,ApiClient connectingSource){
        if(pendingTask.current()!=null||busy&&!refreshing)return;
        ApiClient taskApi=api;String taskIdentity=RecommendationRequest.identity(taskApi);PendingTask.State pending=pendingTask.begin(taskOwner);
        worker.execute(()->{
            try {if(!taskIdentity.equals(RecommendationRequest.identity(taskApi)))throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                T result=operation.call();runOnUiThread(()->{
                PendingTask.State completed=pendingTask.finish(pending,null);
                if(isDestroyed()||isFinishing())return;
                pendingTask.consume(completed);
                boolean initialSession=taskApi!=null&&taskIdentity.isEmpty()&&(taskApi.ownsCurrentSession()
                    ||connectingSource!=null&&connectingSource.base.equals(taskApi.base)&&connectingSource.ownsCurrentSession());
                if(api==taskApi&&(taskIdentity.equals(RecommendationRequest.identity(taskApi))||initialSession)
                    &&(connectingSource==null||connectingSource.ownsCurrentSession()))success.accept(result);
            });}
            catch(Exception error){runOnUiThread(()->{
                PendingTask.State completed=pendingTask.finish(pending,error);
                if(isDestroyed()||isFinishing())return;
                pendingTask.consume(completed);showError(errorMessage(error),retry==null?null:()->{if(api==taskApi&&taskIdentity.equals(RecommendationRequest.identity(taskApi)))retry.run();});
            });}
        });
    }
    private void hideKeyboard(){
        View root=findViewById(R.id.main);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        root.requestFocus();
    }
    private String errorMessage(Exception error){String message=error instanceof org.json.JSONException?"서버 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.":error instanceof java.io.IOException?"네트워크에 연결하지 못했어요. 설정과 연결 상태를 확인해 주세요.":error.getMessage();return message==null?"처리하지 못했어요. 다시 시도해 주세요.":message;}
    private void clearResults(){
        picks=new ArrayList<>();hiddenTracks.clear();hasResults=false;resultConditions=new Recommender.Conditions();restoredPlaylistId=null;restoredLibraryScroll=0;
        findViewById(R.id.request_panel).setVisibility(View.VISIBLE);
        findViewById(R.id.results).setVisibility(View.GONE);
        findViewById(R.id.card_navigation).setVisibility(View.GONE);
        TextView empty=findViewById(R.id.empty);empty.setText(R.string.home_empty);empty.setVisibility(View.VISIBLE);cards.notifyDataSetChanged();updateDiscoverAppearance();
    }
    private void clearStorageResults(){
        dismissFailure();
        metadataReady=false;catalogue=new ArrayList<>();feedback=new HashMap<>();savedTracks=new HashSet<>();
        replacementPosition=-1;recommendation.discard();clearResults();playlistId=null;libraryQuery="";
        ((LinearLayout)findViewById(R.id.library)).removeAllViews();
    }
    private void networkPermission(String base,Runnable action){
        networkPermission(base,action,false);
    }
    private void networkPermission(String base,Runnable action,boolean connecting){
        if(!base.isEmpty()&&!ApiClient.consentAccepted(this,base)){
            TapTapUi.consent(this,dialogs,base,()->{try{ApiClient.acceptConsent(this,base);networkPermission(base,action,connecting);}catch(IllegalStateException error){showError(error.getMessage(),()->networkPermission(base,action,connecting));}},()->{
                ApiClient.declineConsent(this,base);cancelHubConnection();
                if(api!=null&&api.base.equals(base)){api=null;storageIdentity="local";conversation.clear();settings.edit().remove("conversation").apply();clearStorageResults();refreshData(()->{if(librarySelected)loadLibrary();});}
            },this::openConsentLink);return;
        }
        if(android.os.Build.VERSION.SDK_INT>=37&&!base.isEmpty()&&ApiClient.localHost(java.net.URI.create(base).getHost())
                &&checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingConnection=action;pendingPermissionBase=base;pendingServerConnection=connecting;
            localPermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        }else action.run();
    }
    private void updateBusy(){
        busy=pendingTask.current()!=null||recommendation.current().loading;findViewById(R.id.progress).setVisibility(busy?View.VISIBLE:View.INVISIBLE);
        findViewById(R.id.recommend).setEnabled(!busy && metadataReady);findViewById(R.id.settings).setEnabled(!busy);
        findViewById(R.id.save_results).setEnabled(!busy && metadataReady && !picks.isEmpty());
        findViewById(R.id.edit_request).setEnabled(!busy);
        findViewById(R.id.refine_request).setEnabled(!busy&&metadataReady);
        android.view.Menu menu=((BottomNavigationView)findViewById(R.id.navigation)).getMenu();
        for(int i=0;i<menu.size();i++)menu.getItem(i).setEnabled(!busy);
        updateMode();updateCounter();cards.notifyDataSetChanged();
        if(!busy&&foregroundRefreshIdentity!=null)findViewById(R.id.main).post(this::refreshForeground);
        if(!busy&&pendingHubMode!=null)findViewById(R.id.main).post(this::continuePendingHub);
    }
    private Snackbar snackbar(String message,int duration){
        View anchor=findViewById(R.id.card_navigation);
        if(!anchor.getGlobalVisibleRect(new android.graphics.Rect()))anchor=findViewById(R.id.navigation);
        Snackbar bar=Snackbar.make(findViewById(R.id.main),message,duration).setAnchorView(anchor);
        bar.setBackgroundTint(getColor(R.color.surface)).setTextColor(getColor(R.color.ink)).setActionTextColor(getColor(R.color.deck_accent));
        bar.getView().setBackground(TapTapUi.rounded(this,R.color.surface,16,true));
        bar.setTextMaxLines(4);
        bar.setAnchorViewLayoutListenerEnabled(true);return bar;
    }
    private void dismissFailure(){if(failureBar!=null){failureBar.dismiss();failureBar=null;}}
    private void showError(String message,Runnable retry){dismissFailure();Snackbar bar=snackbar(message,retry==null?Snackbar.LENGTH_LONG:Snackbar.LENGTH_INDEFINITE);failureBar=bar;if(retry!=null)bar.setAction("재시도",v->{dismissFailure();retry.run();});bar.show();}

    private final class CardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            View card=LayoutInflater.from(parent.getContext()).inflate(R.layout.track_card,parent,false);
            card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                View image=v.findViewById(R.id.artwork);
                int details=v.findViewById(R.id.track_details).getMeasuredHeight();
                int height=Math.max(dp(80),Math.min(Math.round((r-l)*1.18f),b-t-details-dp(32)));
                if(image.getLayoutParams().height!=height){image.getLayoutParams().height=height;image.requestLayout();}
            });
            return new RecyclerView.ViewHolder(card){};
        }
        @Override public int getItemCount(){return picks.size();}
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position){
            Recommender.Pick pick=picks.get(position);Track track=pick.track;View view=holder.itemView;
            ((TextView)view.findViewById(R.id.track_title)).setText(track.title);((TextView)view.findViewById(R.id.track_artist)).setText(track.artist);
            view.findViewById(R.id.track_title).setContentDescription(track.title+" · 곡 정보와 출처 보기");
            ((TextView)view.findViewById(R.id.reason)).setText(pick.reason);
            List<String> labels=new ArrayList<>();for(String tag:track.tags)if(labels.size()<3)labels.add(Recommender.label(tag));
            ((TextView)view.findViewById(R.id.track_tags)).setText(android.text.TextUtils.join("  ·  ",labels));
            artwork.load((ImageView)view.findViewById(R.id.track_artwork),track.youtubeVideoId,track.artworkUrl,bitmap->{
                if(!picks.isEmpty()&&picks.get(Math.min(pager.getCurrentItem(),picks.size()-1)).track.id.equals(track.id))updateDiscoverAppearance();
            });
            MaterialButton save=view.findViewById(R.id.save);
            save.setText(savedTracks.contains(track.id)?"저장됨 · 추가":"목록에 저장");save.setOnClickListener(v->chooseSave(track));
            view.findViewById(R.id.external).setOnClickListener(v->external(track));
            view.findViewById(R.id.track_title).setOnClickListener(v->source(track));
            view.findViewById(R.id.edit_result).setOnClickListener(v->editResult(track));view.findViewById(R.id.edit_result).setEnabled(!busy && metadataReady);
            save.setEnabled(!busy && metadataReady);
        }
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state){
        super.onSaveInstanceState(state);JSONArray rows=new JSONArray();try{for(Recommender.Pick p:picks)rows.put(new JSONObject().put("track",TrackJson.write(p.track)).put("reason",p.reason));}catch(Exception ignored){}
        state.putString("picks",rows.toString());state.putInt("index",pager.getCurrentItem());state.putString("mode",resultMode);state.putString("notice",resultNotice);state.putBoolean("library",librarySelected);
        state.putString("libraryQuery", libraryQuery);
        state.putString("storageBase",api==null?"":api.base);
        state.remove("storageIdentity");state.putString("storageOwner",ApiClient.ownerFingerprint(api==null?"":api.base,storageIdentity));state.putBoolean("returningFromHub",returningFromHub);
        state.putParcelable("pendingHubPlaylist",pendingHubPlaylist);
        state.putString("pendingHubMode",pendingHubMode);state.putString("hubServerAddress",hubServerAddress);
        state.putBoolean("pendingHubSettings",pendingHubMode!=null&&(pendingHubSettings||settingsDialog!=null&&settingsDialog.isShowing()));
        if(refinementPrompt!=null){state.putString("refinementDraft",refinementPrompt.getText().toString());
            List<String> kept=new ArrayList<>();for(Map.Entry<String,android.widget.CheckBox> entry:refinementChoices.entrySet())if(entry.getValue().isChecked())kept.add(entry.getKey());
            state.putString("refinementKeeps",new JSONArray(kept).toString());}
        state.putString("pendingPermissionBase",pendingPermissionBase);state.putBoolean("pendingServerConnection",pendingServerConnection);
        if(librarySelected){state.putString("playlistId",playlistId);state.putInt("libraryScroll",findViewById(R.id.library_scroll).getScrollY());}
        state.putBoolean("hasResults",hasResults);state.putBoolean("editingRequest",hasResults && findViewById(R.id.request_panel).getVisibility()==View.VISIBLE);
        state.putBoolean("requestPending",recommendation.current().loading);
        state.putInt("replacementPosition",replacementPosition);state.putInt("replacementKeptCount",replacementKeptCount);
        if(recommendation.current().loading&&recommendation.snapshot()!=null)state.putString("pendingRecommendation",recommendation.snapshot().toString());
        try { state.putString("conditions",TrackJson.conditions(resultConditions).toString()); } catch(Exception ignored){}
        state.putString("hiddenTracks",new JSONArray(hiddenTracks).toString());
    }
    @Override protected void onStop(){stopPlayer();super.onStop();}
    @Override protected void onDestroy(){
        artwork.close();
        dismissFailure();stopPlayer();for(AlertDialog dialog:dialogs)dialog.dismiss();dialogs.clear();
        worker.execute(local::close);worker.shutdown();super.onDestroy();
    }
}
