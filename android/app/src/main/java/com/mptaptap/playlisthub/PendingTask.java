package com.mptaptap.playlisthub;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/** Keeps only a task marker; an Activity and its database remain outside this model. */
public final class PendingTask extends ViewModel {
    static final class State {
        final Object owner;
        final boolean loading;
        final Exception error;
        State(Object owner, boolean loading, Exception error) {
            this.owner=owner;this.loading=loading;this.error=error;
        }
    }
    private final MutableLiveData<State> state=new MutableLiveData<>(null);
    LiveData<State> states(){return state;}
    State current(){return state.getValue();}
    State begin(Object owner){
        if(current()!=null)throw new IllegalStateException("A task is already pending");
        State pending=new State(owner,true,null);state.setValue(pending);return pending;
    }
    State finish(State pending,Exception error){
        if(current()!=pending)return null;
        State completed=new State(pending.owner,false,error);state.setValue(completed);return completed;
    }
    void consume(State delivered){if(current()==delivered)state.setValue(null);}
    @Override protected void onCleared(){state.setValue(null);}
}
