package com.mptaptap.playlisthub;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.mptaptap.core.Recommender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;

/** Retains one request across screen recreation, without retaining an Activity or its database. */
public final class RecommendationRequest extends ViewModel {
    static final class State {
        final String base, identity;
        final boolean loading;
        final Object[] result;
        final Exception error;
        State(String base, String identity, boolean loading, Object[] result, Exception error) {
            this.base=base; this.identity=identity; this.loading=loading; this.result=result; this.error=error;
        }
    }
    private final MutableLiveData<State> state=new MutableLiveData<>(new State("","",false,null,null));
    private final ExecutorService worker=Executors.newCachedThreadPool();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String lastPrompt;
    private String lastBase="",lastIdentity="";
    private Recommender.Conditions lastPrevious;
    private List<String> lastHistory=List.of(),lastKept=List.of(),lastExcluded=List.of();
    LiveData<State> states(){return state;}
    State current(){return state.getValue();}
    void consume(State delivered){if(current()==delivered)state.setValue(new State("","",false,null,null));}
    void discard(){lastPrompt=null;lastPrevious=null;lastHistory=List.of();lastKept=List.of();lastExcluded=List.of();lastBase="";lastIdentity="";state.setValue(new State("","",false,null,null));}
    static String identity(ApiClient api){return api==null?"local":api.identity();}
    boolean retry(ApiClient api){
        if(lastPrompt==null||!lastBase.equals(api==null?"":api.base)||!lastIdentity.equals(identity(api)))return false;
        start(lastPrompt,api,lastHistory,lastKept,lastExcluded,lastPrevious);return true;
    }
    JSONObject snapshot() {
        if(lastPrompt==null)return null;
        try{return new JSONObject().put("prompt",lastPrompt).put("history",new JSONArray(lastHistory))
            .put("keptTrackIds",new JSONArray(lastKept)).put("excludedTrackIds",new JSONArray(lastExcluded))
            .put("previousConditions",lastPrevious==null?JSONObject.NULL:TrackJson.conditions(lastPrevious));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    void restore(JSONObject request,ApiClient api) throws Exception {
        if(current().loading)return;
        String prompt=request.getString("prompt");List<String> history=strings(request.getJSONArray("history"));
        com.mptaptap.core.Conversation.requests(history,prompt);
        lastPrompt=prompt;lastHistory=history;lastKept=strings(request.getJSONArray("keptTrackIds"));lastExcluded=strings(request.getJSONArray("excludedTrackIds"));
        lastPrevious=request.isNull("previousConditions")?null:TrackJson.conditions(request.getJSONObject("previousConditions"));
        lastBase=api==null?"":api.base;lastIdentity=identity(api);
    }
    private static List<String> strings(JSONArray values) throws Exception {List<String> rows=new ArrayList<>();for(int i=0;i<values.length();i++)rows.add(values.getString(i));return rows;}

    void start(String prompt, ApiClient api) {
        start(prompt,api,List.of(),List.of(),List.of());
    }
    void start(String prompt, ApiClient api,
            List<String> history, List<String> kept, List<String> excluded) {
        start(prompt,api,history,kept,excluded,null);
    }
    void start(String prompt, ApiClient api,
            List<String> history, List<String> kept, List<String> excluded, Recommender.Conditions previous) {
        if(current().loading)return;
        lastPrompt=prompt;lastHistory=new ArrayList<>(history);lastKept=new ArrayList<>(kept);lastExcluded=new ArrayList<>(excluded);
        try{lastPrevious=previous==null?null:TrackJson.conditions(TrackJson.conditions(previous));}catch(Exception error){throw new IllegalArgumentException(error);}
        lastBase=api==null?"":api.base;lastIdentity=identity(api);
        State pending=new State(lastBase,lastIdentity,true,null,null);
        List<String> requestHistory=new ArrayList<>(lastHistory),requestKept=new ArrayList<>(lastKept),requestExcluded=new ArrayList<>(lastExcluded);
        Recommender.Conditions requestPrevious=lastPrevious;
        state.setValue(pending);
        worker.execute(()->{
            Object[] result=null; Exception error=null;
            try {
                if(!pending.identity.equals(identity(api)))throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
                if(api==null)throw new IllegalStateException("AI 추천을 연결한 뒤 같은 요청으로 다시 시도해 주세요.");
                JSONObject value=api.object("POST","/recommendations",new JSONObject().put("prompt",prompt)
                    .put("history",new JSONArray(requestHistory)).put("keptTrackIds",new JSONArray(requestKept)).put("excludedTrackIds",new JSONArray(requestExcluded))
                    .put("previousConditions",requestPrevious==null?JSONObject.NULL:TrackJson.conditions(requestPrevious)));
                result=new Object[]{TrackJson.picks(value.getJSONArray("tracks")),value.getString("mode"),value.optString("notice"),TrackJson.conditions(value.getJSONObject("conditions"))};
                List<String> conversation=new ArrayList<>(requestHistory);conversation.add(prompt);
                while(conversation.size()>8)conversation.remove(0);
                result=new Object[]{result[0],result[1],result[2],result[3],conversation};
            } catch(Exception failure){error=failure;}
            State completed=new State(pending.base,pending.identity,false,result,error);
            main.post(()->{if(current()==pending)state.setValue(completed);});
        });
    }
    @Override protected void onCleared(){discard();worker.shutdownNow();}
}
