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
        // "처지"까지만 읽으면 꼬리 "지 않게"가 부정으로 읽히지 않아 sad 가 오히려 요청된다.
        for(String request:List.of("너무 처지지 않게 해줘","처지는 노래는 싫어")) {
            Recommender.Conditions upbeat=Recommender.parse(request);
            assert upbeat.excludedTags.equals(List.of("sad"))&&!upbeat.tags.contains("sad"):request;
        }
        assert Recommender.parse("축 처져 있을 때 듣는 노래").tags.contains("sad");
        // 곡 이름 "밤편지"는 시간대 조건이 아니라 사전이 못 읽는 표현으로 남아 AI 해석에 맡겨진다.
        Recommender.Conditions letter=Recommender.parse("밤편지 같은 느낌으로 바꿔줘");
        assert !letter.tags.contains("night")&&letter.unread.contains("밤편지")&&!Recommender.isKnownConditionRequest("밤편지 같은 느낌으로 바꿔줘");
        for(String request:List.of("밤에 듣는 노래","밤 노래","한밤중에","밤하늘 보면서","잔잔한 밤","night drive"))
            assert Recommender.parse(request).tags.contains("night"):request;
        // 참고 곡에서 추론한 장르는 선호다. 명시한 제외와 겹치는 추론은 버리고, 직접 말하면 필수가 된다.
        Recommender.Conditions like=new Recommender.Conditions();like.tags.addAll(List.of("ballad","sad","calm"));
        like.inferredTags.addAll(List.of("ballad","sad","calm"));like.excludedTags.add("calm");Recommender.validate(like);
        assert like.hardTags.isEmpty()&&like.softTags.contains("ballad")&&like.inferredTags.equals(List.of("ballad","sad")):like.inferredTags;
        Recommender.Conditions livelier=Conversation.parse(like,List.of(),"더 신나게");
        assert livelier.inferredTags.equals(List.of("ballad"))&&livelier.hardTags.isEmpty()&&livelier.tags.contains("ballad"):livelier.inferredTags;
        Recommender.Conditions direct=Conversation.parse(like,List.of(),"발라드로 바꿔줘");
        assert direct.hardTags.equals(List.of("ballad"))&&!direct.inferredTags.contains("ballad"):direct.hardTags;
        // "한국 노래·한국곡·일본 노래"는 언어로 잡히고 지역 안내 대상이 아니다.
        for(String[] spoken:new String[][]{{"한국 노래","ko"},{"한국곡","ko"},{"일본 노래","ja"},{"한국어 노래","ko"},{"korean songs","ko"},{"일본어 노래","ja"}}) {
            Recommender.Conditions said=Recommender.parse(spoken[0]);
            assert said.language.equals(spoken[1])&&said.origin.isEmpty():spoken[0];
        }
        // 언어로 잡히지 않는 국적 표현만 "요청했지만 반영하지 않은 지역"으로 표시한다.
        for(String request:List.of("한국 발라드","한국 발라드곡","국내 노래")) {
            Recommender.Conditions regional=Recommender.parse(request);
            assert regional.origin.equals("KR")&&regional.language.isEmpty()&&regional.unread.isEmpty():request;
        }
        assert Recommender.parse("일본 시티팝").origin.equals("JP")&&Recommender.parse("일본 시티팝").language.isEmpty();
        // 가사 없음은 언어만 none 으로 바꾸고 지역 표시는 남긴다.
        Recommender.Conditions quiet=Recommender.parse("가사 없는 한국 발라드");
        assert quiet.language.equals("none")&&quiet.origin.equals("KR")&&quiet.tags.containsAll(List.of("ballad","instrumental"));
        assert Conversation.parse(quiet,List.of(),"더 신나게").origin.equals("KR");
        assert Conversation.parse(quiet,List.of(),"일본 시티팝으로 바꿔줘").origin.equals("JP");
        assert Conversation.parse(quiet,List.of(),"한국 노래 말고").origin.isEmpty();
        assert Conversation.parse(quiet,List.of(),"지역 상관없이").origin.isEmpty();
        assert Conversation.parse(quiet,List.of(),"처음부터 팝").origin.isEmpty();
        assert Conversation.copyConditions(quiet).origin.equals("KR");
        Recommender.Conditions lower=new Recommender.Conditions();lower.origin="kr";assert Recommender.validate(lower).origin.equals("KR");
        Recommender.Conditions bogus=new Recommender.Conditions();bogus.origin="Korea";
        try{Recommender.validate(bogus);assert false:"invalid origin";}catch(IllegalArgumentException expected){}
        assert Recommender.originLabel("KR").equals("한국")&&Recommender.originLabel("JP").equals("일본")&&Recommender.originLabel("US").equals("미국");
        assert Recommender.originLabel("1").equals("1"):"An invalid region code is shown as is";
        assert Recommender.originNotice("KR").equals("지역 조건(한국)은 반영되지 않았어요.");
        assert Recommender.originNotice("JP").equals("지역 조건(일본)은 반영되지 않았어요.");
        // 연대는 직접 말한 표현만 읽는다. 범위와 나열은 그중 하나에만 속하면 통과하는 목록이 된다.
        Object[][] decadeCases={{"90년대 발라드",List.of(1990)},{"1990년대 노래",List.of(1990)},{"80년대 팝",List.of(1980)},{"50년대 재즈",List.of(1950)},
                {"2000년대 발라드",List.of(2000)},{"00년대 노래",List.of(2000)},{"10년대 노래",List.of(2010)},{"2020년대 팝",List.of(2020)},
                {"90s pop",List.of(1990)},{"1990s rock",List.of(1990)},{"2000s",List.of(2000)},
                {"90~2000년대 노래",List.of(1990,2000)},{"80~2000년대 노래",List.of(1980,1990,2000)},{"90년대~2000년대 노래",List.of(1990,2000)},
                {"90년대와 2000년대 노래",List.of(1990,2000)},{"2000년대, 90년대",List.of(1990,2000)},{"90년대 후반 발라드",List.of(1990)}};
        for(Object[] decadeCase:decadeCases){
            Recommender.Conditions dated=Recommender.parse((String)decadeCase[0]);
            assert dated.decades.equals(decadeCase[1])&&dated.unread.isEmpty():decadeCase[0]+" "+dated.decades+" "+dated.unread;
        }
        for(String request:List.of("옛날 노래","레트로 감성","요즘 노래","최신곡","2017년 노래","30년대 노래","1995년대 노래"))
            assert Recommender.parse(request).decades.isEmpty():request;
        Recommender.Conditions scenario=Recommender.parse("1990년대 한국 발라드 중에 가사 없는 곡만");
        assert scenario.decades.equals(List.of(1990))&&scenario.origin.equals("KR")&&scenario.language.equals("none")&&scenario.tags.containsAll(List.of("ballad","instrumental"));
        // 이어받기: 유지, 덮어쓰기, "말고"·"상관없이" 해제, 처음부터 초기화.
        Recommender.Conditions nineties=Recommender.parse("90년대 발라드");
        assert Conversation.parse(nineties,List.of(),"더 신나게").decades.equals(List.of(1990));
        assert Conversation.parse(nineties,List.of(),"2000년대로 바꿔줘").decades.equals(List.of(2000));
        assert Conversation.parse(nineties,List.of(),"90년대 말고").decades.isEmpty();
        assert Conversation.parse(nineties,List.of(),"연대 상관없이").decades.isEmpty();
        assert Conversation.parse(nineties,List.of(),"처음부터 팝").decades.isEmpty();
        assert Conversation.copyConditions(nineties).decades.equals(List.of(1990));
        assert Conversation.parse(List.of("90년대 발라드"),"더 잔잔하게").decades.equals(List.of(1990));
        Recommender.Conditions unordered=new Recommender.Conditions();unordered.decades.addAll(List.of(2000,1990,1990));
        assert Recommender.validate(unordered).decades.equals(List.of(1990,2000));
        for(List<Integer> invalid:List.of(List.of(1995),List.of(1890),List.of(2100),List.of(1900,1910,1920,1930,1940,1950,1960,1970,1980))) {
            Recommender.Conditions wrong=new Recommender.Conditions();wrong.decades.addAll(invalid);
            try{Recommender.validate(wrong);assert false:invalid;}catch(IllegalArgumentException expected){}
        }
        assert Recommender.decadeLabel(List.of(1990,2000)).equals("1990년대, 2000년대");
        // 발매 연도 판정은 ReleaseYear 한곳에 모여 있다.
        assert ReleaseYear.of("2017-05-15",null)==2017&&ReleaseYear.of("1999",List.of())==1999&&ReleaseYear.of("2001-03",null)==2001;
        assert ReleaseYear.of(null,List.of("2005-01-01","1998","bad"))==1998&&ReleaseYear.of("",java.util.Arrays.asList("",null))==ReleaseYear.UNKNOWN;
        assert ReleaseYear.of("abcd",null)==ReleaseYear.UNKNOWN&&ReleaseYear.of("0000",null)==ReleaseYear.UNKNOWN;
        assert ReleaseYear.judge(0,List.of(1990))==ReleaseYear.Verdict.UNDATED&&ReleaseYear.judge(1999,List.of(1990))==ReleaseYear.Verdict.MATCH
                &&ReleaseYear.judge(2000,List.of(1990))==ReleaseYear.Verdict.OUTSIDE&&ReleaseYear.judge(2003,List.of(1990,2000))==ReleaseYear.Verdict.MATCH;
        Recommender.Conditions ballad=new Recommender.Conditions();ballad.tags.add("ballad");
        Recommender.Conditions lifted=Conversation.parse(ballad,List.of(),"너무 처지지 않게 해줘");
        assert lifted.tags.equals(List.of("ballad"))&&lifted.excludedTags.equals(List.of("sad"));
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
