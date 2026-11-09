package com.mptaptap.core;
import java.util.List;
import java.util.ArrayList;
final class ConversationCheck {
    static void run() {
        Recommender.Conditions c=Conversation.parse(List.of("영어 잔잔한 피아노 음악"),"더 신나는 곡으로, 피아노 제외");
        assert c.language.equals("en") && c.tags.contains("energetic") && !c.tags.contains("calm") && !c.tags.contains("piano");
        assert c.excludedTags.contains("piano");
        Recommender.Conditions vocals=Conversation.parse(List.of("가사 없는 피아노"),"연주곡 제외");
        assert vocals.language.isEmpty()&&!vocals.tags.contains("instrumental")&&vocals.excludedTags.contains("instrumental");
        assert Conversation.parse(List.of("가사 없는 피아노"),"영어 팝으로 바꿔줘").tags.equals(List.of("pop"));
        assert Conversation.parse(List.of("가사 없는 피아노"),"영어 팝").language.equals("en");
        for(String request:List.of("팝","팝으로 바꿔줘","팝도 좋아","팝 음악","팝송"))assert Recommender.parse(request).tags.contains("pop"):request;
        for(String request:List.of("케이팝","k-pop","K pop","kpop"))assert Recommender.parse(request).tags.equals(List.of("kpop")):request;
        Recommender.Conditions jazz=Conversation.parse(List.of("가사 없는 피아노"),"재즈로 바꿔줘");
        assert jazz.tags.equals(List.of("instrumental","jazz"))&&jazz.language.equals("none");
        assert Conversation.parse(jazz,List.of(),"더 잔잔하게").tags.containsAll(List.of("instrumental","jazz","calm"));
        Recommender.Conditions noJazz=Conversation.parse(jazz,List.of(),"재즈 장르는 제외");
        assert !noJazz.tags.contains("jazz")&&noJazz.excludedTags.contains("jazz")&&noJazz.tags.contains("instrumental");
        assert Recommender.parse("no jazz").excludedTags.equals(List.of("jazz"));
        assert Recommender.parse("재즈만 빼고").excludedTags.equals(List.of("jazz"));
        assert Recommender.parse("잔잔한 곡은 제외").excludedTags.equals(List.of("calm"));
        assert Recommender.parse("따뜻한 곡은 제외").excludedTags.equals(List.of("warm"));
        assert Recommender.parse("행복한 곡은 제외").excludedTags.equals(List.of("bright"));
        for(String request:List.of("잔잔한 곡은 제외","따뜻한 곡은 제외","행복한 곡은 제외"))
            assert Recommender.isKnownConditionRequest(request)&&Recommender.parse(request).tags.isEmpty():request;
        Recommender.Conditions piano=Conversation.parse(jazz,List.of(),"피아노로 바꿔줘");
        assert piano.tags.contains("piano")&&!piano.tags.contains("jazz")&&jazz.tags.contains("jazz");
        assert Conversation.parse(noJazz,List.of(),"재즈로 바꿔줘").excludedTags.isEmpty();
        Recommender.Conditions unlisted=new Recommender.Conditions();unlisted.tags.add("unsupported_genre");
        Recommender.Conditions open=new Recommender.Conditions();open.tags.add("genre:chanson");open.excludedTags.add("genre:enka");open.language="fr";
        Recommender.Conditions warmer=Conversation.parse(open,List.of(),"더 따뜻하게");
        assert warmer.tags.containsAll(List.of("genre:chanson","warm"))&&warmer.excludedTags.equals(List.of("genre:enka"))&&warmer.language.equals("fr");
        assert Conversation.parse(warmer,List.of(),"팝으로 바꿔줘").tags.equals(List.of("warm","pop"));
        assert Conversation.parse(warmer,List.of(),"일본어로 바꿔줘").language.equals("ja");
        assert Conversation.parse(warmer,List.of(),"처음부터 재즈").tags.equals(List.of("jazz"));
        assert Recommender.parse("일본어 말고 프랑스어").language.equals("fr");
        assert Recommender.parse("프랑스어 말고 일본어").language.equals("ja");
        assert Recommender.parse("영어와 일본어 제외하고 프랑스어").language.equals("fr");
        assert Recommender.parse("exclude Japanese and English, but French").language.equals("fr");
        assert Recommender.parse("not French or German, but Japanese").language.equals("ja");
        assert Recommender.parse("English, French").language.equals("fr")&&Recommender.ambiguousLanguage("English, French");
        assert Conversation.parse(open,List.of(),"프랑스어는 제외").language.isEmpty();
        assert Conversation.parse(open,List.of(),"일본어는 제외").language.equals("fr");
        assert Conversation.parse(open,List.of(),"처음부터 영어 말고 일본어").language.equals("ja");
        assert Conversation.parse(unlisted,List.of(),"더 따뜻하게").tags.contains("unsupported_genre");
        assert Conversation.parse(unlisted,List.of(),"팝으로 바꿔줘").tags.equals(List.of("pop"));
        Recommender.Conditions excludedUnlisted=new Recommender.Conditions();excludedUnlisted.excludedTags.add("unsupported_genre");
        assert Conversation.parse(excludedUnlisted,List.of(),"피아노").tags.equals(List.of("piano"));
        assert Conversation.parse(unlisted,List.of(),"처음부터 재즈").tags.equals(List.of("jazz"));
        for(String request:List.of("팝","팝으로 바꿔줘","처음부터 팝","재즈만 추천해줘","클래식 음악","recommend jazz","재즈 말고 피아노",
                "신나는 음악을 추천해줘","잔잔한 음악","공부할 때 가사 없는 피아노","잔잔한 팝","더 따뜻하게","비 오는 날 밤에"))
            assert Recommender.isKnownConditionRequest(request):request;
        for(String request:List.of("미국 팝","미국 음악","영국 팝","팝과 Gregorian chant","팝 음악과 Gregorian chant","한국어 팝","English pop","영어 팝",
                "덜 신나는 팝","덜신나는팝","신나는 음악에 무작위unknown 추가","더 알려줘"))
            assert !Recommender.isKnownConditionRequest(request):request;
        assert Conversation.parse(unlisted,List.of(),"팝으로 바꿔줘").language.isEmpty();
        Recommender.Conditions englishGenre=Conversation.parse(null,List.of("영어 피아노"),"팝으로 바꿔줘");
        assert englishGenre.language.equals("en")&&englishGenre.tags.equals(List.of("pop"));
        assert Conversation.parse(englishGenre,List.of(),"처음부터 팝").language.isEmpty();
        assert Conversation.parse(List.of("한국어 록"),"처음부터 영어 팝").language.equals("en");
        assert !Conversation.parse(List.of("한국어 록"),"처음부터 영어 팝").tags.contains("rock");
        Recommender.Conditions snapshot=Conversation.parse(List.of(),"영어 잔잔한 팝");List<String> recent=new ArrayList<>(List.of("영어 잔잔한 팝"));
        for(int i=0;i<20;i++){
            String prompt=i%2==0?"비 오는 날에":"밤에도";List<String> before=new ArrayList<>(snapshot.tags);
            Recommender.Conditions next=Conversation.parse(snapshot,recent,prompt);assert snapshot.tags.equals(before):"Merging must not mutate the previous result";
            snapshot=next;recent.add(prompt);while(recent.size()>8)recent.remove(0);
        }
        assert snapshot.language.equals("en")&&snapshot.tags.containsAll(List.of("calm","pop","rain","night")):"Initial conditions must survive a bounded history";
        assert Conversation.parse(snapshot,List.of("한국어 록"),"조건 유지").language.equals("en"):"A complete snapshot must not replay old requests";
        Recommender.Conditions reset=Conversation.parse(snapshot,recent,"처음부터 한국어 록");
        assert reset.language.equals("ko")&&reset.tags.equals(List.of("rock"))&&reset.excludedTags.isEmpty();
        Recommender.Conditions previous=Conversation.parse(List.of(),"가사 없는 피아노");
        assert Conversation.parse(previous,List.of(),"연주곡 제외").language.isEmpty();assert previous.language.equals("none")&&previous.tags.contains("instrumental");
        Recommender.Conditions invalid=new Recommender.Conditions();invalid.tags.add("external song metadata");
        try {Conversation.parse(invalid,List.of(),"팝");throw new AssertionError("Unsupported snapshot values accepted");}catch(IllegalArgumentException expected) { }
        invalid.tags.clear();invalid.language="not_a_language";
        try {Conversation.parse(invalid,List.of(),"팝");throw new AssertionError("Unsupported snapshot language accepted");}catch(IllegalArgumentException expected) { }
        String[][] newGenres={{"indie","indie"},{"folk","folk"},{"soul","soul"},{"funk","funk"},{"disco","disco"},
                {"citypop","city pop"},{"lofi","lo-fi"},{"jpop","j-pop"},{"synthpop","synth pop"},{"dreampop","dream pop"},
                {"ambient","ambient"},{"house","house"},{"techno","techno"},{"trance","trance"},{"punk","punk"},
                {"reggae","reggae"},{"bossa_nova","bossa nova"},{"latin","latin"},{"country","country"},{"soundtrack","soundtrack"},{"gospel","gospel"},{"trap","trap"}};
        for(String[] genre:newGenres) {
            Recommender.Conditions positive=Recommender.parse(genre[1]),negative=Recommender.parse("exclude "+genre[1]);
            assert positive.tags.equals(List.of(genre[0]))&&positive.excludedTags.isEmpty():genre[1];
            assert negative.tags.isEmpty()&&negative.excludedTags.equals(List.of(genre[0])):genre[1];
            assert Recommender.isKnownConditionRequest(genre[1])&&Recommender.isKnownConditionRequest("exclude "+genre[1]):genre[1];
        }
        String[][] compounds={{"citypop","city pop","city-pop"},{"jpop","j pop","j-pop"},
                {"synthpop","synth pop","synth-pop"},{"dreampop","dream pop","dream-pop"},
                {"punk","punk rock","punk-rock","punkrock","펑크록"},{"lofi","lo-fi hip hop","lofi hiphop","lofi hip-hop"}};
        for(String[] compound:compounds)for(int i=1;i<compound.length;i++) {
            String request=compound[i];Recommender.Conditions positive=Recommender.parse(request),negative=Recommender.parse("without "+request);
            assert positive.tags.equals(List.of(compound[0]))&&positive.excludedTags.isEmpty():request;
            assert negative.tags.isEmpty()&&negative.excludedTags.equals(List.of(compound[0])):request;
            assert Recommender.isKnownConditionRequest(request)&&Recommender.isKnownConditionRequest("without "+request):request;
        }
        assert Recommender.parse("훵크").tags.equals(List.of("funk"))&&Recommender.parse("펑크").tags.equals(List.of("punk"));
        assert Recommender.parse("pop").tags.equals(List.of("pop"))&&Recommender.parse("rock").tags.equals(List.of("rock"));
        assert Recommender.parse("J-pop").language.isEmpty()&&Recommender.parse("without J-pop").language.isEmpty();
        Recommender.Conditions jpop=Conversation.parse(englishGenre,List.of(),"제이팝으로 바꿔줘");
        assert jpop.tags.equals(List.of("jpop"))&&jpop.language.equals("en");
        assert Recommender.parse("일본어 J-pop").language.equals("ja");
        Recommender.Conditions expanded=Conversation.parse(englishGenre,List.of(),"시티팝으로 바꿔줘");
        Recommender.Conditions expandedExcluded=Conversation.parse(expanded,List.of(),"without synth pop");
        assert expandedExcluded.tags.equals(List.of("citypop"))&&expandedExcluded.excludedTags.equals(List.of("synthpop"))&&expandedExcluded.language.equals("en");
        Recommender.Conditions changed=Conversation.parse(expandedExcluded,List.of(),"신스팝으로 바꿔줘");
        assert changed.tags.equals(List.of("synthpop"))&&changed.excludedTags.isEmpty()&&changed.language.equals("en");
        Recommender.Conditions expandedReset=Conversation.parse(expandedExcluded,List.of(),"처음부터 레게");
        assert expandedReset.tags.equals(List.of("reggae"))&&expandedReset.excludedTags.isEmpty()&&expandedReset.language.isEmpty();
        List<Track> genreTracks=new ArrayList<>();String[] filterGenres={"citypop","pop","punk","rock"};
        for(int i=0;i<filterGenres.length;i++) {
            Track t=new Track();t.id=String.format("00000000-0000-0000-0000-%012d",i);t.title=filterGenres[i];t.artist="Artist";
            t.tags.add(filterGenres[i]);t.source="MusicBrainz";t.license="CC0-1.0";t.aiUseAllowed=true;t.sourceUrl="https://musicbrainz.org/recording/"+t.id;genreTracks.add(t);
        }
        for(String[] filter:new String[][]{{"city pop","citypop"},{"pop","pop"},{"punk rock","punk"},{"rock","rock"}}) {
            Recommender.Result filtered=Recommender.recommend(genreTracks,Recommender.parse(filter[0]),java.util.Map.of(),"basic");
            assert filtered.tracks.size()==1&&filtered.tracks.get(0).track.tags.equals(List.of(filter[1])):filter[0];
        }
        Recommender.Result excludedCity=Recommender.recommend(genreTracks,Recommender.parse("without city pop"),java.util.Map.of(),"basic");
        assert excludedCity.tracks.size()==3&&excludedCity.tracks.stream().noneMatch(p->p.track.tags.contains("citypop"));
        try { Conversation.requests(java.util.Collections.nCopies(9,"피아노"),"팝"); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { }
    }
}
