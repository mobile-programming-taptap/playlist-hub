package com.mptaptap.playlisthub;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** UI-free rules for the playlist export review: what is pre-selected, how rows group, what the send button says. */
final class ExportReview {
    private ExportReview(){}

    static final class Groups {
        private final List<JSONObject> needsReview=new ArrayList<>(),excluded=new ArrayList<>(),selected=new ArrayList<>();
        List<JSONObject> needsReview(){return needsReview;}
        List<JSONObject> excluded(){return excluded;}
        List<JSONObject> selected(){return selected;}
    }

    static String song(JSONObject track){if(track==null)return "곡 정보 확인 필요";String title=track.optString("title");return (title.isEmpty()?"곡명 확인 필요":title)+(track.optString("artist").isEmpty()?"":" · "+track.optString("artist"));}

    /**
     * Rule-scored MATCHED rows are pre-selected. UNKNOWN rows (no candidate is the same song) and rows with no candidate
     * are pre-excluded, so a different song never waits as a suggestion; only NEEDS_REVIEW (same song, unclear version) stays for the user.
     * Existing choices win.
     */
    static JSONArray autoSelect(JSONArray items,JSONArray selections) {
        JSONArray result=copy(selections);if(items==null)return result;
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;int index=item.optInt("sourceIndex",i);
            if(find(result,index)!=null)continue;
            String status=item.optString("status");JSONObject selected=item.optJSONObject("selected");JSONArray candidates=item.optJSONArray("candidates");
            if(status.equals("MATCHED")&&selected!=null&&!selected.optString("externalId").isEmpty())result.put(selection(index,selected.optString("externalId"),false,song(selected)));
            else if(status.equals("UNKNOWN")||!status.equals("NOT_SEARCHED")&&(candidates==null||candidates.length()==0))result.put(selection(index,"",true,""));
        }
        return result;
    }

    static Groups group(JSONArray items,JSONArray selections) {
        Groups groups=new Groups();if(items==null)return groups;
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;JSONObject chosen=find(selections,item.optInt("sourceIndex",i));
            if(chosen==null)groups.needsReview.add(item);else if(chosen.optBoolean("excluded"))groups.excluded.add(item);else groups.selected.add(item);
        }
        return groups;
    }

    static int autoCount(JSONArray selections){int count=0;if(selections!=null)for(int i=0;i<selections.length();i++){JSONObject row=selections.optJSONObject(i);if(row!=null&&row.optBoolean("auto")&&!row.optBoolean("excluded"))count++;}return count;}

    static String sendLabel(String providerName,JSONObject review,JSONArray selections) {
        if(review==null||review.optInt("nextOffset",-1)>=0)return "곡 후보 찾는 중";
        Groups groups=group(review.optJSONArray("items"),selections);
        if(!groups.needsReview.isEmpty())return "확인 필요 "+groups.needsReview.size()+"곡 남음";
        if(groups.selected.isEmpty())return "보낼 수 있는 곡이 없어요";
        return providerName+(providerName.equals("Spotify")?"로":"으로")+" "+groups.selected.size()+"곡 보내기";
    }

    static boolean canSend(JSONObject review,JSONArray selections) {
        if(review==null||review.optInt("nextOffset",-1)>=0)return false;
        Groups groups=group(review.optJSONArray("items"),selections);return groups.needsReview.isEmpty()&&!groups.selected.isEmpty();
    }

    static String transferLine(JSONObject transferItem,JSONArray reviewItems) {
        int index=transferItem.optInt("sourceIndex");String state=transferItem.optString("status");
        JSONObject item=find(reviewItems,index),source=item==null?null:item.optJSONObject("source");
        return (source==null?(index+1)+"번째 곡":song(source))+" · "+(state.equals("FAILED")?"실패 · 다시 시도할 수 있어요":state.equals("ADDED")?"전송 완료":state.equals("PENDING")?"전송 대기":"전송 결과 확인 필요");
    }

    /** The Bundle copy of user/auto choices; keeps the auto flag so the confirm dialog still counts auto picks after restore. */
    static JSONArray savedSelections(JSONArray selections) {
        JSONArray saved=new JSONArray();if(selections==null)return saved;
        for(int i=0;i<selections.length();i++){JSONObject row=selections.optJSONObject(i);if(row==null)continue;
            try{saved.put(new JSONObject().put("sourceIndex",row.optInt("sourceIndex")).put("targetId",row.optString("targetId")).put("excluded",row.optBoolean("excluded")).put("auto",row.optBoolean("auto")));}
            catch(JSONException error){throw new IllegalStateException(error);}}
        return saved;
    }
    static JSONObject find(JSONArray rows,int index){if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optInt("sourceIndex",i)==index)return row;}return null;}
    private static JSONArray copy(JSONArray rows){JSONArray result=new JSONArray();if(rows!=null)for(int i=0;i<rows.length();i++)result.put(rows.opt(i));return result;}
    private static JSONObject selection(int index,String targetId,boolean excluded,String label) {
        try{return new JSONObject().put("sourceIndex",index).put("targetId",targetId).put("excluded",excluded).put("label",label).put("auto",true);}
        catch(JSONException error){throw new IllegalStateException(error);}
    }
}
