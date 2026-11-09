package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import com.kakao.sdk.auth.TokenManager;
import com.kakao.sdk.auth.model.OAuthToken;
import com.kakao.sdk.auth.model.Prompt;
import com.kakao.sdk.common.model.ClientError;
import com.kakao.sdk.common.model.ClientErrorCause;
import com.kakao.sdk.user.UserApiClient;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/** Holds a single request across rotation without retaining an Activity or repeating a write. */
public final class HubRequest extends ViewModel {
    static final class State {
        final String route;
        final boolean loading;
        final JSONObject value;
        final Exception error;
        final String base,identity;
        State(String route,boolean loading,JSONObject value,Exception error,String base,String identity){this.route=route;this.loading=loading;this.value=value;this.error=error;this.base=base;this.identity=identity;}
    }
    final JSONObject data=new JSONObject();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state=new MutableLiveData<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private KakaoAttempt kakaoAttempt;
    private static final class KakaoAttempt {
        final String base,owner;final boolean delete;
        boolean fallback;
        KakaoAttempt(String base,String owner,boolean delete){this.base=base;this.owner=owner;this.delete=delete;}
    }
    private String lastRoute,lastMethod,lastPath,lastBase,lastIdentity,dataBase,dataIdentity;
    private JSONObject lastBody;
    LiveData<State> states(){return state;}
    State current(){return state.getValue();}
    boolean busy(){return current()!=null&&current().loading;}
    void consume(State delivered){if(current()==delivered)state.setValue(null);}
    private static String identity(Context context,String base){return context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE).getString(base,"");}
    private static String selectedBase(Context context){return context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("server","").trim().replaceAll("/+$","");}
    String fingerprint(Context context,String base){return UUID.nameUUIDFromBytes((base+"|"+identity(context,base)).getBytes(StandardCharsets.UTF_8)).toString();}
    void clearData(){java.util.Iterator<String> keys=data.keys();while(keys.hasNext()){keys.next();keys.remove();}}
    boolean synchronize(Context context,String base){
        String current=identity(context,base);boolean changed=dataBase!=null&&(!dataBase.equals(base)||!dataIdentity.equals(current));
        if(changed){clearData();lastRoute=null;lastBody=null;}dataBase=base;dataIdentity=current;return changed;
    }
    boolean accept(Context context,String base,State result){
        String current=identity(context,base);if(!base.equals(selectedBase(context))||!base.equals(result.base)||!current.equals(result.identity))return false;
        if(dataBase!=null&&(!dataBase.equals(base)||!dataIdentity.equals(current)&&!dataIdentity.isEmpty()))clearData();
        dataBase=base;dataIdentity=current;return true;
    }
    private void changed(Context context,String base){synchronize(context,base);state.setValue(new State("identity",false,null,new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요."),base,identity(context,base)));}
    void retry(Context context,String base){
        if(lastRoute==null)return;
        if(!base.equals(selectedBase(context))||!base.equals(lastBase)||!identity(context,base).equals(lastIdentity)){changed(context,base);return;}
        send(context,base,lastRoute,lastMethod,lastPath,lastBody);
    }

    void rememberAuthorization(Context context,String base,String provider,boolean library){
        if(!platform(provider)||identity(context,base).isEmpty())throw new IllegalStateException("음악 계정 연결을 다시 시작해 주세요.");
        if(!context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
                .putString("authorizationBase",base).putString("authorizationOwner",fingerprint(context,base))
                .putString("authorizationProvider",provider).putBoolean("authorizationLibrary",library)
                .putLong("authorizationStarted",System.currentTimeMillis()).commit())throw new IllegalStateException("연결 요청을 보관하지 못했어요. 다시 시도해 주세요.");
        data.remove("authorizationStatus");data.remove("authorizationReason");data.remove("authorizationNotice");data.remove("authorizationRejected");
        restoreAuthorization(context,base);
    }
    boolean restoreAuthorization(Context context,String base){
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        String provider=settings.getString("authorizationProvider","");
        if(provider.isEmpty())return false;
        if(!platform(provider)||identity(context,base).isEmpty()||!base.equals(settings.getString("authorizationBase",""))
                ||!fingerprint(context,base).equals(settings.getString("authorizationOwner",""))){forgetAuthorization(context);return false;}
        put("authorizationProvider",provider);boolean library=settings.getBoolean("authorizationLibrary",false);put("libraryConnect",String.valueOf(library));if(library)put("libraryProvider",provider);put("authorizationPending",true);
        long age=System.currentTimeMillis()-settings.getLong("authorizationStarted",0);
        if(age<0||age>600_000){put("authorizationStatus","error");put("authorizationReason","session_expired");}
        return true;
    }
    boolean receiveAuthorization(Context context,String base,Uri uri){
        String provider,status,reason;
        try{
            if(uri==null||!uri.isHierarchical()||!"taptap".equals(uri.getScheme())||!"connected".equals(uri.getEncodedAuthority())
                    ||!uri.getEncodedPath().isEmpty()||uri.getFragment()!=null)throw new IllegalArgumentException();
            for(String key:uri.getQueryParameterNames())if(!java.util.Arrays.asList("provider","status","reason").contains(key)||uri.getQueryParameters(key).size()!=1)throw new IllegalArgumentException();
            provider=uri.getQueryParameter("provider");status=uri.getQueryParameter("status");reason=uri.getQueryParameter("reason");
            if(reason==null)reason="";
            if(!platform(provider)||!("connected".equals(status)||"cancelled".equals(status)||"error".equals(status))
                    ||!java.util.Arrays.asList("","access_denied","session_expired","provider_unauthorized","provider_forbidden","provider_rate_limited","provider_unavailable","connection_failed").contains(reason)
                    ||"connected".equals(status)&&!reason.isEmpty()||"cancelled".equals(status)&&!(reason.isEmpty()||reason.equals("access_denied")))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){put("authorizationRejected","true");put("authorizationNotice","음악 계정 연결 응답을 확인할 수 없어요. 앱에서 다시 연결해 주세요.");return false;}
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        if(!provider.equals(settings.getString("authorizationProvider",""))||!restoreAuthorization(context,base)){
            put("authorizationRejected","true");put("authorizationNotice","진행 중인 음악 계정 연결 요청이 없어요. 앱에서 다시 연결해 주세요.");return false;
        }
        // The URI is only an outcome hint. The Activity must verify this owner's connection with the server.
        data.remove("authorizationRejected");if(!"session_expired".equals(data.optString("authorizationReason"))){put("authorizationStatus",status);put("authorizationReason",reason);}
        put("authorizationPending",true);return true;
    }
    void forgetAuthorization(Context context){
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().remove("authorizationBase").remove("authorizationOwner")
                .remove("authorizationProvider").remove("authorizationLibrary").remove("authorizationStarted").apply();
    }
    private void put(String key,Object value){try{data.put(key,value);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static boolean platform(String value){return "spotify".equals(value)||"youtube".equals(value);}

    boolean kakaoPending(){return kakaoAttempt!=null;}
    void cancelKakao(Context context){
        KakaoAttempt attempt=kakaoAttempt;if(attempt==null)return;kakaoAttempt=null;
        state.setValue(new State("kakaoAuthorization",false,null,new IllegalStateException("카카오 로그인을 취소했어요. 현재 계정은 유지돼요."),attempt.base,identity(context,attempt.base)));
    }
    void startKakao(Context activity,String base,boolean delete){
        if(closed||busy()||kakaoAttempt!=null)return;
        Context app=activity.getApplicationContext();
        if(!TapTapApplication.kakaoConfigured()){
            state.setValue(new State("kakaoAuthorization",false,null,new IllegalStateException("카카오 네이티브 앱 키 설정이 필요해요. 앱을 설정한 뒤 다시 설치해 주세요."),base,identity(app,base)));return;
        }
        if(!base.equals(selectedBase(app))){changed(app,base);return;}
        lastRoute=null;lastBody=null;
        KakaoAttempt attempt=new KakaoAttempt(base,identity(app,base),delete);kakaoAttempt=attempt;
        state.setValue(new State("kakaoAuthorization",true,null,null,base,attempt.owner));
        Function2<OAuthToken,Throwable,Unit> callback=(token,error)->{kakaoResult(app,attempt,token==null?null:token.getAccessToken(),error,true);return Unit.INSTANCE;};
        try{
            if(!delete&&UserApiClient.getInstance().isKakaoTalkLoginAvailable(activity))UserApiClient.getInstance().loginWithKakaoTalk(activity,callback);
            else launchKakaoAccount(activity,attempt);
        }catch(Exception error){kakaoResult(app,attempt,null,error,!delete);}
    }
    void fallbackKakao(Context activity){
        KakaoAttempt attempt=kakaoAttempt;
        if(attempt==null||!attempt.fallback||closed)return;
        Context app=activity.getApplicationContext();
        if(!attempt.base.equals(selectedBase(app))||!attempt.owner.equals(identity(app,attempt.base))){kakaoAttempt=null;changed(app,selectedBase(app));return;}
        attempt.fallback=false;
        state.setValue(new State("kakaoAuthorization",true,null,null,attempt.base,attempt.owner));
        launchKakaoAccount(activity,attempt);
    }
    private void launchKakaoAccount(Context activity,KakaoAttempt attempt){
        Context app=activity.getApplicationContext();
        Function2<OAuthToken,Throwable,Unit> callback=(token,error)->{kakaoResult(app,attempt,token==null?null:token.getAccessToken(),error,false);return Unit.INSTANCE;};
        try{
            if(attempt.delete)UserApiClient.getInstance().loginWithKakaoAccount(activity,java.util.Collections.singletonList(Prompt.LOGIN),null,null,null,null,callback);
            else UserApiClient.getInstance().loginWithKakaoAccount(activity,callback);
        }catch(Exception error){kakaoResult(app,attempt,null,error,false);}
    }
    private void kakaoResult(Context app,KakaoAttempt attempt,String accessToken,Throwable error,boolean fromTalk){
        main.post(()->{
            if(closed||kakaoAttempt!=attempt)return;
            if(!attempt.base.equals(selectedBase(app))||!attempt.owner.equals(identity(app,attempt.base))){kakaoAttempt=null;changed(app,selectedBase(app));return;}
            boolean cancelled=error instanceof ClientError&&((ClientError)error).getReason()==ClientErrorCause.Cancelled;
            if(error!=null&&fromTalk&&!cancelled){attempt.fallback=true;state.setValue(new State("kakaoFallback",false,new JSONObject(),null,attempt.base,attempt.owner));return;}
            kakaoAttempt=null;
            if(error!=null||accessToken==null||accessToken.isEmpty()){
                String message=cancelled?"카카오 로그인을 취소했어요. 현재 계정은 유지돼요.":"카카오 로그인을 완료하지 못했어요. 다시 시도해 주세요.";
                state.setValue(new State("kakaoAuthorization",false,null,new IllegalStateException(message),attempt.base,attempt.owner));return;
            }
            state.setValue(null);
            try{send(app,attempt.base,attempt.delete?"signedOut":"signedIn",attempt.delete?"DELETE":"POST",attempt.delete?"/accounts/me":"/accounts/kakao",new JSONObject().put(attempt.delete?"kakaoAccessToken":"accessToken",accessToken));}
            catch(Exception failure){state.setValue(new State("kakaoAuthorization",false,null,new IllegalStateException("로그인을 다시 시도해 주세요."),attempt.base,attempt.owner));}
        });
    }

    void send(Context context,String base,String route,String method,String path,JSONObject body) {
        if(closed||busy())return;
        Context app=context.getApplicationContext();
        if(!base.equals(selectedBase(app))){changed(app,base);return;}
        if(synchronize(app,base)){changed(app,base);return;}
        ApiClient client=new ApiClient(app,base);String owner=client.identity();
        final JSONObject snapshot;
        try{snapshot=body==null?null:new JSONObject(body.toString());}
        catch(Exception error){state.setValue(new State(route,false,null,error,base,owner));return;}
        boolean sensitive=snapshot!=null&&(snapshot.has("password")||snapshot.has("accessToken")||snapshot.has("kakaoAccessToken"));
        lastRoute=sensitive?null:route;lastMethod=method;lastPath=path;lastBody=sensitive?null:snapshot;lastBase=base;lastIdentity=owner;
        state.setValue(new State(route,true,null,null,base,owner));
        worker.execute(()->{
            String resultOwner=owner;
            try {
                if(!base.equals(selectedBase(app))||!owner.equals(client.identity()))throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                client.connect();if(!client.ownsCurrentSession()||!owner.isEmpty()&&!owner.equals(client.identity()))throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                resultOwner=client.identity();lastIdentity=resultOwner;JSONObject result;
                if(route.equals("account")) {
                    result=new JSONObject();
                    try{result.put("profile",client.object("GET","/accounts/me",null));}
                    catch(ApiClient.ApiError error){if(error.statusCode==401){client.connect();resultOwner=client.identity();lastIdentity=resultOwner;}else if(error.statusCode!=404)throw error;}
                    result.put("configuration",client.object("GET","/connections/config",null));
                    result.put("connections",client.array("/connections"));
                    result.put("sessionExpired",client.loginExpired());
                } else if(route.equals("guest")) {
                    boolean registered=true;
                    try{client.object("GET","/accounts/me",null);}catch(ApiClient.ApiError error){if(error.statusCode!=404)throw error;registered=false;}
                    if(registered){client.replaceSession(client.object("POST","/sessions",null).getString("token"));resultOwner=client.identity();lastIdentity=resultOwner;}
                    result=new JSONObject();
                } else if(route.equals("history")) {
                    result=new JSONObject().put("imports",client.array("/imports/playlists?summary=true"))
                            .put("transfers",client.array("/transfers?summary=true"));
                } else {
                    String raw=client.request(method,path,snapshot);
                    if(closed||!base.equals(selectedBase(app))||!client.ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                    result=raw.trim().startsWith("[")?new JSONObject().put("rows",new JSONArray(raw)):raw.trim().isEmpty()?new JSONObject():new JSONObject(raw);
                    if(path.equals("/accounts/register")||path.equals("/accounts/login")||path.equals("/accounts/kakao")){client.replaceSession(result.getString("token"));result.remove("token");resultOwner=client.identity();}
                    if(method.equals("DELETE")&&(path.equals("/accounts/session")||path.equals("/accounts/me"))){client.clearSession();resultOwner="";}
                }
                if(!base.equals(selectedBase(app))||!client.ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                if(route.equals("signedOut")&&TapTapApplication.kakaoConfigured()){
                    JSONObject delivered=result;String deliveredOwner=resultOwner;
                    main.post(()->{
                        if(closed)return;
                        try{UserApiClient.getInstance().logout(error->{TokenManager.getInstance().clear();if(!closed)state.setValue(new State(route,false,delivered,null,base,deliveredOwner));return Unit.INSTANCE;});}
                        catch(Exception ignored){TokenManager.getInstance().clear();if(!closed)state.setValue(new State(route,false,delivered,null,base,deliveredOwner));}
                    });return;
                }
                if(!closed)state.postValue(new State(route,false,result,null,base,resultOwner));
            } catch(Exception error){if(!closed)state.postValue(new State(route,false,null,error,base,resultOwner));}
            finally{if(sensitive){snapshot.remove("password");snapshot.remove("accessToken");snapshot.remove("kakaoAccessToken");}}
        });
    }
    @Override protected void onCleared(){closed=true;kakaoAttempt=null;lastBody=null;worker.shutdownNow();}
}
