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
        invalid.tags.clear();invalid.language="fr";
        try {Conversation.parse(invalid,List.of(),"팝");throw new AssertionError("Unsupported snapshot language accepted");}catch(IllegalArgumentException expected) { }
        try { Conversation.requests(java.util.Collections.nCopies(9,"피아노"),"팝"); throw new AssertionError(); }
        catch(IllegalArgumentException expected) { }
    }
}
