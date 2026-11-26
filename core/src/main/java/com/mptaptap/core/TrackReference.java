package com.mptaptap.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "첫 곡은 남겨줘"처럼 이번 추천의 곡을 가리키는 말을 곡 id로 바꾼다.
 * 가리키는 말은 문장에서 떼어 내고 나머지만 AI에게 보낸다. AI는 화면 순서를 모르기 때문이다.
 * 곡을 가리키는 말 바로 뒤(조사와 "다음 추천에" 같은 범위 표현만 사이에 허용)에 동사가 와야 그 곡에 대한 요청으로 읽는다.
 */
public final class TrackReference {
    private TrackReference() {}

    public static final class Result {
        /** 가리키는 말을 뗀 문장. 곡 지정만 있었다면 빈 문자열이다. */
        public final String remaining;
        public final List<String> kept, excluded;
        /** 해석할 수 없을 때의 안내. null이면 해석 성공이다. */
        public final String error;
        Result(String remaining, List<String> kept, List<String> excluded, String error) {
            this.remaining = remaining; this.kept = kept; this.excluded = excluded; this.error = error;
        }
        public boolean referenced() { return !kept.isEmpty() || !excluded.isEmpty(); }
    }

    public static final String MAIN_NOTICE = "곡 지정은 이어서 요청하기에서 할 수 있어요.";
    static final String VAGUE = "어느 곡인지 알 수 없어요. '첫 곡', '지금 듣는 곡'처럼 말하거나 아래에서 유지할 곡을 선택해 주세요.";
    static final String NO_VERB = "그 곡을 남길지 뺄지 알 수 없어요. 유지할 곡은 아래에서 선택하고, 뺄 곡은 카드 메뉴의 '이번 추천에서 제외'를 써 주세요.";
    static final String NEGATED = "곡을 빼지 않거나 남기지 않는 요청은 문장으로 처리하지 않아요. 유지할 곡은 아래에서 선택해 주세요.";
    static final String COMBINED = "곡 지정과 다른 조건은 나눠서 말해 주세요. 예: 첫 곡은 남겨줘, 재즈는 빼줘";
    static final String DISLIKE = "곡이 싫다면 카드의 싫어요 버튼으로 알려 주세요. 다음 추천에도 반영돼요.";
    static final String CONFLICT = "같은 곡을 남기면서 뺄 수는 없어요. 아래 선택과 문장을 다시 확인해 주세요.";
    static final String DISLIKED = "싫어요를 남긴 곡은 유지할 수 없어요. 반응을 취소한 뒤 선택해 주세요.";
    static String missing(int position) { return position + "번째 곡은 이번 추천에 없어요. 유지할 곡은 아래에서 선택해 주세요."; }

    private static final String SCOPE = "(?:(?:이번|다음)\\s*추천(?:에서만|에서도|에서는|에서|에도|에는|에|의)?\\s*)";
    private static final Pattern TARGET = Pattern.compile(SCOPE + "*(?:"
            + "(첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열|[1-9][0-9]?)\\s*(?:번째|번)\\s*곡"
            + "|(첫)\\s*곡"
            + "|(마지막)\\s*(?:번째\\s*)?곡"
            + "|((?:지금|현재)\\s*(?:듣는|듣고\\s*있는|나오는|재생\\s*중인|보는|보고\\s*있는)?|듣고\\s*있는)\\s*곡)");
    private static final Pattern DEMONSTRATIVE = Pattern.compile("(?<![가-힣])(?:(?:이|그|저|앞|뒤)\\s*곡|(?:아까|방금)(?:\\s*[가-힣]+){0,2}?\\s*곡)");
    private static final String PARTICLE = "(?:은|는|을|를|도|만|이|가)?\\s*";
    private static final String KEEP = "남겨|남기|유지|포함|살려|살리|그대로\\s*둬|그대로\\s*두", EXCLUDE = "빼|제외|없애|지워|지우";
    private static final String ENDING = "(?:해|하고|시켜|시키고)?(?:\\s*(?:주세요|줘요|줘|줄래|주고|주면서|요))?(?:고)?(?=$|[\\s.,!?~])";
    private static final Pattern COORDINATED = Pattern.compile("^(?:이랑|랑|하고|와|과|및|\\s*,)");
    private static final Pattern DISLIKED_VERB = Pattern.compile("^" + PARTICLE + "(?:정말\\s*|너무\\s*|진짜\\s*)?싫");
    private static final Pattern NEGATED_VERB = Pattern.compile("^" + PARTICLE + SCOPE + "?(?:(?:안|못)\\s*(?:" + KEEP + "|" + EXCLUDE + ")"
            + "|(?:" + KEEP + "|" + EXCLUDE + ")(?:하|시키)?지\\s*(?:마|말))");
    private static final Pattern VERB = Pattern.compile("^" + PARTICLE + SCOPE + "?(?:(" + KEEP + ")|(" + EXCLUDE + "))" + ENDING);

