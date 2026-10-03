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
        ImportMatchCheck.run();
        ConversationCheck.run();
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
        for (String request : List.of("재즈만 추천해줘", "가사 없는 jazz", "클래식", "hip-hop", "R&B", "EDM", "블루스", "메탈")) {
            Recommender.Result absent = Recommender.recommend(tracks, Recommender.parse(request), feedback, "basic");
            assert absent.tracks.isEmpty() && absent.notice.contains("장르") && absent.notice.contains("0곡") : request;
        }
        assert Recommender.recommend(tracks,Recommender.parse("재즈 장르는 제외하고 피아노"),feedback,"basic").tracks.size()==8;
        Recommender.Conditions unlisted=new Recommender.Conditions();unlisted.tags.addAll(List.of("unsupported_genre","pop","instrumental"));
        assert Recommender.recommend(tracks,unlisted,feedback,"openai").tracks.isEmpty():"An unlisted explicit genre must not be filled with a supported genre";
        Recommender.Result keptJazz=Conversation.recommend(tracks,Recommender.parse("재즈"),feedback,"basic",List.of(tracks.get(4).id),List.of());
        assert keptJazz.tracks.size()==1&&keptJazz.tracks.get(0).track.id.equals(tracks.get(4).id);
        assert keptJazz.notice.contains("직접 유지한 1곡")&&keptJazz.notice.contains("장르")&&keptJazz.notice.contains("새 곡");
        List<String> allKept=new ArrayList<>();for(Recommender.Pick pick:result.tracks)allKept.add(pick.track.id);
        Recommender.Result allKeptJazz=Conversation.recommend(tracks,Recommender.parse("가사 없는 재즈"),feedback,"basic",allKept,List.of());
        assert allKeptJazz.tracks.size()==8&&allKeptJazz.notice.contains("직접 유지한 8곡")&&allKeptJazz.notice.contains("새 곡");
        assert feedback.isEmpty()&&tracks.stream().noneMatch(t->t.tags.contains("jazz")):"A missing genre must not alter feedback or catalogue tags";
        Recommender.Result continued=Conversation.recommend(tracks,conditions,feedback,"basic",List.of(tracks.get(4).id),List.of(tracks.get(0).id));
        assert continued.tracks.size()==8&&continued.tracks.get(0).track.id.equals(tracks.get(4).id);
        assert continued.tracks.stream().noneMatch(p->p.track.id.equals(tracks.get(0).id));
        assert continued.notice.contains("유지한 1곡")&&!continued.notice.contains("7곡");
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
        for(String request:List.of("exclude piano","avoid piano","without piano","EXCLUDE piano","avoid   piano","without\tpiano","without\npiano","no piano","not piano","no   piano","not\tpiano","피아노 제외")) {
            Recommender.Conditions negative=Recommender.parse(request);
            assert negative.tags.isEmpty()&&negative.excludedTags.equals(List.of("piano")):request;
            assert Recommender.isKnownConditionRequest(request):request;
            assert Recommender.recommend(tracks,negative,feedback,"basic").tracks.stream().noneMatch(p->p.track.tags.contains("piano")):request;
        }
        for(String request:List.of("notreally piano","noise piano","cannot piano","volcano piano","unexclude piano","unavoid piano","withoutness piano")) {
            Recommender.Conditions positive=Recommender.parse(request);
            assert positive.tags.equals(List.of("piano"))&&positive.excludedTags.isEmpty():request;
            assert !Recommender.isKnownConditionRequest(request):request;
        }
        Recommender.Conditions contrasting=Recommender.parse("piano without rock");
        assert contrasting.tags.equals(List.of("piano"))&&contrasting.excludedTags.equals(List.of("rock"));
        Recommender.Result vocals = Recommender.recommend(tracks, Recommender.parse("no instrumental"), feedback, "basic");
        assert vocals.tracks.size() == 3;
        assert vocals.tracks.stream().allMatch(p -> !p.track.language.equals("none"));
        assert Recommender.parse("K pop").tags.equals(Arrays.asList("kpop"));
        Recommender.Conditions noLongerInstrumental=Conversation.parse(List.of("가사 없는 음악"),"연주곡 제외");
        assert Recommender.recommend(tracks,noLongerInstrumental,feedback,"basic").tracks.size()==3:
                "Removing an earlier no-vocals condition must allow vocal tracks again";
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
        System.out.println("PASS: catalogue source boundary, 8 picks, missing genre/kept boundaries, temporary replacement, vocal/unsupported language filters, dislike exclusion/undo, like ranking, negation, input validation and playlist text sharing.");
    }
}
