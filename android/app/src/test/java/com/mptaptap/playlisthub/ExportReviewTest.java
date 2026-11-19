package com.mptaptap.playlisthub;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExportReviewTest {
    private static JSONObject track(String id,String title,String artist)throws Exception{return new JSONObject().put("externalId",id).put("title",title).put("artist",artist);}
    private static JSONObject candidate(String id)throws Exception{return new JSONObject().put("track",track(id,"T"+id,"A"+id));}
    /** index 0 MATCHED, 1 NEEDS_REVIEW (2 candidates), 2 UNKNOWN (none), 3 NOT_SEARCHED. */
    private static JSONArray items()throws Exception{
        return new JSONArray()
            .put(new JSONObject().put("sourceIndex",0).put("status","MATCHED").put("selected",track("sp1","Song","Artist")).put("candidates",new JSONArray().put(candidate("sp1"))).put("source",track("y0","A","B")))
            .put(new JSONObject().put("sourceIndex",1).put("status","NEEDS_REVIEW").put("candidates",new JSONArray().put(candidate("c1")).put(candidate("c2"))).put("source",track("y1","C","D")))
            .put(new JSONObject().put("sourceIndex",2).put("status","UNKNOWN").put("candidates",new JSONArray()).put("source",track("y2","E","F")))
            .put(new JSONObject().put("sourceIndex",3).put("status","NOT_SEARCHED").put("candidates",new JSONArray()).put("source",track("y3","G","H")));
    }
    private static JSONObject find(JSONArray rows,int index){for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i).optInt("sourceIndex")==index)return rows.optJSONObject(i);return null;}

    @Test public void autoSelectPicksMatchedAndExcludesEmpty()throws Exception{
        JSONArray before=new JSONArray(),selections=ExportReview.autoSelect(items(),before);
        assertEquals(2,selections.length());assertEquals(0,before.length());
        JSONObject matched=find(selections,0);assertEquals("sp1",matched.getString("targetId"));assertFalse(matched.getBoolean("excluded"));assertTrue(matched.getBoolean("auto"));assertEquals("Song · Artist",matched.getString("label"));
        JSONObject empty=find(selections,2);assertTrue(empty.getBoolean("excluded"));assertTrue(empty.getBoolean("auto"));
        assertNull(find(selections,1));assertNull(find(selections,3));
    }
    @Test public void autoSelectKeepsUserChoice()throws Exception{
        JSONArray user=new JSONArray().put(new JSONObject().put("sourceIndex",0).put("targetId","other").put("excluded",false).put("auto",false));
        JSONObject kept=find(ExportReview.autoSelect(items(),user),0);
        assertEquals("other",kept.getString("targetId"));assertFalse(kept.getBoolean("auto"));
    }
    @Test public void groupOrdersBySection()throws Exception{
        JSONArray items=items();ExportReview.Groups groups=ExportReview.group(items,ExportReview.autoSelect(items,new JSONArray()));
        assertEquals(2,groups.needsReview().size());assertEquals(1,groups.needsReview().get(0).getInt("sourceIndex"));assertEquals(3,groups.needsReview().get(1).getInt("sourceIndex"));
        assertEquals(1,groups.excluded().size());assertEquals(2,groups.excluded().get(0).getInt("sourceIndex"));
        assertEquals(1,groups.selected().size());assertEquals(0,groups.selected().get(0).getInt("sourceIndex"));
    }
    @Test public void sendLabelStates()throws Exception{
        JSONArray items=new JSONArray().put(items().get(0)).put(items().get(1)),selections=ExportReview.autoSelect(items,new JSONArray());
        JSONObject running=new JSONObject().put("items",items).put("nextOffset",5),done=new JSONObject().put("items",items).put("nextOffset",-1);
        assertEquals("곡 후보 찾는 중",ExportReview.sendLabel("Spotify",running,selections));assertFalse(ExportReview.canSend(running,selections));
        assertEquals("확인 필요 1곡 남음",ExportReview.sendLabel("Spotify",done,selections));assertFalse(ExportReview.canSend(done,selections));
        selections.put(new JSONObject().put("sourceIndex",1).put("targetId","").put("excluded",true).put("auto",false));
        assertEquals("Spotify로 1곡 보내기",ExportReview.sendLabel("Spotify",done,selections));assertTrue(ExportReview.canSend(done,selections));
        assertEquals("YouTube Music으로 1곡 보내기",ExportReview.sendLabel("YouTube Music",done,selections));
    }
    @Test public void autoCountDropsWhenUserExcludes()throws Exception{
        JSONArray selections=ExportReview.autoSelect(items(),new JSONArray());assertEquals(1,ExportReview.autoCount(selections));
        JSONObject matched=find(selections,0);matched.put("excluded",true).put("auto",false).put("targetId","");
        assertEquals(0,ExportReview.autoCount(selections));
    }
    @Test public void transferLineUsesSongName()throws Exception{
        assertEquals("A · B · 전송 완료",ExportReview.transferLine(new JSONObject().put("sourceIndex",0).put("status","ADDED"),items()));
        assertEquals("8번째 곡 · 전송 대기",ExportReview.transferLine(new JSONObject().put("sourceIndex",7).put("status","PENDING"),items()));
    }
    @Test public void savedSelectionsKeepAutoFlag()throws Exception{
        JSONArray selections=ExportReview.autoSelect(items(),new JSONArray());
        JSONArray saved=new JSONArray(ExportReview.savedSelections(selections).toString());
        assertEquals(1,ExportReview.autoCount(saved));assertEquals("sp1",find(saved,0).getString("targetId"));assertTrue(find(saved,2).getBoolean("excluded"));
    }
    @Test public void unrelatedCandidatesAreExcludedNotOffered()throws Exception{
        JSONArray items=new JSONArray().put(new JSONObject().put("sourceIndex",0).put("status","UNKNOWN").put("candidates",new JSONArray().put(candidate("wrong"))).put("source",track("y0","재즈 모음","채널")));
        JSONArray selections=ExportReview.autoSelect(items,new JSONArray());
        JSONObject row=find(selections,0);assertNotNull("An unrelated candidate must not wait for review",row);assertTrue(row.getBoolean("excluded"));assertTrue(row.getBoolean("auto"));
        ExportReview.Groups groups=ExportReview.group(items,selections);assertTrue(groups.needsReview().isEmpty());assertEquals(1,groups.excluded().size());
    }
}
