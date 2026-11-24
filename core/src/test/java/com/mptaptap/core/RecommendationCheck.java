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
        TrackReferenceCheck.run();
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
        Recommender.Conditions happyJpop=Recommender.parse("즐거운 제이팝");
        assert happyJpop.tags.equals(List.of("jpop"))&&happyJpop.language.isEmpty();
        assert !Recommender.isKnownConditionRequest("즐거운 제이팝"):"Interpret natural-language moods through AI, without request-specific keyword exceptions";
        Recommender.Result absentJpop=Recommender.recommend(tracks,happyJpop,feedback,"openai");
        assert absentJpop.tracks.isEmpty()&&!absentJpop.notice.contains("카탈로그")&&absentJpop.notice.contains("이번 추천");
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
        Track foreign=new Track();foreign.id="00000000-0000-0000-0000-000000000012";foreign.title="Verified foreign-language song";foreign.artist="Artist";
        foreign.language="ja";foreign.tags.add("pop");foreign.source="MusicBrainz";foreign.license="CC0-1.0";foreign.aiUseAllowed=true;
        foreign.sourceUrl="https://musicbrainz.org/recording/"+foreign.id;
        List<Track> expanded=new ArrayList<>(tracks);expanded.add(foreign);Recommender.Conditions foreignConditions=Recommender.parse("일본어 팝");
        Recommender.Result foreignResult=Recommender.recommend(expanded,foreignConditions,feedback,"openai");
        assert foreignResult.tracks.size()==1&&foreignResult.notice.contains("1곡")&&!foreignResult.notice.contains("0곡"):"A verified foreign-language song must not be announced as absent";
        // 곡 국적은 검증할 수 없어 지역으로 거르지 않는다. 지역 표현이 있어도 결과는 같고 안내만 덧붙는다.
        for(String[] pair:new String[][]{{"한국 팝","팝"},{"국내 노래","노래"},{"한국 재즈","재즈"}}) {
            Recommender.Result regional=Recommender.recommend(expanded,Recommender.parse(pair[0]),feedback,"openai");
            Recommender.Result plain=Recommender.recommend(expanded,Recommender.parse(pair[1]),feedback,"openai");
            assert regional.tracks.stream().map(pick->pick.track.id).toList().equals(plain.tracks.stream().map(pick->pick.track.id).toList()):pair[0];
            // 기존 안내는 순서 그대로이고, 미반영 안내는 맨 뒤에 붙는다. 0곡이어도 지역을 원인으로 말하지 않는다.
            assert regional.notice.equals((plain.notice+" "+Recommender.originNotice("KR")).trim()):regional.notice;
            assert !regional.notice.contains("지역(");
        }
        assert Recommender.recommend(expanded,Recommender.parse("한국 재즈"),feedback,"openai").notice.startsWith("요청한 장르 조건");
        assert Recommender.recommend(expanded,Recommender.parse("팝"),feedback,"openai").notice.indexOf("지역")<0;
        // 곡을 유지한 채 이어서 요청해도 미반영 안내는 맨 뒤에 한 번만 붙는다.
        String keptNotice=Conversation.recommend(expanded,Recommender.parse("한국 팝"),feedback,"openai",List.of(tracks.get(4).id),List.of()).notice;
        assert keptNotice.endsWith(Recommender.originNotice("KR"))&&keptNotice.indexOf(Recommender.originNotice("KR"))==keptNotice.lastIndexOf(Recommender.originNotice("KR")):keptNotice;
        // 연대 필터: 연도를 모르면 제외, 요청 연대 중 하나에 속하면 통과(OR), 다른 조건과는 AND.
        // 연대는 마지막에 보므로 장르로 먼저 빠진 곡(록 1997)은 연대 집계에 들어가지 않는다.
        List<Track> dated=new ArrayList<>();
        Object[][] years={{1995,"pop"},{2003,"pop"},{0,"pop"},{1988,"pop"},{1997,"rock"}};
        for(int i=0;i<years.length;i++){
            Track t=new Track();t.id=String.format("00000000-0000-0000-0000-%012d",100+i);t.title="Dated "+i;t.artist="Artist";t.language="en";
            t.source="MusicBrainz";t.license="CC0-1.0";t.aiUseAllowed=true;t.sourceUrl="https://musicbrainz.org/recording/"+t.id;
            t.releaseYear=(Integer)years[i][0];t.tags.add((String)years[i][1]);dated.add(t);
        }
        Recommender.Stats ninetiesStats=new Recommender.Stats();
        Recommender.Result nineties=Recommender.recommend(dated,Recommender.parse("90년대 팝"),feedback,"openai",Set.of(),ninetiesStats);
        assert nineties.tracks.size()==1&&nineties.tracks.get(0).track.releaseYear==1995:nineties.tracks.size();
        assert ninetiesStats.undatedExcluded==1&&ninetiesStats.outsideDecadeExcluded==2:ninetiesStats.undatedExcluded+"/"+ninetiesStats.outsideDecadeExcluded;
        Recommender.Result spanning=Recommender.recommend(dated,Recommender.parse("90~2000년대 팝"),feedback,"openai");
        assert spanning.tracks.stream().map(pick->pick.track.releaseYear).sorted().toList().equals(List.of(1995,2003));
        Recommender.Stats noDecade=new Recommender.Stats();
        assert Recommender.recommend(dated,Recommender.parse("팝"),feedback,"openai",Set.of(),noDecade).tracks.size()==4&&noDecade.undatedExcluded==0&&noDecade.outsideDecadeExcluded==0;
        // 0곡 안내 순서는 장르 → 언어 → 연대 → 일반이다.
        assert Recommender.recommend(dated,Recommender.parse("70년대 재즈"),feedback,"openai").notice.startsWith("요청한 장르 조건");
        assert Recommender.recommend(dated,Recommender.parse("70년대 일본어 노래"),feedback,"openai").notice.startsWith("요청한 언어 조건");
        assert Recommender.recommend(dated,Recommender.parse("70년대 노래"),feedback,"openai").notice.startsWith("요청한 연대(1970년대) 조건에 맞는 곡을"):"decade notice";
        // 연대만 있는 요청도 조건이 있는 요청으로 본다.
        assert !Recommender.recommend(dated,Recommender.parse("90년대 노래"),feedback,"openai").notice.startsWith("구체적인");
        String keptDecade=Conversation.recommend(dated,Recommender.parse("70년대 노래"),feedback,"openai",List.of(dated.get(0).id),List.of()).notice;
        assert keptDecade.contains("연대 조건에 맞는 새 곡을 확인하지 못했어요"):keptDecade;
        Recommender.Result keptForeign=Conversation.recommend(expanded,foreignConditions,feedback,"openai",List.of(tracks.get(4).id),List.of());
        assert keptForeign.tracks.size()==2&&keptForeign.notice.contains("합해 2곡")&&!keptForeign.notice.contains("새 추천을 찾지 못"):"Count kept and new foreign-language songs together";
        Recommender.Result absentForeign=Conversation.recommend(tracks,foreignConditions,feedback,"basic",List.of(tracks.get(4).id),List.of());
        assert absentForeign.tracks.size()==1&&absentForeign.notice.contains("새 추천을 찾지 못"):"Retain the missing-language notice when only a kept song remains";
        foreign.tags=List.of("genre:chanson");foreign.language="fr";
        Recommender.Conditions open=new Recommender.Conditions();open.tags.add("genre:chanson");open.language="fr";
        assert Recommender.recommend(expanded,open,feedback,"openai").tracks.size()==1;
        assert Recommender.recommend(expanded,open,feedback,"openai").tracks.get(0).reason.contains("chanson");
        foreign.language="ja";assert Recommender.recommend(expanded,open,feedback,"openai").tracks.isEmpty():"French and Japanese must never share one unknown-language bucket";
        foreign.language="fr-ca";assert Recommender.recommend(expanded,open,feedback,"openai").tracks.size()==1;
        open.excludedTags.add("genre:chanson");assert Recommender.recommend(expanded,open,feedback,"openai").tracks.isEmpty();
        assert Recommender.validTag("genre:hindustani_dhrupad")&&Recommender.validLanguage("sa")&&Recommender.validLanguage("zh-hant");
        assert !Recommender.validTag("genre:<script>")&&!Recommender.validTag("genre:"+"x".repeat(65))&&!Recommender.validLanguage("fr_ca");
        assert !Recommender.label("genre:gregorian_chant").isEmpty()&&!Recommender.languageLabel("fr").isEmpty();
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
        conditions.language = "not_a_language";
        try { Recommender.validate(conditions); throw new AssertionError("Unknown language accepted"); } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: catalogue source boundary, 8 picks, missing genre/kept boundaries, temporary replacement, vocal/unsupported language filters, dislike exclusion/undo, like ranking, negation, input validation and playlist text sharing.");
    }
}
