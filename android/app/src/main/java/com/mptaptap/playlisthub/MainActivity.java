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
    private final Object taskOwner=new Object();
    private PendingTask pendingTask;
    private RecommendationRequest recommendation;
    private boolean librarySelected;
    private String playlistId;
    private String libraryQuery = "";
    private String resultMode = "basic";
    private String resultNotice = "";
    private boolean hasResults;
    private Recommender.Conditions resultConditions = new Recommender.Conditions();
    private Set<String> hiddenTracks = new HashSet<>();
    private String restoredPlaylistId;
    private int restoredLibraryScroll;
    private WebView player;
    private AlertDialog playerDialog;
    private final Set<AlertDialog> dialogs=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Runnable pendingConnection;
    private final ActivityResultLauncher<String> localPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
        Runnable action=pendingConnection;pendingConnection=null;
        if(granted&&action!=null)action.run();
        else {
            if(api!=null&&ApiClient.localHost(java.net.URI.create(api.base).getHost())){settings.edit().remove("server").apply();api=null;recommendation.discard();clearResults();refreshData(()->{});}
            dialogBuilder().setTitle("서버에 연결하지 않았어요")
                .setMessage("로컬 서버 연결에는 근처 기기 권한이 필요해요. 기본 추천은 계속 사용할 수 있어요.").setPositiveButton("확인",null).show();
        }
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); EdgeToEdge.enable(this); setContentView(R.layout.activity_main);
        recommendation=new ViewModelProvider(this).get(RecommendationRequest.class);
        pendingTask=new ViewModelProvider(this).get(PendingTask.class);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            return WindowInsetsCompat.CONSUMED;
        });
        local = new LocalStore(this); settings = getSharedPreferences("settings", MODE_PRIVATE);
        prompt = findViewById(R.id.prompt); prompt.setText(settings.getString("prompt", ""));
        if (state != null) {
            libraryQuery = state.getString("libraryQuery", "");
            restoredPlaylistId = state.getString("playlistId");
            restoredLibraryScroll = state.getInt("libraryScroll");
        }
        pager = findViewById(R.id.pager); cards = new CardAdapter(); pager.setAdapter(cards);
        findViewById(R.id.recommend_scroll).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> resizeCard());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int index) { stopPlayer(); updateCounter(); }
        });
        findViewById(R.id.recommend).setOnClickListener(v -> recommend());
        findViewById(R.id.edit_request).setOnClickListener(v -> {
            findViewById(R.id.request_panel).setVisibility(View.VISIBLE);
            findViewById(R.id.results).setVisibility(View.GONE); findViewById(R.id.empty).setVisibility(View.GONE);
            findViewById(R.id.card_navigation).setVisibility(View.GONE);
            ((androidx.core.widget.NestedScrollView)findViewById(R.id.recommend_scroll)).scrollTo(0, 0);
            prompt.requestFocus(); prompt.setSelection(prompt.length());
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(prompt, InputMethodManager.SHOW_IMPLICIT);
        });
        findViewById(R.id.save_results).setOnClickListener(v -> {
            List<Track> tracks=new ArrayList<>(); for (Recommender.Pick pick:picks) tracks.add(pick.track);
            chooseSaveAll(tracks);
        });
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.example_rain).setOnClickListener(v -> prompt.setText("비 오는 밤에 듣기 좋은 잔잔한 음악"));
        findViewById(R.id.example_study).setOnClickListener(v -> prompt.setText("공부할 때 집중할 수 있는 가사 없는 피아노 음악"));
        findViewById(R.id.example_workout).setOnClickListener(v -> prompt.setText("운동할 때 듣기 좋은 신나는 음악"));
        findViewById(R.id.previous).setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() - 1, true));
        findViewById(R.id.next).setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() + 1, true));
        ((BottomNavigationView)findViewById(R.id.navigation)).setOnItemSelectedListener(item -> {
            hideKeyboard();
            librarySelected = item.getItemId() == R.id.nav_library; stopPlayer();
            findViewById(R.id.recommend_page).setVisibility(librarySelected ? View.GONE : View.VISIBLE);
            findViewById(R.id.library_scroll).setVisibility(librarySelected ? View.VISIBLE : View.GONE);
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
            if (state != null && state.getBoolean("hasResults")) {
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
        String base = settings.getString("server", "");
        if (!base.isEmpty()) {
            try { api = new ApiClient(this, base); } catch (Exception e) { showError("저장된 서버 주소를 확인해 주세요.", null); }
        }
        recommendation.states().observe(this, pending->{
            updateBusy();
            if(pending.result==null&&pending.error==null)return;
            recommendation.consume(pending);
            if(!pending.base.equals(api==null?"":api.base))return;
            if(pending.error!=null)showError(errorMessage(pending.error),()->{if(pending.base.equals(api==null?"":api.base))recommend();});
            else showRecommendation(pending.result);
        });
        if(state!=null&&state.getBoolean("requestPending")&&!recommendation.current().loading
                &&recommendation.current().result==null&&recommendation.current().error==null)
            { String requestBase=api==null?"":api.base;
            findViewById(R.id.main).post(()->{if(!isDestroyed()&&!isFinishing()&&requestBase.equals(api==null?"":api.base))showError("진행하던 추천을 이어받지 못했어요. 요청을 확인하고 다시 추천받아 주세요.",()->{if(requestBase.equals(api==null?"":api.base))recommend();});}); }
        pendingTask.states().observe(this, pending->{
            updateBusy();
            if(pending==null||pending.loading||pending.owner==taskOwner)return;
            pendingTask.consume(pending);
            refreshAfterRecreation(state,pending.error);
        });
        if(pendingTask.current()==null)refreshAfterRecreation(state,null);
    }

    private void refreshAfterRecreation(Bundle state,Exception interruptedError) {
        String base=settings.getString("server","");
        if(!base.equals(api==null?"":api.base)) {
            try {api=base.isEmpty()?null:new ApiClient(this,base);clearResults();}
            catch(Exception error){showError("저장된 서버 주소를 확인해 주세요.",null);return;}
        }
        networkPermission(api==null?"":api.base,()->refreshData(()->{
            if(state!=null&&state.getBoolean("library"))((BottomNavigationView)findViewById(R.id.navigation)).setSelectedItemId(R.id.nav_library);
            if(interruptedError!=null)showError(errorMessage(interruptedError),null);
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
            updateMode(); cards.notifyDataSetChanged(); updateCounter(); success.run();
        }, () -> refreshData(success),true);
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
        if(busy)return;
        String request = prompt.getText().toString().trim();
        TextInputLayout field = findViewById(R.id.prompt_layout);
        try { Recommender.parse(request); field.setError(null); }
        catch (IllegalArgumentException e) { field.setError(e.getMessage()); return; }
        hideKeyboard(); stopPlayer(); settings.edit().putString("prompt", request).apply();
        recommendation.start(request,api,new ArrayList<>(catalogue),new HashMap<>(feedback));
    }

    private void showRecommendation(Object[] result) {
        picks = (List<Recommender.Pick>)result[0]; resultMode = (String)result[1]; resultNotice = (String)result[2];
        resultConditions = (Recommender.Conditions)result[3]; hiddenTracks.clear();
        showResults(); List<Recommender.Pick> shown=picks;
        findViewById(R.id.results).post(() -> {
            if(isDestroyed()||isFinishing()||picks!=shown)return;
            // View state restoration runs after onStart; a new result always begins with its first track.
            hideKeyboard();pager.setCurrentItem(0,false);
            ((androidx.core.widget.NestedScrollView)findViewById(R.id.recommend_scroll)).scrollTo(0,findViewById(R.id.results).getTop());
        });
    }

    private void showResults() {
        hasResults=true; findViewById(R.id.request_panel).setVisibility(View.GONE);
        findViewById(R.id.results).setVisibility(View.VISIBLE);
        findViewById(R.id.empty).setVisibility(picks.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView)findViewById(R.id.empty)).setText(hiddenTracks.isEmpty() ? "조건에 맞는 곡을 찾지 못했어요.\n장르나 언어 조건을 줄여 다시 추천받아 보세요." : "이번 추천에서 모든 곡을 제외했어요.\n요청을 수정해 새로 추천받을 수 있어요.");
        ((TextView)findViewById(R.id.notice)).setText((resultMode.equals("openai") ? "AI 추천" : "기본 추천") + (resultNotice.isEmpty() ? " · 한 곡씩 들어보세요." : "\n" + resultNotice)
            + (hiddenTracks.isEmpty() ? "" : "\n이번 결과만 수정했어요. 좋아요·싫어요는 그대로예요."));
        List<String> conditions=new ArrayList<>();
        for (String tag:resultConditions.tags) conditions.add(Recommender.LABELS.get(tag));
        for (String tag:resultConditions.excludedTags) conditions.add(Recommender.LABELS.get(tag)+" 제외");
        switch(resultConditions.language) {
            case "ko": conditions.add("한국어"); break;
            case "en": conditions.add("영어"); break;
            case "none": if(!resultConditions.tags.contains("instrumental"))conditions.add("연주곡"); break;
            case "unsupported": conditions.add("현재 없는 언어"); break;
        }
        ((TextView)findViewById(R.id.condition_summary)).setText("추천 조건 · "+(conditions.isEmpty() ? "다양한 음악" : android.text.TextUtils.join(" · ",conditions)));
        pager.setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.card_navigation).setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.save_results).setVisibility(picks.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.save_results).setEnabled(!busy && !picks.isEmpty());
        ((MaterialButton)findViewById(R.id.save_results)).setText("추천 "+picks.size()+"곡 모두 저장");
        cards.notifyDataSetChanged(); updateCounter(); findViewById(R.id.results).post(this::resizeCard);
    }

    private void resizeCard() {
        if (findViewById(R.id.results).getVisibility()!=View.VISIBLE || picks.isEmpty()) return;
        int available=findViewById(R.id.recommend_scroll).getHeight()-pager.getTop()-dp(16);
        int height=Math.max(dp(180), available);
        if (pager.getLayoutParams().height!=height) { pager.getLayoutParams().height=height;pager.requestLayout(); }
    }

    private void editResult(Track track) {
        if (busy) return;
        dialogBuilder().setTitle(track.title)
            .setMessage("이번 추천 결과만 바꿔요. 싫어요로 기록하지 않아요.")
            .setPositiveButton("같은 조건의 다른 곡", (d,w) -> changeResult(track, true))
            .setNeutralButton("이번 추천에서 제외", (d,w) -> changeResult(track, false))
            .setNegativeButton("취소", null).show();
    }

    private void changeResult(Track track, boolean replace) {
        if (busy) return;
        int index=-1; Set<String> unavailable=new HashSet<>(hiddenTracks);
        for (int i=0;i<picks.size();i++) {
            unavailable.add(picks.get(i).track.id);
            if (picks.get(i).track.id.equals(track.id)) index=i;
        }
        if (index<0) return;
        List<Recommender.Pick> before=picks, changed=new ArrayList<>(picks);
        Set<String> beforeHidden=new HashSet<>(hiddenTracks); int beforeIndex=pager.getCurrentItem();
        if (replace) {
            List<Recommender.Pick> alternatives=Recommender.recommend(catalogue, resultConditions, feedback, resultMode, unavailable).tracks;
            if (alternatives.isEmpty()) { showError("같은 조건의 다른 곡이 없어요. 요청의 조건을 줄여보세요.", null); return; }
            changed.set(index, alternatives.get(0));
        } else changed.remove(index);
        stopPlayer(); picks=changed; hiddenTracks.add(track.id); showResults();
        pager.setCurrentItem(Math.max(0, Math.min(beforeIndex, picks.size()-1)), false);
        snackbar(replace ? "같은 조건의 다른 곡으로 바꿨어요." : "이번 추천에서 제외했어요.", Snackbar.LENGTH_LONG)
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
        findViewById(R.id.previous).setEnabled(!busy && index > 1);
        findViewById(R.id.next).setEnabled(!busy && index > 0 && index < picks.size());
        findViewById(R.id.previous).setAlpha(!busy && index>1 ? 1f : 0.45f);
        findViewById(R.id.next).setAlpha(!busy && index>0 && index<picks.size() ? 1f : 0.45f);
        MaterialButton like=findViewById(R.id.like), dislike=findViewById(R.id.dislike);
        like.setEnabled(!busy && index>0);dislike.setEnabled(!busy && index>0);
        if (index>0 && index<=picks.size()) {
            Track track=picks.get(index-1).track;int reaction=feedback.getOrDefault(track.id,0);
            like.setText(reaction==1?"좋아요 취소":"♡ 좋아요");dislike.setText(reaction==-1?"싫어요 취소":"싫어요");
            like.setOnClickListener(v->react(track,reaction==1?0:1));dislike.setOnClickListener(v->react(track,reaction==-1?0:-1));
        }
    }

    private void updateMode() {
        ((TextView)findViewById(R.id.mode_label)).setText(api == null ? "기본 추천 · 반응과 목록은 이 기기에 저장돼요." : "온라인 추천 · 한 문장으로 음악을 찾아보세요.");
    }

    private void react(Track track, int reaction) {
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
        if (tracks.isEmpty()) return;
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
        EditText name = new EditText(this); name.setHint("예: 비 오는 날의 음악"); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setSingleLine(true); name.setText(currentTitle); name.setSelection(name.length());
        LinearLayout box = column(); box.addView(name);
        AlertDialog dialog = dialogBuilder().setTitle(id == null ? "새 플레이리스트" : "목록 이름 변경").setView(box).setPositiveButton(id == null ? "만들기" : "변경", null).setNegativeButton("취소", null).create();
        JSONObject[] createdPlaylist=new JSONObject[1];
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(busy)return;
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("이름을 입력해 주세요."); return; }
            name.setError(null);name.setEnabled(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            task(() -> {
                try {
                    if(id==null) {
                        if(createdPlaylist[0]==null)createdPlaylist[0]=api==null?local.create(title):api.object("POST","/playlists",new JSONObject().put("title",title));
                        if(firstTracks!=null)saveTracks(firstTracks,createdPlaylist[0].getString("id"));
                        return createdPlaylist[0];
                    }
                    if (api == null) local.rename(id, title); else api.request("PUT", "/playlists/"+id, new JSONObject().put("title", title));
                    return new JSONObject().put("id", id);
                } catch(Exception error) {
                    runOnUiThread(()->{if(!isDestroyed()&&dialog.isShowing()){
                        name.setError(errorMessage(error));name.setEnabled(createdPlaylist[0]==null);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
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
            box.addView(button("+ 새 플레이리스트", v -> editPlaylist(null, null, "")));
            box.addView(button("곡명·아티스트로 찾기", v -> searchCatalogue()));
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
            if (items.length() == 0) box.addView(text("아직 곡이 없어요. 추천 화면에서 음악을 담아보세요.", 16, false));
            for (int i=0;i<items.length();i++) {
                JSONObject row = items.optJSONObject(i);
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
            List<Track> tracks=new ArrayList<>(); JSONArray items=data.getJSONArray("items");
            for (int i=0;i<items.length();i++) tracks.add(TrackJson.read(items.getJSONObject(i).getJSONObject("track")));
            Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, data.getString("title"))
                .putExtra(Intent.EXTRA_TEXT, PlaylistText.format(data.getString("title"), tracks));
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
            rows.addView(button(row.optString("title") + "\n" + row.optInt("size") + "곡 · 앱 추천에서 저장", v -> openPlaylist(id)));
        }
        if (count==0) rows.addView(text(query.trim().isEmpty() ? "아직 저장한 목록이 없어요.\n추천 곡에서 ‘목록에 저장’을 눌러보세요." : "검색한 이름의 목록이 없어요.", 16, false));
    }

    private EditText searchField(String hint, Consumer<String> change) {
        EditText field=new EditText(this); field.setSingleLine(true); field.setHint(hint);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { change.accept(s.toString()); }
            public void afterTextChanged(Editable text) {}
        });
        return field;
    }

    private void searchCatalogue() {
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
            return savedIds();
        }, ids -> { savedTracks=ids; cards.notifyDataSetChanged(); playlistId=null; loadLibrary(); }, () -> deletePlaylist(id));
    }

    private void remove(String id, String item) {
        task(() -> { if (api == null) local.remove(id, item); else api.request("DELETE", "/playlists/"+id+"/items/"+item, null); return savedIds(); }, ids -> {
            savedTracks=ids; cards.notifyDataSetChanged(); openPlaylist(id);
        }, () -> remove(id,item));
    }

    private void external(Track track) {
        String q = Uri.encode(track.title + " " + track.artist);
        boolean direct = !track.youtubeVideoId.isEmpty();
        dialogBuilder().setTitle("어디에서 들을까요?")
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
        dialogBuilder().setTitle("곡 정보·출처")
            .setMessage(t.title+"\n"+t.artist+"\n\nMusicBrainz 핵심 메타데이터 · CC0\n분위기 태그: TapTap 직접 작성\n\n음원 파일은 저장하지 않아요.")
            .setPositiveButton("출처 열기",(d,w)->openUrl(t.sourceUrl)).setNegativeButton("닫기",null).show();
    }

    private void listen(Track track) {
        stopPlayer();
        if (track.youtubeVideoId.isEmpty()) {
            dialogBuilder().setTitle("외부에서 찾아볼까요?").setMessage("이 곡의 공식 영상 연결은 아직 확인하지 못했어요. 곡명·아티스트로 검색할 수 있어요.")
                .setPositiveButton("외부에서 검색",(d,w)->external(track)).setNegativeButton("닫기",null).show(); return;
        }
        LinearLayout box=column(); TextView status=text("YouTube 영상 연결 중…",14,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); box.addView(status);
        try {
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
            String html=ApiClient.read(getAssets().open("player.html")).replace("__VIDEO__",track.youtubeVideoId).replace("__ORIGIN__",origin);
            player.loadDataWithBaseURL(origin+"/",html,"text/html","UTF-8",null);
        } catch(Exception | LinkageError e){stopPlayer();status.setText("플레이어를 준비하지 못했어요. 외부에서 열어보세요.");}
        playerDialog=dialogBuilder().setTitle(track.title).setView(box)
            .setPositiveButton("외부에서 열기",(d,w)->external(track)).setNegativeButton("닫기",null).create();
        playerDialog.setOnDismissListener(d->{if(playerDialog==d)stopPlayer();}); playerDialog.show();
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

    private void showSettings(){
        LinearLayout box=column(); box.addView(text("나의 음악 설정",23,true));
        box.addView(text("추천에는 직접 입력한 요청과 앱 안의 좋아요·싫어요만 사용해요. 저장·외부 열기를 청취 기록으로 간주하지 않아요.",14,false));
        box.addView(button("좋아요·싫어요 관리 ("+feedback.size()+")",v->manageFeedback()));
        box.addView(text("AI 추천 서버 연결",18,true));
        box.addView(text(api==null?"기본 추천을 사용 중이에요. 목록은 이 기기에 보관돼요.":"서버에 연결된 내 반응과 목록을 사용해요.",14,false));
        EditText address=new EditText(this);address.setSingleLine(true);address.setHint("https://서버주소");address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(api==null?"":api.base);box.addView(address);
        AlertDialog dialog=dialogBuilder().setView(scroll(box)).setNegativeButton("닫기",null).create();
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
        box.addView(button("곡 데이터와 이용 안내",v->dialogBuilder().setTitle("추천 데이터")
            .setMessage("MusicBrainz 핵심 메타데이터(CC0)로 검증한 "+catalogue.size()+"곡과 직접 작성한 태그를 사용해요. 곡이 부족하면 조건을 줄여 주세요.\n\n외부 서비스에서 가져온 데이터는 추천에 사용하지 않아요. 영상 재생과 외부 연결은 네트워크·서비스·지역에 따라 달라질 수 있어요.")
            .setPositiveButton("출처·이용 조건",(d,w)->openUrl("https://musicbrainz.org/doc/About/Data_License")).setNegativeButton("닫기",null).show()));
        dialog.show();
    }

    private void manageFeedback(){
        List<Track> reacted=new ArrayList<>();for(Track t:catalogue)if(feedback.containsKey(t.id))reacted.add(t);
        String[] labels=new String[reacted.size()];for(int i=0;i<labels.length;i++){Track t=reacted.get(i);labels[i]=(feedback.get(t.id)==1?"좋아요 · ":"싫어요 · ")+t.title+" — "+t.artist;}
        MaterialAlertDialogBuilder dialog=dialogBuilder().setTitle("반응을 눌러 취소하세요").setNegativeButton("닫기",null);
        if(labels.length==0)dialog.setMessage("아직 남긴 반응이 없어요. 추천 카드에서 좋아요·싫어요를 선택할 수 있어요.");
        else dialog.setItems(labels,(d,w)->react(reacted.get(w),0)).setNeutralButton("전체 초기화",(d,w)->dialogBuilder().setTitle("반응을 모두 지울까요?").setMessage("저장한 플레이리스트는 유지돼요.")
            .setPositiveButton("초기화",(a,b)->task(()->{if(api==null)local.clearFeedback();else api.request("DELETE","/me/feedback",null);return true;},done->{feedback.clear();cards.notifyDataSetChanged();updateCounter();},null)).setNegativeButton("취소",null).show());
        dialog.show();
    }

    private MaterialAlertDialogBuilder dialogBuilder(){
        return new MaterialAlertDialogBuilder(this){
            @Override public AlertDialog create(){
                AlertDialog dialog=super.create();dialogs.add(dialog);return dialog;
            }
        };
    }

    private LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(16));return box;}
    private ScrollView scroll(View child){ScrollView view=new ScrollView(this);view.addView(child);return view;}
    private TextView text(String value,int size,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(getColor(bold?R.color.ink:R.color.muted));view.setPadding(0,dp(10),0,dp(10));if(bold)view.setTypeface(null,Typeface.BOLD);return view;}
    private MaterialButton button(String label,View.OnClickListener action){MaterialButton view=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);view.setText(label);view.setTextSize(14);view.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);view.setMinHeight(dp(52));view.setCornerRadius(dp(16));view.setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(R.color.line)));view.setTextColor(getColor(R.color.ink));view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.surface)));view.setOnClickListener(v->{if(!busy)action.onClick(v);});return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private <T> void task(Callable<T> operation,Consumer<T> success,Runnable retry){
        task(operation,success,retry,false);
    }
    private <T> void task(Callable<T> operation,Consumer<T> success,Runnable retry,boolean refreshing){
        if(pendingTask.current()!=null||busy&&!refreshing)return;
        ApiClient taskApi=api;PendingTask.State pending=pendingTask.begin(taskOwner);
        worker.execute(()->{
            try {T result=operation.call();runOnUiThread(()->{
                PendingTask.State completed=pendingTask.finish(pending,null);
                if(isDestroyed()||isFinishing())return;
                pendingTask.consume(completed);success.accept(result);
            });}
            catch(Exception error){runOnUiThread(()->{
                PendingTask.State completed=pendingTask.finish(pending,error);
                if(isDestroyed()||isFinishing())return;
                pendingTask.consume(completed);showError(errorMessage(error),retry==null?null:()->{if(api==taskApi)retry.run();});
            });}
        });
    }
    private void hideKeyboard(){
        View root=findViewById(R.id.main);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        root.requestFocus();
    }
    private String errorMessage(Exception error){String message=error instanceof java.io.IOException?"네트워크에 연결하지 못했어요. 설정과 연결 상태를 확인해 주세요.":error.getMessage();return message==null?"처리하지 못했어요. 다시 시도해 주세요.":message;}
    private void clearResults(){
        picks=new ArrayList<>();hiddenTracks.clear();hasResults=false;resultConditions=new Recommender.Conditions();restoredPlaylistId=null;restoredLibraryScroll=0;
        findViewById(R.id.request_panel).setVisibility(View.VISIBLE);
        findViewById(R.id.results).setVisibility(View.GONE);
        findViewById(R.id.card_navigation).setVisibility(View.GONE);
        TextView empty=findViewById(R.id.empty);empty.setText(R.string.home_empty);empty.setVisibility(View.VISIBLE);cards.notifyDataSetChanged();
    }
    private void networkPermission(String base,Runnable action){
        if(android.os.Build.VERSION.SDK_INT>=37&&!base.isEmpty()&&ApiClient.localHost(java.net.URI.create(base).getHost())
                &&checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingConnection=action;localPermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK);
        }else action.run();
    }
    private void updateBusy(){
        busy=pendingTask.current()!=null||recommendation.current().loading;findViewById(R.id.progress).setVisibility(busy?View.VISIBLE:View.INVISIBLE);
        findViewById(R.id.recommend).setEnabled(!busy);findViewById(R.id.settings).setEnabled(!busy);
        findViewById(R.id.save_results).setEnabled(!busy && !picks.isEmpty());
        findViewById(R.id.edit_request).setEnabled(!busy);
        android.view.Menu menu=((BottomNavigationView)findViewById(R.id.navigation)).getMenu();
        for(int i=0;i<menu.size();i++)menu.getItem(i).setEnabled(!busy);
        updateCounter();cards.notifyDataSetChanged();
    }
    private Snackbar snackbar(String message,int duration){
        View anchor=findViewById(R.id.card_navigation);
        if(!anchor.getGlobalVisibleRect(new android.graphics.Rect()))anchor=findViewById(R.id.navigation);
        Snackbar bar=Snackbar.make(findViewById(R.id.main),message,duration).setAnchorView(anchor);
        bar.setTextMaxLines(4);
        bar.setAnchorViewLayoutListenerEnabled(true);return bar;
    }
    private void showError(String message,Runnable retry){Snackbar bar=snackbar(message,Snackbar.LENGTH_LONG);if(retry!=null)bar.setAction("재시도",v->retry.run());bar.show();}

    private final class CardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.track_card,parent,false)){};}
        @Override public int getItemCount(){return picks.size();}
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position){
            Recommender.Pick pick=picks.get(position);Track track=pick.track;View view=holder.itemView;
            ((TextView)view.findViewById(R.id.track_title)).setText(track.title);((TextView)view.findViewById(R.id.track_artist)).setText(track.artist);
            view.findViewById(R.id.track_title).setContentDescription(track.title+" · 곡 정보와 출처 보기");
            ((TextView)view.findViewById(R.id.reason)).setText(pick.reason);
            List<String> labels=new ArrayList<>();for(String tag:track.tags)if(Recommender.LABELS.containsKey(tag)&&labels.size()<3)labels.add(Recommender.LABELS.get(tag));
            ((TextView)view.findViewById(R.id.track_tags)).setText(android.text.TextUtils.join("  ·  ",labels));
            int hash=Math.abs(track.id.hashCode()%4);int[][] colors={{0xFF33415C,0xFF7385A2},{0xFF256A65,0xFF71A69B},{0xFF9D515D,0xFFE5A48D},{0xFF475B73,0xFF9CABBF}};
            GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors[hash]);background.setCornerRadius(dp(18));view.findViewById(R.id.artwork).setBackground(background);
            MaterialButton save=view.findViewById(R.id.save);
            save.setText(savedTracks.contains(track.id)?"저장됨 · 추가":"목록에 저장");save.setOnClickListener(v->chooseSave(track));
            view.findViewById(R.id.listen).setOnClickListener(v->listen(track));view.findViewById(R.id.external).setOnClickListener(v->external(track));
            view.findViewById(R.id.track_title).setOnClickListener(v->source(track));
            view.findViewById(R.id.edit_result).setOnClickListener(v->editResult(track));view.findViewById(R.id.edit_result).setEnabled(!busy);
            save.setEnabled(!busy);
        }
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state){
        super.onSaveInstanceState(state);JSONArray rows=new JSONArray();try{for(Recommender.Pick p:picks)rows.put(new JSONObject().put("track",TrackJson.write(p.track)).put("reason",p.reason));}catch(Exception ignored){}
        state.putString("picks",rows.toString());state.putInt("index",pager.getCurrentItem());state.putString("mode",resultMode);state.putString("notice",resultNotice);state.putBoolean("library",librarySelected);
        state.putString("libraryQuery", libraryQuery);
        if(librarySelected){state.putString("playlistId",playlistId);state.putInt("libraryScroll",findViewById(R.id.library_scroll).getScrollY());}
        state.putBoolean("hasResults",hasResults);state.putBoolean("editingRequest",hasResults && findViewById(R.id.request_panel).getVisibility()==View.VISIBLE);
        state.putBoolean("requestPending",recommendation.current().loading);
        try { state.putString("conditions",TrackJson.conditions(resultConditions).toString()); } catch(Exception ignored){}
        state.putString("hiddenTracks",new JSONArray(hiddenTracks).toString());
    }
    @Override protected void onStop(){stopPlayer();super.onStop();}
    @Override protected void onDestroy(){
        stopPlayer();for(AlertDialog dialog:dialogs)dialog.dismiss();dialogs.clear();
        worker.execute(local::close);worker.shutdown();super.onDestroy();
    }
}
