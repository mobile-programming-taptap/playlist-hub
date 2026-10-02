package com.mptaptap.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecommendationCheck {
    public static void main(String[] args) {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Track t = new Track();
            t.id = String.format("00000000-0000-0000-0000-%012d", i);
            t.title = "Sample " + i; t.artist = "Artist";
            t.source = "MusicBrainz"; t.license = "CC0-1.0"; t.aiUseAllowed = true;
            t.sourceUrl = "https://musicbrainz.org/recording/" + t.id;
            t.language = i < 9 ? "none" : "ko";
            t.tags = new ArrayList<>(i < 9 ? Arrays.asList("calm", "piano", "instrumental", "study") : Arrays.asList("energetic", "kpop", "workout"));
            tracks.add(t);
        }
        Map<String, Integer> feedback = new HashMap<>();
        Track invalid = new Track();
        invalid.id = "------------------------------------"; invalid.title = "Invalid"; invalid.artist = "Artist";
        invalid.source = "MusicBrainz"; invalid.license = "CC0-1.0"; invalid.aiUseAllowed = true;
        invalid.sourceUrl = "https://musicbrainz.org/recording/" + invalid.id;
        assert !invalid.usable() : "Malformed recording ID must not be treated as a verified source";
        Recommender.Conditions conditions = Recommender.parse("공부할 때 가사 없는 잔잔한 피아노 음악");
        Recommender.Result result = Recommender.recommend(tracks, conditions, feedback, "basic");
        assert result.tracks.size() == 8;
        assert result.tracks.stream().allMatch(p -> p.track.language.equals("none"));
        String disliked = result.tracks.get(0).track.id;
        feedback.put(disliked, -1);
        assert Recommender.recommend(tracks, conditions, feedback, "basic").tracks.stream().noneMatch(p -> p.track.id.equals(disliked));
        feedback.remove(disliked);
        assert Recommender.recommend(tracks, conditions, feedback, "basic").tracks.stream().anyMatch(p -> p.track.id.equals(disliked));
        feedback.put(tracks.get(8).id, 1);
        assert Recommender.recommend(tracks, conditions, feedback, "basic").tracks.get(0).track.id.equals(tracks.get(8).id);
        assert Recommender.parse("신나는 곡은 빼고 잔잔한 곡").excludedTags.contains("energetic");
        assert Recommender.parse("가사 없는 곡은 빼고").excludedTags.contains("instrumental");
        Recommender.Result vocals = Recommender.recommend(tracks, Recommender.parse("no instrumental"), feedback, "basic");
        assert vocals.tracks.size() == 3;
        assert vocals.tracks.stream().allMatch(p -> !p.track.language.equals("none"));
        assert Recommender.parse("K pop").tags.equals(Arrays.asList("kpop"));
        assert Recommender.recommend(tracks, Recommender.parse("일본어 노래"), feedback, "basic").tracks.isEmpty();
        tracks.get(0).source = "Spotify";
        assert Recommender.recommend(tracks, conditions, feedback, "basic").tracks.stream().noneMatch(p -> p.track.id.equals(tracks.get(0).id));
        boolean rejected = false;
        try { Recommender.parse(" "); } catch (IllegalArgumentException e) { rejected = true; }
        assert rejected;
        conditions.language = "fr";
        try { Recommender.validate(conditions); throw new AssertionError("Unknown language accepted"); } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: catalogue source boundary, 8 picks, vocal/unsupported language filters, dislike exclusion/undo, like ranking, negation and input validation.");
    }
}
