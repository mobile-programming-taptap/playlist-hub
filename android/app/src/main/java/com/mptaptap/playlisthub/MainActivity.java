package com.mptaptap.playlisthub;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.mptaptap.core.Recommender;
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
    private List<Track> catalogue = new ArrayList<>();
    private List<Recommender.Pick> picks = new ArrayList<>();
    private Map<String, Integer> feedback = new HashMap<>();
    private Set<String> savedTracks = new HashSet<>();
    private LocalStore local;
    private ApiClient api;
    private SharedPreferences settings;
    private ViewPager2 pager;
    private CardAdapter cards;
    private EditText prompt;
    private boolean busy;
    private boolean librarySelected;
    private String playlistId;
    private String resultMode = "basic";
    private String resultNotice = "";
    private WebView player;
    private AlertDialog playerDialog;
    private Runnable pendingConnection;
    private final ActivityResultLauncher<String> localPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
        Runnable action=pendingConnection;pendingConnection=null;
        if(granted&&action!=null)action.run();
        else {
            if(api!=null&&ApiClient.localHost(java.net.URI.create(api.base).getHost())){api=null;refreshData(()->{});}
            new MaterialAlertDialogBuilder(this).setTitle("서버에 연결하지 않았어요")
                .setMessage("로컬 서버 연결에는 근처 기기 권한이 필요해요. 기본 추천은 계속 사용할 수 있어요.").setPositiveButton("확인",null).show();
        }
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); EdgeToEdge.enable(this); setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            return WindowInsetsCompat.CONSUMED;
        });
        local = new LocalStore(this); settings = getSharedPreferences("settings", MODE_PRIVATE);
        prompt = findViewById(R.id.prompt); prompt.setText(settings.getString("prompt", ""));
        pager = findViewById(R.id.pager); cards = new CardAdapter(); pager.setAdapter(cards);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int index) { stopPlayer(); updateCounter(); }
        });
        findViewById(R.id.recommend).setOnClickListener(v -> recommend());
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.example_rain).setOnClickListener(v -> prompt.setText("비 오는 밤에 듣기 좋은 잔잔한 음악"));
        findViewById(R.id.example_study).setOnClickListener(v -> prompt.setText("공부할 때 집중할 수 있는 가사 없는 피아노 음악"));
        findViewById(R.id.example_workout).setOnClickListener(v -> prompt.setText("운동할 때 듣기 좋은 신나는 음악"));
        findViewById(R.id.previous).setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() - 1, true));
        findViewById(R.id.next).setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() + 1, true));
        ((BottomNavigationView)findViewById(R.id.navigation)).setOnItemSelectedListener(item -> {
            librarySelected = item.getItemId() == R.id.nav_library; stopPlayer();
            findViewById(R.id.recommend_scroll).setVisibility(librarySelected ? View.GONE : View.VISIBLE);
            findViewById(R.id.library_scroll).setVisibility(librarySelected ? View.VISIBLE : View.GONE);
            if (librarySelected) { playlistId = null; loadLibrary(); }
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy && librarySelected) return;
                if (librarySelected && playlistId != null) { playlistId = null; loadLibrary(); }
                else if (librarySelected) ((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_recommend);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        try {
            catalogue = TrackJson.list(new JSONArray(ApiClient.read(getAssets().open("tracks.json"))));
            if (state != null && state.containsKey("picks")) {
                picks = TrackJson.picks(new JSONArray(state.getString("picks")));
                resultMode = state.getString("mode", "basic"); resultNotice = state.getString("notice", "");
                showResults(); pager.setCurrentItem(state.getInt("index"), false);
            }
        } catch (Exception e) { showError("곡 목록을 불러오지 못했어요.", null); }
        String base = settings.getString("server", "");
        if (!base.isEmpty()) {
            try { api = new ApiClient(this, base); } catch (Exception e) { showError("저장된 서버 주소를 확인해 주세요.", null); }
        }
        networkPermission(api==null?"":api.base, () -> refreshData(() -> {
            if (state != null && state.getBoolean("library")) ((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_library);
        }));
    }

    private void refreshData(Runnable success) {
        task(() -> {
            if (api != null) {
                api.connect(); catalogue = TrackJson.list(api.array("/catalogue"));
                JSONObject reactions = api.object("GET", "/me/feedback", null);
                Map<String, Integer> f = new HashMap<>(); reactions.keys().forEachRemaining(id -> f.put(id, reactions.optInt(id)));
                return new Object[]{f, savedIds()};
            }
            return new Object[]{local.feedback(), savedIds()};
        }, data -> {
            feedback = (Map<String, Integer>)data[0]; savedTracks = (Set<String>)data[1];
            updateMode(); cards.notifyDataSetChanged(); success.run();
        }, () -> refreshData(success));
    }

    private Set<String> savedIds() throws Exception {
        Set<String> ids = new HashSet<>();
        if (api != null) { JSONArray rows = api.array("/me/saved-tracks"); for (int i=0;i<rows.length();i++) ids.add(rows.getString(i)); }
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
        String request = prompt.getText().toString().trim();
        TextInputLayout field = findViewById(R.id.prompt_layout);
        try { Recommender.parse(request); field.setError(null); }
        catch (IllegalArgumentException e) { field.setError(e.getMessage()); return; }
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        prompt.clearFocus(); stopPlayer(); settings.edit().putString("prompt", request).apply();
        task(() -> {
            if (api == null) {
                Recommender.Result result = Recommender.recommend(catalogue, Recommender.parse(request), local.feedback(), "basic");
                return new Object[]{result.tracks, result.mode, result.notice};
            }
            JSONObject response = api.object("POST", "/recommendations", new JSONObject().put("prompt", request));
            return new Object[]{TrackJson.picks(response.getJSONArray("tracks")), response.getString("mode"), response.optString("notice")};
        }, result -> {
            picks = (List<Recommender.Pick>)result[0]; resultMode = (String)result[1]; resultNotice = (String)result[2];
            showResults(); pager.setCurrentItem(0, false);
            findViewById(R.id.results).post(() -> ((androidx.core.widget.NestedScrollView)findViewById(R.id.recommend_scroll)).smoothScrollTo(0, findViewById(R.id.results).getTop()));
        }, this::recommend);
    }

    private void showResults() {
        findViewById(R.id.results).setVisibility(View.VISIBLE);
        findViewById(R.id.empty).setVisibility(picks.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView)findViewById(R.id.empty)).setText("조건에 맞는 곡을 찾지 못했어요.\n장르나 언어 조건을 줄여 다시 추천받아 보세요.");
        ((TextView)findViewById(R.id.notice)).setText((resultMode.equals("openai") ? "AI 추천" : "기본 추천") + (resultNotice.isEmpty() ? " · 한 곡씩 들어보세요." : "\n" + resultNotice));
        pager.setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        cards.notifyDataSetChanged(); updateCounter();
    }

    private void updateCounter() {
        int index = picks.isEmpty() ? 0 : pager.getCurrentItem() + 1;
        ((TextView)findViewById(R.id.counter)).setText(index + " / " + picks.size());
        findViewById(R.id.previous).setEnabled(!busy && index > 1);
        findViewById(R.id.next).setEnabled(!busy && index > 0 && index < picks.size());
    }

    private void updateMode() {
        ((TextView)findViewById(R.id.mode_label)).setText(api == null ? "기본 추천 · 반응과 목록은 이 기기에 저장돼요." : "서버 연결됨 · 한 문장으로 음악을 찾아보세요.");
    }

    private void react(Track track, int reaction) {
        task(() -> {
            if (api == null) local.react(track.id, reaction);
            else api.request(reaction == 0 ? "DELETE" : "PUT", "/me/feedback/" + track.id, reaction == 0 ? null : new JSONObject().put("reaction", reaction));
            return reaction;
        }, value -> {
            if (value == 0) feedback.remove(track.id); else feedback.put(track.id, value);
            cards.notifyDataSetChanged();
            Snackbar.make(findViewById(R.id.main), value == 0 ? "반응을 취소했어요." : "다음 추천에 반영할게요.", Snackbar.LENGTH_SHORT).show();
        }, () -> react(track, reaction));
    }

    private JSONArray playlists() throws Exception { return api == null ? local.playlists() : api.array("/playlists"); }
    private JSONObject playlist(String id) throws Exception { return api == null ? local.playlist(id) : api.object("GET", "/playlists/" + id, null); }

    private void chooseSave(Track track) {
        task(this::playlists, lists -> {
            String[] labels = new String[lists.length()+1];
            for (int i=0;i<lists.length();i++) labels[i] = lists.optJSONObject(i).optString("title") + " · " + lists.optJSONObject(i).optInt("size") + "곡";
            labels[lists.length()] = "+ 새 플레이리스트";
            new MaterialAlertDialogBuilder(this).setTitle("어디에 저장할까요?").setItems(labels, (dialog, which) -> {
                if (which == lists.length()) createPlaylist(track);
                else save(track, lists.optJSONObject(which).optString("id"));
            }).setNegativeButton("취소", null).show();
        }, () -> chooseSave(track));
    }

    private void createPlaylist(Track firstTrack) {
        EditText name = new EditText(this); name.setHint("예: 비 오는 날의 음악"); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        LinearLayout box = column(); box.addView(name);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("새 플레이리스트").setView(box).setPositiveButton("만들기", null).setNegativeButton("취소", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("이름을 입력해 주세요."); return; }
            task(() -> api == null ? local.create(title) : api.object("POST", "/playlists", new JSONObject().put("title", title)), created -> {
                dialog.dismiss();
                if (firstTrack != null) save(firstTrack, created.optString("id")); else loadLibrary();
            }, null);
        })); dialog.show();
    }

    private void save(Track track, String id) {
        task(() -> {
            if (api == null) local.save(id, track);
            else api.object("POST", "/playlists/" + id + "/items", new JSONObject().put("trackId", track.id));
            return track.id;
        }, saved -> {
            savedTracks.add(saved); cards.notifyDataSetChanged();
            Snackbar.make(findViewById(R.id.main), "플레이리스트에 저장했어요.", Snackbar.LENGTH_LONG)
                    .setAction("목록 보기", v -> ((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_library)).show();
        }, () -> save(track, id));
    }

    private void loadLibrary() {
        if (!librarySelected) return;
        task(this::playlists, lists -> {
            if (!librarySelected || playlistId != null) return;
            LinearLayout box = findViewById(R.id.library); box.removeAllViews();
            box.addView(text("내 플레이리스트", 27, true));
            box.addView(text(api == null ? "좋아하는 음악을 이 기기에 모아두세요." : "내가 저장한 음악을 다시 들어보세요.", 14, false));
            box.addView(button("+ 새 플레이리스트", v -> createPlaylist(null)));
            if (lists.length() == 0) box.addView(text("아직 저장한 목록이 없어요.\n추천 곡에서 ‘목록에 저장’을 눌러보세요.", 16, false));
            for (int i=0;i<lists.length();i++) {
                JSONObject row = lists.optJSONObject(i); String id = row.optString("id");
                box.addView(button(row.optString("title") + "\n" + row.optInt("size") + "곡 · 앱 추천에서 저장", v -> openPlaylist(id)));
            }
        }, this::loadLibrary);
    }

    private void openPlaylist(String id) {
        task(() -> playlist(id), data -> {
            if (!librarySelected) return;
            playlistId = id; LinearLayout box = findViewById(R.id.library); box.removeAllViews();
            box.addView(button("‹ 내 목록으로", v -> { playlistId=null; loadLibrary(); }));
            box.addView(text(data.optString("title"), 27, true));
            JSONArray items = data.optJSONArray("items");
            box.addView(text(items.length() + "곡", 14, false));
            if (items.length() == 0) box.addView(text("아직 곡이 없어요. 추천 화면에서 음악을 담아보세요.", 16, false));
            for (int i=0;i<items.length();i++) {
                JSONObject row = items.optJSONObject(i);
                try {
                    Track track = TrackJson.read(row.getJSONObject("track")); String item = row.getString("id");
                    box.addView(button((i+1) + ". " + track.title + "\n" + track.artist, v -> new MaterialAlertDialogBuilder(this).setTitle(track.title)
                        .setItems(new String[]{"들어보기", "다른 플랫폼에서 열기", "곡 정보·출처", "이 목록에서 제거"}, (d, choice) -> {
                            if (choice == 0) listen(track);
                            else if (choice == 1) external(track);
                            else if (choice == 2) source(track);
                            else new MaterialAlertDialogBuilder(this).setTitle("이 목록에서 제거할까요?").setMessage(track.title)
                                .setPositiveButton("제거", (a,b) -> remove(id, item)).setNegativeButton("취소", null).show();
                        }).setNegativeButton("닫기", null).show()));
                } catch (Exception e) { box.addView(text("곡 정보를 불러오지 못했어요.", 14, false)); }
            }
        }, () -> openPlaylist(id));
    }

    private void remove(String id, String item) {
        task(() -> { if (api == null) local.remove(id, item); else api.request("DELETE", "/playlists/"+id+"/items/"+item, null); return savedIds(); }, ids -> {
            savedTracks=ids; cards.notifyDataSetChanged(); openPlaylist(id);
        }, () -> remove(id,item));
    }

    private void external(Track track) {
        String q = Uri.encode(track.title + " " + track.artist);
        boolean direct = !track.youtubeVideoId.isEmpty();
        new MaterialAlertDialogBuilder(this).setTitle("어디에서 들을까요?")
            .setItems(new String[]{"Spotify · 곡 검색", "YouTube Music · " + (direct ? "영상으로 연결" : "곡 검색")}, (d, choice) -> openUrl(choice==0 ?
                "https://open.spotify.com/search/"+q : direct ? "https://music.youtube.com/watch?v="+track.youtubeVideoId : "https://music.youtube.com/search?q="+q))
            .setNegativeButton("취소", null).show();
    }

    private void openUrl(String url) {
        stopPlayer(); Uri uri=Uri.parse(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null) { showError("안전한 연결 주소를 확인할 수 없어요.",null); return; }
        Intent intent=new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) { showError("링크를 열 수 있는 음악 앱이나 브라우저가 없어요.",null); }
    }

    private void source(Track t) {
        new MaterialAlertDialogBuilder(this).setTitle("곡 정보·출처")
            .setMessage(t.title+"\n"+t.artist+"\n\nMusicBrainz 핵심 메타데이터 · CC0\n분위기 태그: TapTap 직접 작성\n\n음원 파일은 저장하지 않아요.")
            .setPositiveButton("출처 열기",(d,w)->openUrl(t.sourceUrl)).setNegativeButton("닫기",null).show();
    }

    private void listen(Track track) {
        stopPlayer();
        if (track.youtubeVideoId.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("외부에서 찾아볼까요?").setMessage("이 곡의 공식 영상 연결은 아직 확인하지 못했어요. 곡명·아티스트로 검색할 수 있어요.")
                .setPositiveButton("외부에서 검색",(d,w)->external(track)).setNegativeButton("닫기",null).show(); return;
        }
        LinearLayout box=column(); TextView status=text("YouTube 영상 연결 중…",14,false); box.addView(status);
        player=new WebView(this); player.setContentDescription(track.title+" YouTube 공식 플레이어");
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
        try {
            String html=ApiClient.read(getAssets().open("player.html")).replace("__VIDEO__",track.youtubeVideoId).replace("__ORIGIN__",origin);
            player.loadDataWithBaseURL(origin+"/",html,"text/html","UTF-8",null);
        } catch(Exception e){status.setText("플레이어를 준비하지 못했어요. 외부에서 열어보세요.");}
        playerDialog=new MaterialAlertDialogBuilder(this).setTitle(track.title).setView(box)
            .setPositiveButton("외부에서 열기",(d,w)->external(track)).setNegativeButton("닫기",null).create();
        playerDialog.setOnDismissListener(d->stopPlayer()); playerDialog.show();
    }

    private void stopPlayer(){
        if(player!=null){WebView view=player;player=null;view.stopLoading();view.loadUrl("about:blank");view.onPause();view.destroy();}
        if(playerDialog!=null){AlertDialog dialog=playerDialog;playerDialog=null;dialog.dismiss();}
    }

    private void showSettings(){
        LinearLayout box=column(); box.addView(text("나의 음악 설정",23,true));
        box.addView(text("추천에는 직접 입력한 요청과 앱 안의 좋아요·싫어요만 사용해요. 저장·외부 열기를 청취 기록으로 간주하지 않아요.",14,false));
        box.addView(button("좋아요·싫어요 관리 ("+feedback.size()+")",v->manageFeedback()));
        box.addView(text("AI 추천 서버 연결",18,true));
        box.addView(text(api==null?"기본 추천을 사용 중이에요. 목록은 이 기기에 보관돼요.":"서버에 연결된 내 반응과 목록을 사용해요.",14,false));
        EditText address=new EditText(this);address.setSingleLine(true);address.setHint("https://서버주소");address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(api==null?"":api.base);box.addView(address);
        AlertDialog dialog=new MaterialAlertDialogBuilder(this).setView(scroll(box)).setNegativeButton("닫기",null).create();
        box.addView(button("서버에 연결",v->{
            final ApiClient candidate;
            try {candidate=new ApiClient(this,address.getText().toString());}catch(Exception e){address.setError(e.getMessage());return;}
            networkPermission(candidate.base,()->task(()->{
                try{
                    candidate.connect();candidate.array("/catalogue");candidate.object("GET","/me/feedback",null);
                    if(!settings.edit().putString("server",candidate.base).commit())throw new IllegalStateException("연결 정보를 저장하지 못했어요.");
                    return candidate;
                }catch(Exception error){
                    runOnUiThread(()->{if(!isDestroyed())address.setError("서버에 연결하지 못했어요. 주소와 서버 실행 상태를 확인해 주세요.");});throw error;
                }
            },connected->{api=connected;clearResults();dialog.dismiss();refreshData(()->{if(librarySelected){playlistId=null;loadLibrary();}});},null));
        }));
        if(api!=null)box.addView(button("이 기기의 기본 추천 사용",v->{
            if(busy)return;settings.edit().remove("server").apply();api=null;clearResults();dialog.dismiss();
            refreshData(()->{if(librarySelected){playlistId=null;loadLibrary();}});
        }));
        box.addView(button("곡 데이터와 이용 안내",v->new MaterialAlertDialogBuilder(this).setTitle("추천 데이터")
            .setMessage("MusicBrainz 핵심 메타데이터(CC0)로 검증한 "+catalogue.size()+"곡과 직접 작성한 태그를 사용해요. 곡이 부족하면 조건을 줄여 주세요.\n\n외부 서비스에서 가져온 데이터는 추천에 사용하지 않아요. 영상 재생과 외부 연결은 네트워크·서비스·지역에 따라 달라질 수 있어요.")
            .setPositiveButton("출처·이용 조건",(d,w)->openUrl("https://musicbrainz.org/doc/About/Data_License")).setNegativeButton("닫기",null).show()));
        dialog.show();
    }

    private void manageFeedback(){
        List<Track> reacted=new ArrayList<>();for(Track t:catalogue)if(feedback.containsKey(t.id))reacted.add(t);
        String[] labels=new String[reacted.size()];for(int i=0;i<labels.length;i++){Track t=reacted.get(i);labels[i]=(feedback.get(t.id)==1?"좋아요 · ":"싫어요 · ")+t.title+" — "+t.artist;}
        MaterialAlertDialogBuilder dialog=new MaterialAlertDialogBuilder(this).setTitle("반응을 눌러 취소하세요").setNegativeButton("닫기",null);
        if(labels.length==0)dialog.setMessage("아직 남긴 반응이 없어요. 추천 카드에서 좋아요·싫어요를 선택할 수 있어요.");
        else dialog.setItems(labels,(d,w)->react(reacted.get(w),0)).setNeutralButton("전체 초기화",(d,w)->new MaterialAlertDialogBuilder(this).setTitle("반응을 모두 지울까요?").setMessage("저장한 플레이리스트는 유지돼요.")
            .setPositiveButton("초기화",(a,b)->task(()->{if(api==null)local.clearFeedback();else api.request("DELETE","/me/feedback",null);return true;},done->{feedback.clear();cards.notifyDataSetChanged();},null)).setNegativeButton("취소",null).show());
        dialog.show();
    }

    private LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(16));return box;}
    private ScrollView scroll(View child){ScrollView view=new ScrollView(this);view.addView(child);return view;}
    private TextView text(String value,int size,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(getColor(bold?R.color.ink:R.color.muted));view.setPadding(0,dp(10),0,dp(10));if(bold)view.setTypeface(null,Typeface.BOLD);return view;}
    private MaterialButton button(String label,View.OnClickListener action){MaterialButton view=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);view.setText(label);view.setMinHeight(dp(52));view.setCornerRadius(dp(16));view.setOnClickListener(action);return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private <T> void task(Callable<T> operation,Consumer<T> success,Runnable retry){
        if(busy)return;setBusy(true);
        worker.execute(()->{
            try {T result=operation.call();runOnUiThread(()->{if(isDestroyed()||isFinishing())return;setBusy(false);success.accept(result);});}
            catch(Exception error){runOnUiThread(()->{if(isDestroyed()||isFinishing())return;setBusy(false);String message=error instanceof java.io.IOException?"네트워크에 연결하지 못했어요. 설정과 연결 상태를 확인해 주세요.":error.getMessage();showError(message==null?"처리하지 못했어요. 다시 시도해 주세요.":message,retry);});}
        });
    }
    private void clearResults(){
        picks=new ArrayList<>();findViewById(R.id.results).setVisibility(View.GONE);
        TextView empty=findViewById(R.id.empty);empty.setText(R.string.home_empty);empty.setVisibility(View.VISIBLE);cards.notifyDataSetChanged();
    }
    private void networkPermission(String base,Runnable action){
        if(android.os.Build.VERSION.SDK_INT>=37&&!base.isEmpty()&&ApiClient.localHost(java.net.URI.create(base).getHost())
                &&checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingConnection=action;localPermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        }else action.run();
    }
    private void setBusy(boolean value){
        busy=value;findViewById(R.id.progress).setVisibility(value?View.VISIBLE:View.INVISIBLE);
        findViewById(R.id.recommend).setEnabled(!value);findViewById(R.id.settings).setEnabled(!value);
        android.view.Menu menu=((BottomNavigationView)findViewById(R.id.navigation)).getMenu();
        for(int i=0;i<menu.size();i++)menu.getItem(i).setEnabled(!value);
        updateCounter();cards.notifyDataSetChanged();
    }
    private void showError(String message,Runnable retry){Snackbar bar=Snackbar.make(findViewById(R.id.main),message,Snackbar.LENGTH_LONG);if(retry!=null)bar.setAction("재시도",v->retry.run());bar.show();}

    private final class CardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.track_card,parent,false)){};}
        @Override public int getItemCount(){return picks.size();}
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position){
            Recommender.Pick pick=picks.get(position);Track track=pick.track;View view=holder.itemView;
            ((TextView)view.findViewById(R.id.track_title)).setText(track.title);((TextView)view.findViewById(R.id.track_artist)).setText(track.artist);
            ((TextView)view.findViewById(R.id.reason)).setText(pick.reason);
            List<String> labels=new ArrayList<>();for(String tag:track.tags)if(Recommender.LABELS.containsKey(tag)&&labels.size()<3)labels.add(Recommender.LABELS.get(tag));
            ((TextView)view.findViewById(R.id.track_tags)).setText(android.text.TextUtils.join("  ·  ",labels));
            int hash=Math.abs(track.id.hashCode()%4);int[][] colors={{0xFF7E6EB5,0xFFD4BFD7},{0xFF68868D,0xFFB9D2CC},{0xFFAD7E84,0xFFEACBB8},{0xFF647AA5,0xFFBCBFDD}};
            GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors[hash]);background.setCornerRadius(dp(18));view.findViewById(R.id.artwork).setBackground(background);
            MaterialButton like=view.findViewById(R.id.like),dislike=view.findViewById(R.id.dislike),save=view.findViewById(R.id.save);
            int reaction=feedback.getOrDefault(track.id,0);like.setText(reaction==1?"♥ 좋아요 취소":"♡ 좋아요");dislike.setText(reaction==-1?"싫어요 취소":"싫어요");
            like.setOnClickListener(v->react(track,reaction==1?0:1));dislike.setOnClickListener(v->react(track,reaction==-1?0:-1));
            save.setText(savedTracks.contains(track.id)?"저장됨 · 추가":"목록에 저장");save.setOnClickListener(v->chooseSave(track));
            view.findViewById(R.id.listen).setOnClickListener(v->listen(track));view.findViewById(R.id.external).setOnClickListener(v->external(track));
            view.findViewById(R.id.track_title).setOnClickListener(v->source(track));
            like.setEnabled(!busy);dislike.setEnabled(!busy);save.setEnabled(!busy);
        }
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state){
        super.onSaveInstanceState(state);JSONArray rows=new JSONArray();try{for(Recommender.Pick p:picks)rows.put(new JSONObject().put("track",TrackJson.write(p.track)).put("reason",p.reason));}catch(Exception ignored){}
        state.putString("picks",rows.toString());state.putInt("index",pager.getCurrentItem());state.putString("mode",resultMode);state.putString("notice",resultNotice);state.putBoolean("library",librarySelected);
    }
    @Override protected void onStop(){stopPlayer();super.onStop();}
    @Override protected void onDestroy(){stopPlayer();worker.execute(local::close);worker.shutdown();super.onDestroy();}
}
