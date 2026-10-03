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
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Account, explicit-source creation and community flows; no imported metadata reaches AI. */
public final class HubActivity extends AppCompatActivity {
    private static final String[] PROVIDERS={"spotify","youtube","apple_music"};
    private static final String[] PROVIDER_NAMES={"Spotify","YouTube Music","Apple Music"};
    private static final String[] SAVED_TEXT={"screen","importId","targetProvider","transferId","transferKey","saveId","sourceKey","importRequestId","conversionRequestId","publicationId","shareId","videoUrl","timeline","listUrl","listTitle","email","name","sourceProvider","query","sort","copyId","copyTitle","copyShare"};
    private HubRequest requests;
    private String base,mode;
    private LinearLayout content;
    private TextView status;
    private LinearProgressIndicator progress;
    private final List<View> controls=new ArrayList<>();
    private final java.util.Set<AlertDialog> dialogs=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int transferPolls;
    private final Runnable transferRefresh=()->{if(resumed&&!requests.busy()&&value("screen").equals("transfer")&&transferPolls++<20)send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null);};

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);EdgeToEdge.enable(this);
        requests=new ViewModelProvider(this).get(HubRequest.class);
        mode=getIntent().getStringExtra("mode");if(mode==null)mode="create";
        boolean callback=getIntent().getData()!=null;if(callback)mode="account";
        base=serverBase();
        boolean changed=requests.synchronize(this,base);
        if(requests.data.length()==0) {
            if(saved!=null&&!changed&&requests.fingerprint(this,base).equals(saved.getString("hubOwner",requests.fingerprint(this,base)))) {
                for(String key:SAVED_TEXT)if(saved.containsKey(key))put(key,saved.getString(key));
                put("favourites",saved.getBoolean("favourites"));
                restoreArray(saved,"edits");restoreArray(saved,"selections");
            }
            if(!requests.data.has("screen"))put("screen",mode);
            String playlist=getIntent().getStringExtra("playlistId");
            if(playlist!=null&&!playlist.isEmpty()){put("publicationId",playlist);put("screen","publication");}
        }
        if(callback){put("screen","account");put("authorizationPending",true);}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.background));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,insets.getInsets(WindowInsetsCompat.Type.ime()).bottom));return WindowInsetsCompat.CONSUMED;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(4),dp(12),dp(4));
        MaterialButton back=button("‹ 돌아가기",this::back);header.addView(back);
        TextView title=text(mode.equals("account")?"내 계정":mode.equals("community")?"음악 커뮤니티":"목록 만들기",20,true);
        header.addView(title,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));root.addView(header);
        progress=new LinearProgressIndicator(this);progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        NestedScrollView scroll=new NestedScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=column();content.setPadding(dp(20),dp(12),dp(20),dp(24));scroll.addView(content);
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
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getData()!=null){put("authorizationPending",true);refreshAuthorization();}}
    @Override protected void onResume(){super.onResume();resumed=true;base=serverBase();if(requests!=null&&requests.synchronize(this,base)){put("screen",mode);render();if(!requests.busy()&&!base.isEmpty())reload();}else if(requests!=null){refreshAuthorization();if(value("screen").equals("transfer"))scheduleTransferRefresh();}}
    private void refreshAuthorization(){if(resumed&&!base.isEmpty()&&requests.data.optBoolean("authorizationPending")&&!requests.busy()){put("authorizationPending",false);put("screen","account");send("account","GET","/accounts/me",null);}}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(transferRefresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(transferRefresh);for(AlertDialog dialog:dialogs)dialog.dismiss();dialogs.clear();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out) {
        requests.synchronize(this,base);out.putString("hubOwner",requests.fingerprint(this,base));
        for(String key:SAVED_TEXT)if(requests.data.has(key))out.putString(key,requests.data.optString(key));
        out.putBoolean("favourites",requests.data.optBoolean("favourites"));
        JSONArray edited=new JSONArray(),edits=requests.data.optJSONArray("edits");
        if(edits!=null)for(int i=0;i<edits.length();i++){JSONObject row=edits.optJSONObject(i);if(row!=null&&row.optBoolean("edited"))edited.put(row);}
        out.putString("edits",edited.toString());
        JSONArray selections=new JSONArray(),chosen=requests.data.optJSONArray("selections");
        if(chosen!=null)for(int i=0;i<chosen.length();i++){JSONObject row=chosen.optJSONObject(i);if(row!=null)selections.put(json("sourceIndex",row.optInt("sourceIndex"),"targetId",row.optString("targetId"),"excluded",row.optBoolean("excluded")));}
        out.putString("selections",selections.toString());
        super.onSaveInstanceState(out);
    }

    private void reload() {
        String screen=value("screen");
        if(screen.equals("account"))send("account","GET","/accounts/me",null);
        else if(screen.equals("history"))send("history","GET","/imports/playlists?summary=true",null);
        else if(screen.equals("community"))loadPublic();
        else if(screen.equals("publicDetail"))send("publicDetail","GET","/public/playlists/"+Uri.encode(value("shareId")),null);
        else if(screen.equals("publication"))send("publication","GET","/playlists/"+Uri.encode(value("publicationId"))+"/publication",null);
        else if(screen.equals("transfer")&&!value("transferId").isEmpty())send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null);
        else if((screen.equals("preview")||screen.equals("review")||screen.equals("sync"))&&!value("importId").isEmpty()) {
            if(screen.equals("review"))send("review","GET","/matches/"+Uri.encode(value("importId"))+"?targetProvider="+Uri.encode(value("targetProvider")),null);
            else send("import","GET","/imports/playlists/"+Uri.encode(value("importId")),null);
        }
    }
    private void back() {
        if(requests.busy()){message("처리가 끝날 때까지 잠시 기다려 주세요.");return;}
        if(value("screen").equals(mode)||value("screen").equals("publication")){finish();return;}
        put("screen",mode);render();reload();
    }
    private void render() {
        controls.clear();content.removeAllViews();status=text("",14,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);
        if(base.isEmpty()) {heading("서버 연결이 필요해요");paragraph("기존 추천 화면의 설정에서 서버에 연결하면 계정, 영상 변환, 음악 서비스 연동과 커뮤니티를 사용할 수 있어요.");addButton(content,"추천 화면으로 돌아가기",this::finish);return;}
        switch(value("screen")) {
            case "account":account();break;
            case "community":community();break;
            case "publicDetail":publicDetail();break;
            case "publication":publication();break;
            case "history":history();break;
            case "preview":preview();break;
            case "review":review();break;
            case "transfer":transfer();break;
            case "sync":sync();break;
            default:create();
        }
        busy(requests.busy());
    }

    private void create() {
        addButton(content,"이전 변환 · 가져오기 · 전송 기록",()->{put("screen","history");render();reload();});
        heading("영상에서 음악 목록 만들기");paragraph("영상 주소와 사용자가 가진 설명의 시간표를 확인해 곡을 나눠요. 저장 전에 제목과 아티스트를 직접 수정할 수 있어요.");
        EditText url=field(content,"YouTube 영상 주소","videoUrl",2048,false);
        EditText timeline=field(content,"영상 설명의 시간표 붙여넣기 (선택)","timeline",65536,true);
        addButton(content,"영상 시간표 확인",()->{
            if(url.getText().toString().trim().isEmpty()){url.setError("영상 주소를 입력해 주세요.");return;}
            beginSource("youtube:"+url.getText().toString().trim()+":"+timeline.getText(),"conversionRequestId");
            JSONObject body=json("id",value("conversionRequestId"),"url",url.getText().toString().trim());
            if(!timeline.getText().toString().trim().isEmpty())set(body,"timeline",timeline.getText().toString());
            put("edits",new JSONArray());put("selections",new JSONArray());send("conversion","POST","/conversions/youtube",body);
        });
        heading("음악 서비스 목록 가져오기");providerPicker(content,"sourceProvider");
        field(content,"플레이리스트 주소","listUrl",2048,false);field(content,"새 목록 이름","listTitle",80,false);
        addButton(content,"플레이리스트 가져오기",()->{
            if(value("listUrl").trim().isEmpty()){message("가져올 플레이리스트 주소를 입력해 주세요.");return;}
            beginSource(provider("sourceProvider")+":"+value("listUrl").trim()+":"+value("listTitle").trim(),"importRequestId");
            String id=value("importRequestId");
            put("edits",new JSONArray());put("selections",new JSONArray());
            send("import","POST","/imports/playlists",json("id",id,"provider",provider("sourceProvider"),"url",value("listUrl").trim(),"title",value("listTitle").trim()));
        });
        addButton(content,"계정과 음악 서비스 연결",()->{put("screen","account");render();reload();});
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

    private void account() {
        JSONObject account=requests.data.optJSONObject("account"),profile=account==null?null:account.optJSONObject("profile");
        if(profile==null) {
            heading("로그인 또는 회원가입");paragraph("계정에 로그인하면 같은 음악 목록을 이어서 관리할 수 있어요.");
            EditText email=field(content,"이메일","email",254,false);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            EditText password=secret(content,"비밀번호 (15자 이상)");field(content,"이름 (회원가입)","name",80,false);
            addButton(content,"로그인",()->authenticate(false,email,password));addButton(content,"회원가입",()->authenticate(true,email,password));
        } else {
            heading(profile.optString("name","내 계정"));paragraph(profile.optString("email"));
            addButton(content,"로그아웃",()->send("signedOut","DELETE","/accounts/session",null));
            addButton(content,"계정 삭제",()->{
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
            if(connected)addButton(row,"연결 해제",()->send("disconnected","DELETE","/connections/"+provider,null));
            else if(enabled)addButton(row,"브라우저에서 연결",()->send("auth","POST","/connections/"+provider+"/start",null));
        }
        addButton(content,"연결 상태 새로고침",()->send("account","GET","/accounts/me",null));
    }
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
        JSONArray entries=previewEntries(imported);
        for(int i=0;i<entries.length();i++) {
            JSONObject row=entries.optJSONObject(i);if(row==null)continue;int index=row.optInt("sourceIndex",i);
            JSONObject track=row.optJSONObject("track"),edit=edit(index,track==null?row:track);
            boolean timeline=imported.has("entries");LinearLayout box=card(content);
            box.addView(text((index+1)+". "+(timeline?row.optString("timestamp"):"가져온 곡"),15,true));
            if(timeline&&!row.optString("sourceRow").isEmpty())addButton(box,"원문과 시간 보기",()->dialog().setTitle("영상 설명 원문").setMessage(row.optString("sourceRow")).setPositiveButton("닫기",null).show());
            if(row.optBoolean("needsReview"))box.addView(text("곡명과 아티스트를 확인해 주세요.",13,false));
            CheckBox include=new CheckBox(this);include.setText("이 곡 포함");include.setMinHeight(dp(48));
            if(timeline&&row.optInt("seconds",row.optInt("startSeconds",-1))<0&&!edit.has("excluded"))set(edit,"excluded",true);
            include.setChecked(!edit.optBoolean("excluded"));include.setOnCheckedChangeListener((v,checked)->{set(edit,"excluded",!checked);set(edit,"edited",true);});controls.add(include);box.addView(include);
            rowField(box,"곡명",edit,"title");rowField(box,"아티스트",edit,"artist");
            if(timeline){if(!edit.has("seconds"))set(edit,"seconds",row.optInt("seconds",row.optInt("startSeconds",-1)));EditText time=rowField(box,"시작 시간 (초)",edit,"seconds");time.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);}
            String sourceUrl=row.optString("sourceUrl",track==null?"":track.optString("sourceUrl"));
            if(timeline)addButton(box,"원본 영상의 이 시간 열기",()->{
                try{String video=imported.optString("videoId");if(!video.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("영상 주소를 확인해 주세요.");open("https://www.youtube.com/watch?v="+video+"&t="+timelineSeconds(imported,edit)+"s");}
                catch(IllegalArgumentException error){message(error.getMessage());}
            });
            else if(!sourceUrl.isEmpty())addButton(box,"원본 곡 열기",()->open(sourceUrl));
        }
        addButton(content,"선택한 곡을 앱 목록에 저장",this::saveImport);
        heading("다른 플랫폼으로 옮기기");providerPicker(content,"targetProvider");
        paragraph("수정하고 선택한 곡을 앱 목록에 저장한 뒤 대상 서비스에서 같은 곡을 찾아요.");
        addButton(content,"대상 플랫폼의 곡 후보 찾기",()->saveImport(true));
        if(imported.has("reviews")&&!imported.optString("targetProvider").isEmpty())addButton(content,"저장된 곡 후보 검토 이어보기",()->{put("targetProvider",imported.optString("targetProvider"));send("review","GET","/matches/"+Uri.encode(value("importId"))+"?targetProvider="+Uri.encode(value("targetProvider")),null);});
        if(!imported.has("entries"))addButton(content,"목록 변경 미리보기",()->send("sync","POST","/sync/"+Uri.encode(value("importId"))+"/preview",null));
    }
    private void saveImport(){saveImport(false);}
    private void saveImport(boolean thenMatch) {
        String title=value("listTitle").trim();if(title.isEmpty()){message("새 목록 이름을 입력해 주세요.");return;}
        JSONObject imported=requests.data.optJSONObject("imported");JSONArray entries=previewEntries(imported),edits=new JSONArray();int included=0;
        for(int i=0;i<entries.length();i++) {
            JSONObject row=entries.optJSONObject(i);if(row==null)continue;JSONObject track=row.optJSONObject("track"),edit=edit(row.optInt("sourceIndex",i),track==null?row:track);
            if(!edit.optBoolean("excluded")) {
                if(edit.optString("title").trim().isEmpty()||edit.optString("artist").trim().isEmpty()){message("포함할 곡의 곡명과 아티스트를 입력하거나 곡을 제외해 주세요.");return;}
                if(imported.has("entries"))try{set(edit,"seconds",timelineSeconds(imported,edit));}catch(IllegalArgumentException error){message(error.getMessage());return;}
                included++;
            }
            JSONObject checked=json("sourceIndex",edit.optInt("sourceIndex"),"title",edit.optString("title"),"artist",edit.optString("artist"),"excluded",edit.optBoolean("excluded"));
            if(imported.has("entries")&&!edit.optBoolean("excluded"))set(checked,"seconds",edit.optInt("seconds"));
            edits.put(checked);
        }
        if(included==0){message("저장할 곡을 한 곡 이상 포함해 주세요.");return;}
        if(value("saveId").isEmpty())put("saveId",UUID.randomUUID().toString());
        send(thenMatch?"savedForMatch":"saved","POST","/imports/playlists/"+Uri.encode(value("importId"))+"/save",json("title",title,"playlistId",value("saveId"),"entries",edits));
    }
    private int timelineSeconds(JSONObject imported,JSONObject edit) {
        int seconds;try{seconds=Integer.parseInt(edit.optString("seconds"));}catch(NumberFormatException error){throw new IllegalArgumentException("시작 시간을 초 단위로 입력하거나 곡을 제외해 주세요.");}
        int duration=imported.optInt("durationSeconds",-1);if(seconds<0||seconds>(duration>=0?duration:21600))throw new IllegalArgumentException("시작 시간이 영상 범위를 벗어났어요. 시간을 수정하거나 곡을 제외해 주세요.");return seconds;
    }

    private void review() {
        JSONObject review=requests.data.optJSONObject("review");if(review==null){paragraph("대상 서비스의 곡 후보를 확인하고 있어요.");return;}
        heading("옮길 곡 확인");paragraph("각 곡의 후보를 선택하거나 제외해 주세요. 라이브·리믹스·커버 등 버전이 다르면 직접 확인해야 해요.");
        JSONArray items=review.optJSONArray("items");if(items==null)items=new JSONArray();
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;int index=item.optInt("sourceIndex",i);JSONObject source=item.optJSONObject("source");LinearLayout box=card(content);
            box.addView(text((index+1)+". "+song(source),16,true));JSONObject chosen=find(requests.data.optJSONArray("selections"),"sourceIndex",index);
            box.addView(text(chosen==null?"선택이 필요해요":chosen.optBoolean("excluded")?"전송에서 제외됨":"선택한 곡: "+selectionLabel(item,chosen),13,false));
            addButton(box,"후보 선택 또는 제외",()->chooseCandidate(item,index));
        }
        if(review.optInt("nextOffset",-1)>=0)addButton(content,"다음 20곡의 후보 찾기",()->send("review","POST","/matches",json("importId",value("importId"),"targetProvider",provider("targetProvider"),"offset",review.optInt("nextOffset"))));
        addButton(content,"일치한 후보를 한 번에 선택",()->{
            dialog().setTitle("일치한 후보를 선택할까요?").setMessage("같은 곡으로 확인된 후보를 선택해요. 나머지 곡은 직접 선택하거나 제외해야 해요.")
                    .setPositiveButton("선택",(d,w)->{JSONArray rows=review.optJSONArray("items");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i),selected=row==null?null:row.optJSONObject("selected");if(row!=null&&row.optString("status").equals("MATCHED")&&selected!=null)select(row.optInt("sourceIndex",i),selected,false);}render();}).setNegativeButton("취소",null).show();
        });
        field(content,"새 목록 이름","listTitle",80,false);addButton(content,"선택 확인 후 플레이리스트 보내기",this::approveTransfer);
    }
    private void chooseCandidate(JSONObject item,int index) {
        JSONArray candidates=item.optJSONArray("candidates");if(candidates==null)candidates=new JSONArray();
        String[] labels=new String[candidates.length()+1];
        for(int i=0;i<candidates.length();i++){JSONObject candidate=candidates.optJSONObject(i);JSONObject track=candidate==null?null:candidate.optJSONObject("track");labels[i]=song(track)+(track==null||track.optString("album").isEmpty()?"":" · "+track.optString("album"));}
        labels[candidates.length()]="이 곡은 전송에서 제외";JSONArray choices=candidates;
        dialog().setTitle("곡과 버전을 확인해 주세요").setItems(labels,(d,which)->{
            if(which==choices.length())select(index,null,true);else {JSONObject candidate=choices.optJSONObject(which);select(index,candidate==null?null:candidate.optJSONObject("track"),false);}render();
        }).setNegativeButton("취소",null).show();
    }
    private void select(int index,JSONObject track,boolean excluded) {
        if(!excluded&&(track==null||track.optString("externalId").isEmpty())){message("선택할 수 있는 곡을 확인하지 못했어요.");return;}
        JSONArray selections=array("selections");JSONObject selection=find(selections,"sourceIndex",index);
        if(selection==null){selection=new JSONObject();set(selection,"sourceIndex",index);selections.put(selection);}
        set(selection,"targetId",excluded?"":track.optString("externalId"));set(selection,"excluded",excluded);set(selection,"label",excluded?"":song(track));
    }
    private void approveTransfer() {
        JSONObject review=requests.data.optJSONObject("review");JSONArray items=review==null?null:review.optJSONArray("items"),selections=array("selections");int included=0;
        if(items==null||selections.length()!=items.length()){message("모든 곡에 대해 후보를 선택하거나 제외해 주세요.");return;}
        JSONArray confirmed=new JSONArray();
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i),selection=item==null?null:find(selections,"sourceIndex",item.optInt("sourceIndex",i));if(selection==null){message("모든 곡에 대해 후보를 선택하거나 제외해 주세요.");return;}if(!selection.optBoolean("excluded"))included++;confirmed.put(json("sourceIndex",selection.optInt("sourceIndex"),"targetId",selection.optString("targetId"),"excluded",selection.optBoolean("excluded")));}
        if(included==0||value("listTitle").trim().isEmpty()){message("보낼 곡과 새 목록 이름을 확인해 주세요.");return;}
        send("reviewSaved","PUT","/matches/"+Uri.encode(value("importId")),json("targetProvider",provider("targetProvider"),"selections",confirmed));
    }
    private void transfer() {
        JSONObject transfer=requests.data.optJSONObject("transfer");heading("플레이리스트 전송 결과");
        if(transfer==null){paragraph("전송 기록을 확인하고 있어요.");addButton(content,"전송 상태 확인",()->send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null));return;}
        JSONArray items=transfer.optJSONArray("items");int failed=0,success=0,pending=0,unknown=0;
        if(items!=null)for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;String state=item.optString("status");
            if(state.equals("ADDED"))success++;else if(state.equals("FAILED"))failed++;else if(state.equals("PENDING"))pending++;else if(state.equals("UNKNOWN"))unknown++;
            paragraph((item.optInt("sourceIndex",i)+1)+"번째 곡 · "+(state.equals("FAILED")?"실패 · 다시 시도할 수 있어요":state.equals("ADDED")?"전송 완료":state.equals("PENDING")?"전송 대기":"전송 결과 확인 필요"));
        }
        JSONArray excluded=transfer.optJSONArray("excludedSourceIndices");int excludedCount=excluded==null?0:excluded.length();
        if(excluded!=null)for(int i=0;i<excluded.length();i++)paragraph((excluded.optInt(i)+1)+"번째 곡 · 사용자가 제외");
        paragraph("완료 "+success+"곡 · 실패 "+failed+"곡 · 제외 "+excludedCount+"곡 · 대기 "+pending+"곡 · 확인 필요 "+unknown+"곡");
        if(!transfer.optString("notice").isEmpty())paragraph(transfer.optString("notice"));
        String playlistUrl=transfer.optString("playlistUrl");Uri playlist=Uri.parse(playlistUrl);String provider=transfer.optString("provider");
        String host=provider.equals("spotify")?"open.spotify.com":provider.equals("youtube")?"www.youtube.com":provider.equals("apple_music")?"music.apple.com":"";
        if(!host.isEmpty()&&"https".equals(playlist.getScheme())&&host.equals(playlist.getHost())&&playlist.getUserInfo()==null&&(playlist.getPort()==-1||playlist.getPort()==443))addButton(content,"생성된 목록 열기",()->open(playlistUrl));
        else if(provider.equals("apple_music")&&!transfer.optString("externalPlaylistId").isEmpty())paragraph("Apple Music 보관함에서 ‘"+transfer.optString("title")+"’ 목록을 확인해 주세요.");
        if(!transfer.optString("status").equals("RUNNING")&&!transfer.optString("status").equals("PENDING")&&(failed+pending+unknown)>0)addButton(content,"남은 곡 이어서 보내기",()->dialog().setTitle("남은 곡을 보낼까요?").setMessage("완료한 곡은 건너뛰고 대기·실패한 곡을 보내요. 결과가 불확실한 곡은 먼저 서비스에서 확인해요.").setPositiveButton("계속 보내기",(d,w)->{transferPolls=0;send("transfer","POST","/transfers/"+Uri.encode(value("transferId"))+"/retry",json("confirmed",true));}).setNegativeButton("취소",null).show());
        addButton(content,"전송 상태 새로고침",()->{transferPolls=0;send("transfer","GET","/transfers/"+Uri.encode(value("transferId")),null);});
    }
    private void sync() {
        JSONObject preview=requests.data.optJSONObject("sync");heading("원본 목록의 변경 내용");if(preview==null){paragraph("변경 내용을 확인하고 있어요.");return;}
        for(String key:new String[]{"added","removed","changed"}) {
            Object change=preview.opt(key);JSONArray rows=change instanceof JSONArray?(JSONArray)change:null;
            paragraph((key.equals("added")?"추가":key.equals("removed")?"삭제":"정보 변경")+" · "+(rows==null?preview.optInt(key):rows.length())+"곡");
            if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)paragraph(song(row.optJSONObject("track")!=null?row.optJSONObject("track"):row));}
        }
        JSONObject after=preview.optJSONObject("after");JSONArray afterItems=after==null?null:after.optJSONArray("items");
        if(afterItems!=null){heading("반영 후 곡 목록");for(int i=0;i<afterItems.length();i++){JSONObject row=afterItems.optJSONObject(i);if(row!=null)paragraph((i+1)+". "+song(row.optJSONObject("track")));}}
        addButton(content,"이 변경 내용을 반영",()->dialog().setTitle("가져온 목록에 반영할까요?").setMessage("위에서 확인한 추가·삭제·정보 변경을 반영해요.").setPositiveButton("반영",(d,w)->send("syncApplied","POST","/sync/"+Uri.encode(value("importId"))+"/apply",json("confirmed",true,"previewId",preview.optString("previewId")))).setNegativeButton("취소",null).show());
    }

    private void community() {
        heading("다른 사람의 음악 목록");field(content,"공개 목록 검색","query",200,false);
        addButton(content,"검색",this::loadPublic);addButton(content,"인기순",()->{put("sort","popular");loadPublic();});addButton(content,"최신순",()->{put("sort","recent");loadPublic();});
        CheckBox favourites=new CheckBox(this);favourites.setText("즐겨찾기한 목록만");favourites.setMinHeight(dp(48));favourites.setChecked(requests.data.optBoolean("favourites"));
        favourites.setOnCheckedChangeListener((v,checked)->{put("favourites",checked);loadPublic();});controls.add(favourites);content.addView(favourites);
        JSONArray rows=requests.data.optJSONArray("publicRows");if(rows==null)return;
        if(rows.length()==0)paragraph("조건에 맞는 공개 목록이 없어요.");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;LinearLayout box=card(content);box.addView(text(row.optString("title"),17,true));
            box.addView(text(row.optString("author")+" · "+row.optInt("size")+"곡 · 즐겨찾기 "+row.optInt("favourites"),13,false));
            addButton(box,"목록 열기",()->{put("shareId",row.optString("shareId"));send("publicDetail","GET","/public/playlists/"+Uri.encode(row.optString("shareId")),null);});
        }
    }
    private void loadPublic(){send("community","GET","/public/playlists?q="+Uri.encode(value("query"))+"&sort="+(value("sort").equals("recent")?"recent":"popular")+"&favourites="+requests.data.optBoolean("favourites"),null);}
    private void publicDetail() {
        JSONObject detail=requests.data.optJSONObject("publicDetail");if(detail==null){paragraph("공개 목록을 확인하고 있어요.");return;}
        heading(detail.optString("title"));paragraph(detail.optString("author")+" · "+detail.optInt("size")+"곡 · 즐겨찾기 "+detail.optInt("favourites"));
        addButton(content,detail.optBoolean("favourited")?"즐겨찾기 해제":"즐겨찾기",()->send("favourite","PUT","/public/playlists/"+Uri.encode(value("shareId"))+"/favourite",json("favourite",!detail.optBoolean("favourited"))));
        addButton(content,"내 목록으로 복사",()->{
            LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);EditText title=new AppCompatEditText(this);title.setHint("복사할 목록 이름");title.setText(detail.optString("title"));title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});box.addView(title);
            dialog().setTitle("내 목록으로 복사").setView(box).setPositiveButton("복사",(d,w)->{
                String name=title.getText().toString().trim();if(name.isEmpty()){message("목록 이름을 입력해 주세요.");return;}
                if(!name.equals(value("copyTitle"))||!value("shareId").equals(value("copyShare"))||value("copyId").isEmpty()){put("copyId",UUID.randomUUID().toString());put("copyTitle",name);put("copyShare",value("shareId"));}
                send("copied","POST","/public/playlists/"+Uri.encode(value("shareId"))+"/copy",json("id",value("copyId"),"title",name));
            }).setNegativeButton("취소",null).show();
        });
        addButton(content,"공개 목록 링크 공유",()->share(base+"/share/"+Uri.encode(value("shareId"))));
        JSONArray items=detail.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i),track=item==null?null:item.optJSONObject("track");paragraph((i+1)+". "+song(track));}
    }
    private void publication() {
        JSONObject publication=requests.data.optJSONObject("publication");heading("목록 공개 설정");if(publication==null){paragraph("공개 설정을 확인하고 있어요.");return;}
        boolean published=publication.optBoolean("public");paragraph(published?"공개 링크로 이 목록을 볼 수 있어요.":"현재 나만 볼 수 있는 비공개 목록이에요.");
        addButton(content,published?"비공개로 전환":"커뮤니티에 공개",()->{
            if(published)send("publication","PUT","/playlists/"+Uri.encode(value("publicationId"))+"/publication",json("public",false));
            else dialog().setTitle("이 목록을 공개할까요?").setMessage("목록 이름, 작성자와 곡 정보가 공개 목록과 공유 링크에 표시돼요.").setPositiveButton("공개",(d,w)->send("publication","PUT","/playlists/"+Uri.encode(value("publicationId"))+"/publication",json("public",true))).setNegativeButton("취소",null).show();
        });
        if(published&&publication.optString("sharePath").startsWith("/share/")){String url=base+publication.optString("sharePath");addButton(content,"공개 링크 열기",()->open(url));addButton(content,"공개 링크 공유",()->share(url));}
    }

    private void delivered(String route,JSONObject value) {
        switch(route) {
            case "account":put("account",value);put("screen","account");break;
            case "signedIn":case "signedOut":clearSource();requests.data.remove("sourceKey");requests.data.remove("history");put("account",new JSONObject());put("screen","account");render();send("account","GET","/accounts/me",null);return;
            case "history":put("history",value);put("screen","history");break;
            case "historyDeleted":
                if(value("deletingKind").equals("import")&&value("deletingId").equals(value("importId"))){clearSource();requests.data.remove("sourceKey");}
                else if(value("deletingKind").equals("transfer")&&value("deletingId").equals(value("transferId"))){requests.data.remove("transferId");requests.data.remove("transferKey");requests.data.remove("transfer");}
                requests.data.remove("deletingKind");requests.data.remove("deletingId");requests.data.remove("history");put("screen","history");render();reload();return;
            case "auth":put("authorizationPending",true);open(value.optString("authorizationUrl"));message("브라우저에서 연결을 마친 뒤 돌아와 주세요.");return;
            case "disconnected":
                if(value.optBoolean("providerRevocationRequired")&&!value.optString("manageAccessUrl").isEmpty())dialog().setTitle("서비스 권한도 확인해 주세요").setMessage("TapTap 연결은 해제했어요. 음악 서비스의 연결 앱 설정에서도 권한을 해제할 수 있어요.").setPositiveButton("권한 설정 열기",(d,w)->open(value.optString("manageAccessUrl"))).setNegativeButton("닫기",null).show();
                send("account","GET","/accounts/me",null);return;
            case "conversion":case "import":
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
            case "savedForMatch":
                put("saveId",value.optString("id"));setResult(RESULT_OK,new Intent().putExtra("playlistId",value.optString("id")));put("selections",new JSONArray());send("review","POST","/matches",json("importId",value("importId"),"targetProvider",provider("targetProvider")));return;
            case "reviewSaved":
                put("review",value);dialog().setTitle("확인한 곡을 전송할까요?").setMessage("선택한 곡으로 "+providerName(provider("targetProvider"))+"에 ‘"+value("listTitle").trim()+"’ 목록을 만들어요. 제외한 곡은 보내지 않아요.")
                    .setPositiveButton("전송",(d,w)->{String key=transferKey();if(!key.equals(value("transferKey"))||value("transferId").isEmpty()){put("transferKey",key);put("transferId",UUID.randomUUID().toString());}transferPolls=0;put("screen","transfer");render();send("transfer","POST","/transfers",json("id",value("transferId"),"importId",value("importId"),"targetProvider",provider("targetProvider"),"title",value("listTitle").trim(),"confirmed",true));}).setNegativeButton("취소",null).show();return;
            case "transfer":put("transfer",value);put("screen","transfer");scheduleTransferRefresh();break;
            case "sync":put("sync",value);put("screen","sync");break;
            case "syncApplied":put("imported",value);put("screen","preview");put("edits",new JSONArray());break;
            case "community":put("publicRows",value.optJSONArray("rows"));put("screen","community");break;
            case "publicDetail":put("publicDetail",value);put("screen","publicDetail");break;
            case "favourite":JSONObject detail=requests.data.optJSONObject("publicDetail");if(detail!=null){set(detail,"favourited",value.optBoolean("favourite"));set(detail,"favourites",value.optInt("favourites"));}break;
            case "publication":put("publication",value);put("screen","publication");break;
            case "saved":case "copied":
                if(route.equals("saved"))put("saveId",value.optString("id"));
                setResult(RESULT_OK,new Intent().putExtra("playlistId",value.optString("id")));dialog().setTitle("내 목록에 저장했어요").setMessage("저장한 음악은 추천 화면의 내 목록에서 확인할 수 있어요.").setPositiveButton("내 목록으로",(d,w)->finish()).setNegativeButton("계속 보기",null).show();message("내 목록에 저장했어요.");return;
        }
        render();
    }
    private void failure(String route,Exception error) {
        if(route.equals("identity")){put("screen",mode);render();message(error.getMessage());addButton(content,"현재 계정으로 다시 확인",this::reload);return;}
        if(route.equals("historyDeleted")&&error instanceof ApiClient.ApiError&&((ApiClient.ApiError)error).statusCode==404){delivered(route,new JSONObject());return;}
        String message=error instanceof ApiClient.ApiError?error.getMessage():"서버와 연결하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.";
        message(message);
        if(route.equals("account")){paragraph("계정 또는 음악 서비스 정보를 확인하지 못했어요.");}
        addButton(content,"같은 요청 다시 시도",()->requests.retry(getApplicationContext(),base));
    }
    private void beginSource(String source,String idKey){String key=UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();if(!key.equals(value("sourceKey"))){clearSource();put("sourceKey",key);}if(value(idKey).isEmpty())put(idKey,UUID.randomUUID().toString());}
    private void clearSource(){for(String key:new String[]{"importId","imported","importRequestId","conversionRequestId","saveId","transferId","transferKey","transfer","review","sync"})requests.data.remove(key);put("edits",new JSONArray());put("selections",new JSONArray());}
    private String transferKey(){JSONArray sorted=new JSONArray();JSONObject review=requests.data.optJSONObject("review");JSONArray items=review==null?null:review.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);JSONObject selected=item==null?null:find(array("selections"),"sourceIndex",item.optInt("sourceIndex",i));if(selected!=null)sorted.put(json("sourceIndex",selected.optInt("sourceIndex"),"targetId",selected.optString("targetId"),"excluded",selected.optBoolean("excluded")));}return UUID.nameUUIDFromBytes((value("importId")+":"+provider("targetProvider")+":"+value("listTitle").trim()+":"+sorted).getBytes(StandardCharsets.UTF_8)).toString();}
    private void restoreApprovals(JSONObject review){JSONArray items=review.optJSONArray("items");if(items==null)return;for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null||!item.optBoolean("approved")||find(array("selections"),"sourceIndex",item.optInt("sourceIndex",i))!=null)continue;boolean excluded=item.optString("status").equals("USER_EXCLUDED");if(excluded||item.optJSONObject("selected")!=null)select(item.optInt("sourceIndex",i),item.optJSONObject("selected"),excluded);}}
    private String selectionLabel(JSONObject item,JSONObject selection){if(!selection.optString("label").isEmpty())return selection.optString("label");JSONArray candidates=item.optJSONArray("candidates");if(candidates!=null)for(int i=0;i<candidates.length();i++){JSONObject candidate=candidates.optJSONObject(i),track=candidate==null?null:candidate.optJSONObject("track");if(track!=null&&track.optString("externalId").equals(selection.optString("targetId")))return song(track);}JSONObject selected=item.optJSONObject("selected");return selected!=null&&selected.optString("externalId").equals(selection.optString("targetId"))?song(selected):"곡 정보 확인 필요";}
    private void scheduleTransferRefresh(){handler.removeCallbacks(transferRefresh);JSONObject transfer=requests.data.optJSONObject("transfer");if(resumed&&transfer!=null&&(transfer.optString("status").equals("RUNNING")||transfer.optString("status").equals("PENDING"))&&transferPolls<20)handler.postDelayed(transferRefresh,2000);}
    private void send(String route,String method,String path,JSONObject body){if(base.isEmpty()){message("추천 화면의 설정에서 서버에 연결해 주세요.");return;}requests.send(getApplicationContext(),base,route,method,path,body);}
    private void busy(boolean loading){if(progress==null)return;progress.setVisibility(loading?View.VISIBLE:View.GONE);for(View control:controls)control.setEnabled(!loading);if(loading&&status!=null)status.setText("처리 중이에요…");else if(status!=null&&status.getText().toString().equals("처리 중이에요…"))status.setText("");}
    private void message(String message){if(status!=null)status.setText(message==null?"처리하지 못했어요.":message);}

    private LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private LinearLayout card(LinearLayout parent){MaterialCardView card=new MaterialCardView(this);card.setRadius(dp(16));card.setCardBackgroundColor(getColor(R.color.surface));card.setStrokeWidth(dp(1));card.setStrokeColor(getColor(R.color.line));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(12),0,dp(8));parent.addView(card,params);LinearLayout box=column();box.setPadding(dp(16),dp(12),dp(16),dp(12));card.addView(box);return box;}
    private TextView text(String label,int size,boolean bold){AppCompatTextView view=new AppCompatTextView(this);view.setText(label);view.setTextSize(size);view.setTextColor(getColor(bold?R.color.ink:R.color.muted));view.setPadding(0,dp(6),0,dp(6));if(bold)view.setTypeface(view.getTypeface(),android.graphics.Typeface.BOLD);return view;}
    private void heading(String label){content.addView(text(label,22,true));}
    private void paragraph(String label){content.addView(text(label,14,false));}
    private MaterialButton button(String label,Runnable action){MaterialButton button=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);button.setText(label);button.setTextSize(14);button.setMinHeight(dp(48));button.setCornerRadius(dp(14));button.setOnClickListener(v->{if(!requests.busy())action.run();});controls.add(button);return button;}
    private void addButton(LinearLayout box,String label,Runnable action){box.addView(button(label,action),new LinearLayout.LayoutParams(-1,-2));}
    private EditText field(LinearLayout box,String hint,String key,int max,boolean multiline){AppCompatEditText view=input(hint,max,multiline);view.setText(value(key));view.addTextChangedListener(watcher(text->put(key,text)));controls.add(view);box.addView(view);return view;}
    private EditText rowField(LinearLayout box,String hint,JSONObject row,String key){AppCompatEditText view=input(hint,key.equals("seconds")?8:300,false);view.setText(row.optString(key));if(!view.getText().toString().equals(row.optString(key))){set(row,key,view.getText().toString());set(row,"edited",true);}view.addTextChangedListener(watcher(text->{set(row,key,text);set(row,"edited",true);}));controls.add(view);box.addView(view);return view;}
    private AppCompatEditText input(String hint,int max,boolean multiline){AppCompatEditText view=new AppCompatEditText(this);view.setHint(hint);view.setMinHeight(dp(48));view.setTextSize(16);view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});view.setSingleLine(!multiline);if(multiline){view.setMinLines(3);view.setMaxLines(6);view.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);view.setGravity(Gravity.TOP);}return view;}
    private EditText secret(LinearLayout box,String hint){AppCompatEditText view=input(hint,128,false);view.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);view.setSaveEnabled(false);controls.add(view);box.addView(view);return view;}
    private TextWatcher watcher(java.util.function.Consumer<String> action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){action.accept(s.toString());}public void afterTextChanged(Editable s){}};}
    private void providerPicker(LinearLayout box,String key){Spinner picker=new Spinner(this);picker.setMinimumHeight(dp(48));picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDER_NAMES));for(int i=0;i<PROVIDERS.length;i++)if(PROVIDERS[i].equals(provider(key)))picker.setSelection(i);picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){put(key,PROVIDERS[position]);}public void onNothingSelected(android.widget.AdapterView<?> parent){}});controls.add(picker);box.addView(picker);}
    private String provider(String key){String selected=value(key);for(String provider:PROVIDERS)if(provider.equals(selected))return selected;return "spotify";}
    private String providerName(String provider){for(int i=0;i<PROVIDERS.length;i++)if(PROVIDERS[i].equals(provider))return PROVIDER_NAMES[i];return "음악 서비스";}
    private String serverBase(){String selected=getSharedPreferences("settings",MODE_PRIVATE).getString("server","");if(!selected.isEmpty())try{return ApiClient.validate(selected);}catch(IllegalArgumentException ignored){}return "";}
    private MaterialAlertDialogBuilder dialog(){return new MaterialAlertDialogBuilder(this){@Override public AlertDialog create(){AlertDialog dialog=super.create();dialogs.add(dialog);return dialog;}};}
    private void open(String url){try{Uri uri=Uri.parse(url);if(uri.getHost()==null||!(uri.getScheme().equals("https")||uri.getScheme().equals("http")&&ApiClient.localHost(uri.getHost())))throw new IllegalArgumentException();startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}catch(ActivityNotFoundException e){message("주소를 열 앱이나 브라우저가 없어요.");}catch(Exception e){message("연결 주소를 확인할 수 없어요.");}}
    private void share(String url){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url),"공개 목록 공유"));}catch(ActivityNotFoundException e){message("공유할 앱을 찾지 못했어요.");}}
    private String song(JSONObject track){if(track==null)return "곡 정보 확인 필요";String title=track.optString("title");return (title.isEmpty()?"곡명 확인 필요":title)+(track.optString("artist").isEmpty()?"":" · "+track.optString("artist"));}
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
