package com.mptaptap.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class TrackReferenceCheck {
    private static final List<String> IDS = List.of("a", "b", "c", "d");

    private static TrackReference.Result read(String request) { return TrackReference.parse(request, IDS, 2, Map.of(), List.of()); }

    // 태그 순서는 조건이 아니다. 같은 요청을 다시 적용하면 순서만 바뀔 수 있다("1990년대 한국 발라드 중에 가사 없는 곡만").
    private static void same(Recommender.Conditions left, Recommender.Conditions right, String request) {
        assert Set.copyOf(left.tags).equals(Set.copyOf(right.tags)) && Set.copyOf(left.excludedTags).equals(Set.copyOf(right.excludedTags))
                && left.language.equals(right.language) && left.decades.equals(right.decades) && left.origin.equals(right.origin)
                && Set.copyOf(left.inferredTags).equals(Set.copyOf(right.inferredTags)) && left.excludedArtists.equals(right.excludedArtists) : request + " " + left.tags + left.excludedTags + " / " + right.tags + right.excludedTags;
    }

    static void run() {
        // 1-3: 곡 지정만 있는 문장은 남는 문장이 없다.
        TrackReference.Result keep = read("이번 추천의 첫 곡은 다음 추천에 남겨줘");
        assert keep.error == null && keep.kept.equals(List.of("a")) && keep.excluded.isEmpty() && keep.remaining.isEmpty() : keep.remaining;
        // 3-3: 지금 보던 카드(2번 위치)를 뺀다.
        TrackReference.Result now = read("이번 추천에서만 지금 듣는 곡 빼줘");
        assert now.error == null && now.excluded.equals(List.of("c")) && now.kept.isEmpty() && now.remaining.isEmpty() : now.remaining;
        assert read("듣고 있는 곡은 제외해 주세요").excluded.equals(List.of("c"));
        // 1-2: "빼줘"는 가리키는 말 바로 뒤가 아니므로 뒤 조건에 붙는다.
        TrackReference.Result mixed = read("다음 추천에서 첫 곡은 포함하고 가사 없는 곡은 빼줘");
        assert mixed.error == null && mixed.kept.equals(List.of("a")) && mixed.excluded.isEmpty() && mixed.remaining.equals("가사 없는 곡은 빼줘") : mixed.remaining;
        // 4-2
        TrackReference.Result second = read("두 번째 곡 빼줘");
        assert second.error == null && second.excluded.equals(List.of("b")) && second.remaining.isEmpty();
        TrackReference.Result last = read("마지막 곡은 유지하고, 더 신나게");
        assert last.error == null && last.kept.equals(List.of("d")) && last.remaining.equals("더 신나게") : last.remaining;
        TrackReference.Result both = read("첫 곡은 남기고 세 번째 곡은 빼고 재즈로 바꿔줘");
        assert both.error == null && both.kept.equals(List.of("a")) && both.excluded.equals(List.of("c")) && both.remaining.equals("재즈로 바꿔줘") : both.remaining;
        // 곡 지정이 없는 문장은 그대로 둔다.
        TrackReference.Result plain = read("아무튼 다시 추천해줘");
        assert plain.error == null && !plain.referenced() && plain.remaining.equals("아무튼 다시 추천해줘");
        assert read("가사 없는 곡은 빼줘").remaining.equals("가사 없는 곡은 빼줘") && !read("가사 없는 곡은 빼줘").referenced();

        // 안내로 넘기는 경우
        assert read("다섯 번째 곡 빼줘").error.equals(TrackReference.missing(5));
        assert TrackReference.parse("지금 듣는 곡 빼줘", IDS, -1, Map.of(), List.of()).error.equals(TrackReference.VAGUE);
        assert read("첫 곡 같은 느낌으로").error.equals(TrackReference.NO_VERB);
        assert read("첫 곡은 빼지 말고 더 신나게").error.equals(TrackReference.NEGATED);
        assert read("첫 곡은 안 빼도 돼").error.equals(TrackReference.NEGATED);
        assert read("첫 곡이랑 재즈 빼줘").error.equals(TrackReference.COMBINED);
        assert read("첫 곡, 두 번째 곡 빼줘").error.equals(TrackReference.COMBINED);
        for (String vague : List.of("이 곡 빼줘", "그 곡은 남겨줘", "아까 그 곡 빼줘", "방금 들은 곡 남겨줘"))
            assert read(vague).error.equals(TrackReference.VAGUE) : vague;
        assert read("첫 곡 싫어").error.equals(TrackReference.DISLIKE);
        assert read("첫 곡은 남기고 첫 번째 곡은 빼줘").error.equals(TrackReference.CONFLICT);
        assert TrackReference.parse("첫 곡 빼줘", IDS, 0, Map.of(), List.of("a")).error.equals(TrackReference.CONFLICT);
        assert TrackReference.parse("첫 곡 남겨줘", IDS, 0, Map.of("a", -1), List.of()).error.equals(TrackReference.DISLIKED);
        // 실패하면 문장과 선택을 건드리지 않는다.
        TrackReference.Result failed = read("다섯 번째 곡 빼줘");
        assert failed.remaining.equals("다섯 번째 곡 빼줘") && !failed.referenced();

        // 메인 입력란은 동사까지 갖춘 곡 지정만 안내한다.
        assert TrackReference.mentions("두 번째 곡 빼줘") && TrackReference.mentions("지금 듣는 곡은 남겨줘");
        assert !TrackReference.mentions("콘서트 첫 곡으로 어울리는 노래") && !TrackReference.mentions("새벽에 과제하는데 너무 조용하면 졸려");

        // 떼어 낸 문장의 조건은 원래 문장과 같다. 가리키는 말만 사라진다.
        Recommender.Conditions previous = Conversation.parse(List.of(), "공부할 때 들을 거 가사 없는 피아노곡");
        for (String request : List.of("다음 추천에서 첫 곡은 포함하고 가사 없는 곡은 빼줘", "첫 곡은 남기고 세 번째 곡은 빼고 재즈로 바꿔줘", "마지막 곡은 유지하고, 더 신나게")) {
            String rest = read(request).remaining;
            Recommender.Conditions original = Conversation.parse(previous, List.of("공부할 때 들을 거 가사 없는 피아노곡"), request);
            Recommender.Conditions stripped = Conversation.parse(previous, List.of("공부할 때 들을 거 가사 없는 피아노곡"), rest);
            same(original, stripped, request);
            for (String word : List.of("첫", "세", "마지막", "남기고", "유지하고", "포함하고")) assert !stripped.unread.contains(word) : request + " " + stripped.unread;
        }

        // 남기기만 한 문장은 마지막 요청을 다시 보낸다. 조건 스냅샷에 이미 반영된 요청이라 조건이 바뀌지 않는다.
        List<List<String>> scenarios = List.of(
                List.of("공부할 때 들을 거 가사 없는 피아노곡", "재즈 빼줘"),
                List.of("한국어 가사 노래로 찾아줘 잔잔한 건 싫어", "밤편지 같은 느낌으로 바꿔줘"),
                List.of("2000년대 한국 발라드곡", "너무 처지지 않게 해줘"),
                List.of("새벽에 과제하는데 너무 조용하면 졸려"),
                List.of("1990년대 한국 발라드 중에 가사 없는 곡만"),
                List.of("신나는 곡", "처음부터 잔잔한 피아노"));
        for (List<String> turns : scenarios) {
            Recommender.Conditions snapshot = null;
            for (int i = 0; i < turns.size(); i++) snapshot = Conversation.parse(snapshot, turns.subList(0, i), turns.get(i));
            String lastRequest = turns.get(turns.size() - 1);
            same(snapshot, Conversation.parse(snapshot, turns, lastRequest), lastRequest);
        }
    }
}