    /** 메인 입력란용. 남기기·빼기 동사까지 갖춘 곡 지정이 있는가. "콘서트 첫 곡으로 어울리는 노래"는 아니다. */
    public static boolean mentions(String request) {
        if (request == null) return false;
        Matcher target = TARGET.matcher(request);
        while (target.find()) if (VERB.matcher(request.substring(target.end())).find()) return true;
        return false;
    }

    /**
     * @param trackIds 대화상자를 열 때 고정한 화면 순서
     * @param current 그때 보던 카드 위치. 재생기는 모달이라 "지금 듣는 곡"은 보던 카드와 같다.
     * @param checked 체크박스로 유지하기로 한 곡
     */
    public static Result parse(String request, List<String> trackIds, int current, Map<String,Integer> feedback, Collection<String> checked) {
        String text = request == null ? "" : request;
        List<String> kept = new ArrayList<>(), excluded = new ArrayList<>();
        boolean[] removed = new boolean[text.length()];
        Matcher target = TARGET.matcher(text);
        boolean found = false;
        while (target.find()) {
            found = true;
            String tail = text.substring(target.end());
            if (COORDINATED.matcher(tail).find()) return failed(text, COMBINED);
            if (DISLIKED_VERB.matcher(tail).find()) return failed(text, DISLIKE);
            if (NEGATED_VERB.matcher(tail).find()) return failed(text, NEGATED);
            Matcher verb = VERB.matcher(tail);
            if (!verb.find()) return failed(text, NO_VERB);
            int index;
            if (target.group(1) != null || target.group(2) != null) {
                int position = position(target.group(1) != null ? target.group(1) : target.group(2));
                if (position > trackIds.size()) return failed(text, missing(position));
                index = position - 1;
            } else if (target.group(3) != null) index = trackIds.size() - 1;
            else index = current;
            if (index < 0 || index >= trackIds.size()) return failed(text, VAGUE);
            String id = trackIds.get(index);
            List<String> into = verb.group(1) != null ? kept : excluded, other = into == kept ? excluded : kept;
            if (other.contains(id)) return failed(text, CONFLICT);
            if (!into.contains(id)) into.add(id);
            for (int i = target.start(); i < target.end() + verb.end(); i++) removed[i] = true;
        }
        if (!found && DEMONSTRATIVE.matcher(text).find()) return failed(text, VAGUE);
        Collection<String> boxes = checked == null ? List.of() : checked;
        for (String id : excluded) if (boxes.contains(id)) return failed(text, CONFLICT);
        for (String id : kept) if (feedback != null && feedback.getOrDefault(id, 0) == -1) return failed(text, DISLIKED);
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < text.length(); i++) rest.append(removed[i] ? ' ' : text.charAt(i));
        String remaining = rest.toString().replaceAll("\\s+", " ").replaceAll("^[\\s,.]+|[\\s,]+$", "");
        return new Result(remaining, kept, excluded, null);
    }

    private static Result failed(String text, String error) { return new Result(text, List.of(), List.of(), error); }

    private static int position(String word) {
        List<String> words = List.of("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열");
        int spoken = words.indexOf(word);
        return spoken >= 0 ? spoken + 1 : Integer.parseInt(word);
    }
}
