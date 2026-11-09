package com.mptaptap.playlisthub;

import com.mptaptap.core.Recommender;
import com.mptaptap.core.Track;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class TrackJson {
    static Track read(JSONObject j) throws JSONException {
        Track t = new Track();
        t.id = j.getString("id"); t.title = j.getString("title"); t.artist = j.getString("artist");
        t.language = j.optString("language"); t.source = j.getString("source"); t.sourceUrl = j.getString("sourceUrl");
        t.license = j.getString("license"); t.licenseReference = j.optString("licenseReference");
        t.tagsSource = j.optString("tagsSource"); t.checkedAt = j.optString("checkedAt");
        t.youtubeVideoId = j.optString("youtubeVideoId"); t.aiUseAllowed = j.optBoolean("aiUseAllowed");
        JSONArray tags = j.getJSONArray("tags");
        for (int i = 0; i < tags.length(); i++) t.tags.add(tags.getString(i));
        if (!t.usable()) throw new JSONException("추천 곡의 출처를 확인할 수 없어요.");
        return t;
    }
    static JSONObject write(Track t) throws JSONException {
        return new JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
            .put("language", t.language).put("tags", new JSONArray(t.tags)).put("source", t.source)
            .put("sourceUrl", t.sourceUrl).put("license", t.license).put("licenseReference", t.licenseReference)
            .put("tagsSource", t.tagsSource).put("checkedAt", t.checkedAt).put("youtubeVideoId", t.youtubeVideoId).put("aiUseAllowed", t.aiUseAllowed);
    }
    static List<Track> list(JSONArray rows) throws JSONException {
        List<Track> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) result.add(read(rows.getJSONObject(i)));
        return result;
    }
    static List<Recommender.Pick> picks(JSONArray rows) throws JSONException {
        List<Recommender.Pick> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            result.add(new Recommender.Pick(read(row.getJSONObject("track")), row.getString("reason")));
        }
        return result;
    }

    static Recommender.Conditions conditions(JSONObject value) throws JSONException {
        Recommender.Conditions c = new Recommender.Conditions();
        JSONArray tags=value.getJSONArray("tags"), excluded=value.getJSONArray("excludedTags");
        for (int i=0;i<tags.length();i++) c.tags.add(tags.getString(i));
        for (int i=0;i<excluded.length();i++) c.excludedTags.add(excluded.getString(i));
        c.language=value.getString("language"); return Recommender.validate(c);
    }
    static JSONObject conditions(Recommender.Conditions c) throws JSONException {
        return new JSONObject().put("tags", new JSONArray(c.tags)).put("excludedTags", new JSONArray(c.excludedTags)).put("language", c.language);
    }
}
