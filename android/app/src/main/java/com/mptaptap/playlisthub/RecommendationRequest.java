package com.mptaptap.playlisthub;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.mptaptap.core.Recommender;
import com.mptaptap.core.Track;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Retains one request across screen recreation, without retaining an Activity or its database. */
public final class RecommendationRequest extends ViewModel {
    static final class State {
        final String base;
        final boolean loading;
        final Object[] result;
        final Exception error;
        State(String base, boolean loading, Object[] result, Exception error) {
            this.base=base; this.loading=loading; this.result=result; this.error=error;
        }
    }
    private final MutableLiveData<State> state=new MutableLiveData<>(new State("",false,null,null));
    private final ExecutorService worker=Executors.newCachedThreadPool();
    private final Handler main=new Handler(Looper.getMainLooper());
    LiveData<State> states(){return state;}
    State current(){return state.getValue();}
    void consume(State delivered){if(current()==delivered)discard();}
    void discard(){state.setValue(new State("",false,null,null));}

    void start(String prompt, ApiClient api, List<Track> catalogue, Map<String,Integer> feedback) {
        if(current().loading)return;
        State pending=new State(api==null?"":api.base,true,null,null);
        state.setValue(pending);
        worker.execute(()->{
            Object[] result=null; Exception error=null;
            try {
                if(api==null) {
                    Recommender.Result value=Recommender.recommend(catalogue,Recommender.parse(prompt),feedback,"basic");
                    result=new Object[]{value.tracks,value.mode,value.notice,value.conditions};
                } else {
                    JSONObject value=api.object("POST","/recommendations",new JSONObject().put("prompt",prompt));
                    result=new Object[]{TrackJson.picks(value.getJSONArray("tracks")),value.getString("mode"),value.optString("notice"),TrackJson.conditions(value.getJSONObject("conditions"))};
                }
            } catch(Exception failure){error=failure;}
            State completed=new State(pending.base,false,result,error);
            main.post(()->{if(current()==pending)state.setValue(completed);});
        });
    }
    @Override protected void onCleared(){discard();worker.shutdownNow();}
}
