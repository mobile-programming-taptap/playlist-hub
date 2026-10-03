package com.mptaptap.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecommendationCheck {
    public static void main(String[] args) {
        assert Track.matches("How You Like That BLACKPINK", "ｂｌａｃｋｐｉｎｋ LIKE");
        assert Track.matches("비 오는 밤", "오는 비");
        assert Track.matches("Anything", " ");
        assert !Track.matches("BLACKPINK", "BTS");
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
        Set<String> unavailable = new HashSet<>();
        for (Recommender.Pick pick : result.tracks) unavailable.add(pick.track.id);
        Recommender.Result replacement = Recommender.recommend(tracks, conditions, feedback, "basic", unavailable);
        assert replacement.tracks.size() == 1 && replacement.tracks.get(0).track.id.equals(tracks.get(8).id);
        assert replacement.tracks.stream().noneMatch(p -> unavailable.contains(p.track.id));
        feedback.put(tracks.get(8).id, -1);
        Map<String, Integer> previousFeedback = new HashMap<>(feedback);
        assert Recommender.recommend(tracks, conditions, feedback, "basic", unavailable).tracks.isEmpty();
        assert feedback.equals(previousFeedback) : "Temporary replacement must not change reactions";
        feedback.remove(tracks.get(8).id); unavailable.add(tracks.get(8).id);
        assert Recommender.recommend(tracks, conditions, feedback, "basic", unavailable).tracks.isEmpty();
        assert feedback.isEmpty() : "Hidden/current IDs must not become permanent dislikes";
        try { Recommender.recommend(tracks, conditions, feedback, "basic", null); throw new AssertionError("Null temporary exclusions accepted"); } catch (IllegalArgumentException expected) { }
        tracks.get(0).tags.add("warm"); tracks.get(8).tags.add("warm"); feedback.put(tracks.get(0).id, 1);
        assert Recommender.recommend(tracks, conditions, feedback, "basic", java.util.Collections.singleton(tracks.get(0).id)).tracks.get(0).track.id.equals(tracks.get(8).id)
                : "Temporarily excluded liked tracks must still contribute personalization tags";
        feedback.remove(tracks.get(0).id); tracks.get(0).tags.remove("warm"); tracks.get(8).tags.remove("warm");
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
        tracks.get(0).youtubeVideoId = "12345678901";
        String shared = PlaylistText.format("  공부 목록  ", tracks.subList(0, 2));
        assert shared.startsWith("공부 목록\n총 2곡\n");
        assert shared.contains("1. Sample 0 — Artist\nhttps://music.youtube.com/watch?v=12345678901");
        assert shared.contains("2. Sample 1 — Artist\n" + tracks.get(1).sourceUrl);
        assert shared.endsWith("곡 정보: MusicBrainz 핵심 메타데이터(CC0).\n음원 파일은 포함하지 않아요.");
        assert PlaylistText.format("목록\n제목", java.util.Collections.emptyList()).startsWith("목록 제목\n총 0곡\n");
        try { PlaylistText.format("목록", Arrays.asList(invalid)); throw new AssertionError("Invalid share source accepted"); } catch (IllegalArgumentException expected) { }
        tracks.get(0).youtubeVideoId = "bad&injected=link";
        try { PlaylistText.format("목록", tracks.subList(0, 1)); throw new AssertionError("Invalid share video ID accepted"); } catch (IllegalArgumentException expected) { }
        tracks.get(0).youtubeVideoId = "";
        tracks.get(0).source = "Spotify";
        assert Recommender.recommend(tracks, conditions, feedback, "basic").tracks.stream().noneMatch(p -> p.track.id.equals(tracks.get(0).id));
        boolean rejected = false;
        try { Recommender.parse(" "); } catch (IllegalArgumentException e) { rejected = true; }
        assert rejected;
        conditions.language = "fr";
        try { Recommender.validate(conditions); throw new AssertionError("Unknown language accepted"); } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: catalogue source boundary, 8 picks, temporary replacement, vocal/unsupported language filters, dislike exclusion/undo, like ranking, negation, input validation and playlist text sharing.");
    }
}
