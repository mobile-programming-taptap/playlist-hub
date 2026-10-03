package com.mptaptap.playlisthub;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private volatile boolean closed;
    private String lastRoute,lastMethod,lastPath,lastBase,lastIdentity,dataBase,dataIdentity;
    private JSONObject lastBody;
    LiveData<State> states(){return state;}
    State current(){return state.getValue();}
    boolean busy(){return current()!=null&&current().loading;}
    void consume(State delivered){if(current()==delivered)state.setValue(null);}
    private static String identity(Context context,String base){return context.getSharedPreferences("server_credentials",Context.MODE_PRIVATE).getString(base,"");}
    private static String selectedBase(Context context){return context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("server","").trim().replaceAll("/+$","");}
    String fingerprint(Context context,String base){return UUID.nameUUIDFromBytes((base+"|"+identity(context,base)).getBytes(StandardCharsets.UTF_8)).toString();}
    private void clearData(){java.util.Iterator<String> keys=data.keys();while(keys.hasNext()){keys.next();keys.remove();}}
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

    void send(Context context,String base,String route,String method,String path,JSONObject body) {
        if(closed||busy())return;
        Context app=context.getApplicationContext();
        if(!base.equals(selectedBase(app))){changed(app,base);return;}
        if(synchronize(app,base)){changed(app,base);return;}
        ApiClient client=new ApiClient(app,base);String owner=client.identity();
        final JSONObject snapshot;
        try{snapshot=body==null?null:new JSONObject(body.toString());}
        catch(Exception error){state.setValue(new State(route,false,null,error,base,owner));return;}
        lastRoute=route;lastMethod=method;lastPath=path;lastBody=snapshot;lastBase=base;lastIdentity=owner;
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
                } else if(route.equals("history")) {
                    result=new JSONObject().put("imports",client.array("/imports/playlists?summary=true"))
                            .put("transfers",client.array("/transfers?summary=true"));
                } else {
                    String raw=client.request(method,path,snapshot);
                    if(closed||!base.equals(selectedBase(app))||!client.ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                    result=raw.trim().startsWith("[")?new JSONObject().put("rows",new JSONArray(raw)):raw.trim().isEmpty()?new JSONObject():new JSONObject(raw);
                    if(path.equals("/accounts/register")||path.equals("/accounts/login")){client.replaceSession(result.getString("token"));result.remove("token");resultOwner=client.identity();}
                    if(method.equals("DELETE")&&(path.equals("/accounts/session")||path.equals("/accounts/me"))){client.clearSession();resultOwner="";}
                }
                if(!base.equals(selectedBase(app))||!client.ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                if(snapshot!=null&&snapshot.has("password")){snapshot.remove("password");lastBody=null;lastRoute=null;}
                if(!closed)state.postValue(new State(route,false,result,null,base,resultOwner));
            } catch(Exception error){if(!closed)state.postValue(new State(route,false,null,error,base,resultOwner));}
        });
    }
    @Override protected void onCleared(){closed=true;lastBody=null;worker.shutdownNow();}
}
