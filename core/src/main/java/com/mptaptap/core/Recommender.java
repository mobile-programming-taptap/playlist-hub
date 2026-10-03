package com.mptaptap.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent CC0 catalogue + explicit in-app feedback only. */
public final class Recommender {
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, String> WORDS = new LinkedHashMap<>();
    static {
        String[][] vocabulary = {
            {"calm", "잔잔한", "잔잔|차분|편안|조용|calm|relax|chill"},
            {"bright", "밝은", "밝은|밝게|기분 전환|행복|bright|happy"},
            {"energetic", "신나는", "신나|신나는|신나는 곡|활기|에너지|energetic|upbeat"},
            {"warm", "따뜻한", "따뜻|포근|위로|warm|comfort"},
            {"sad", "감성적인", "슬픈|슬프|우울|이별|감성|sad"},
            {"acoustic", "어쿠스틱", "어쿠스틱|acoustic"},
            {"ballad", "발라드", "발라드|ballad"},
            {"kpop", "K-pop", "케이팝|k[ -]?pop|아이돌"},
            {"pop", "팝", "(?<!k[ -])(?<![a-z-])pop|팝송|팝 음악"},
            {"rock", "록", "락|록|rock"},
            {"piano", "피아노", "피아노|piano"},
            {"instrumental", "가사 없는", "가사\\s*없는|가사\\s*없이|보컬\\s*없는|연주곡|instrumental|no vocals"},
            {"study", "공부할 때", "공부|집중|작업|독서|study|focus"},
            {"rain", "비 오는 날", "비\\s*오는|비\\s*올|빗소리|rain"},
            {"night", "밤에", "밤|새벽|자기 전|잠들|night|sleep"},
            {"drive", "드라이브할 때", "드라이브|운전|여행|drive|driving"},
            {"workout", "운동할 때", "운동|러닝|달리기|workout|running"}
        };
        for (String[] word : vocabulary) {
            LABELS.put(word[0], word[1]);
            WORDS.put(word[0], word[2]);
        }
    }

    public static final class Conditions {
        public List<String> tags = new ArrayList<>();
        public List<String> excludedTags = new ArrayList<>();
        public String language = "";
    }

    public static final class Pick {
        public final Track track;
        public final String reason;
        public Pick(Track track, String reason) { this.track = track; this.reason = reason; }
    }

    public static final class Result {
        public final List<Pick> tracks;
        public final Conditions conditions;
        public final String mode;
        public final String notice;
        public Result(List<Pick> tracks, Conditions conditions, String mode, String notice) {
            this.tracks = tracks; this.conditions = conditions; this.mode = mode; this.notice = notice;
        }
    }

    private Recommender() {}

    public static Conditions parse(String request) {
        if (request == null || request.trim().isEmpty() || request.length() > 500)
            throw new IllegalArgumentException("듣고 싶은 음악을 1~500자로 입력해 주세요.");
        String input = Normalizer.normalize(request, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Conditions c = new Conditions();
        for (Map.Entry<String, String> word : WORDS.entrySet()) {
            java.util.regex.Matcher matcher = Pattern.compile(word.getValue()).matcher(input);
            while (matcher.find()) {
                String tail = input.substring(matcher.end(), Math.min(input.length(), matcher.end() + 15));
                String head = input.substring(Math.max(0, matcher.start() - 4), matcher.start());
                boolean negative = tail.matches("^[은는을를가이\\s]*(?:음악|노래|곡|건)?[은는을를\\s]*(?:빼|제외|말고|싫|아니|없).*" )
                    || head.matches(".*(?:no |not )$");
                List<String> target = negative ? c.excludedTags : c.tags;
                if (!target.contains(word.getKey())) target.add(word.getKey());
            }
        }
        c.tags.removeAll(c.excludedTags);
        if (input.matches(".*(한국어|한국 노래|한국곡|korean).*")) c.language = "ko";
        else if (input.matches(".*(영어|영미|english).*")) c.language = "en";
        else if (input.matches(".*(일본어|일본 노래|중국어|스페인어|프랑스어|불어|독일어|j.?pop|japanese|chinese|spanish|french|german).*")) c.language = "unsupported";
        if (c.tags.contains("instrumental")) c.language = "none";
        return c;
    }

    public static Conditions validate(Conditions c) {
        if (c == null || c.tags == null || c.excludedTags == null || c.language == null
                || !LABELS.keySet().containsAll(c.tags) || !LABELS.keySet().containsAll(c.excludedTags)
                || !Arrays.asList("", "ko", "en", "none", "unsupported").contains(c.language))
            throw new IllegalArgumentException("추천 조건을 해석하지 못했습니다.");
        c.tags = new ArrayList<>(new LinkedHashSet<>(c.tags));
        c.excludedTags = new ArrayList<>(new LinkedHashSet<>(c.excludedTags));
        c.tags.removeAll(c.excludedTags);
        if (c.tags.contains("instrumental")) c.language = "none";
        return c;
    }

    public static Result recommend(List<Track> catalogue, Conditions c, Map<String, Integer> feedback, String mode) {
        return recommend(catalogue, c, feedback, mode, Collections.emptySet());
    }

    public static Result recommend(List<Track> catalogue, Conditions c, Map<String, Integer> feedback, String mode, Set<String> excludedTrackIds) {
        validate(c);
        if (excludedTrackIds == null) throw new IllegalArgumentException("이번 추천에서 제외할 곡 정보를 확인해 주세요.");
        Map<String, Integer> likedTags = new LinkedHashMap<>();
        for (Track track : catalogue) {
            if (track.usable() && feedback.getOrDefault(track.id, 0) == 1)
                for (String tag : track.tags) likedTags.merge(tag, 1, Integer::sum);
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        List<Track> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> genre = Arrays.asList("acoustic", "ballad", "kpop", "pop", "rock", "piano", "instrumental");
        List<String> mood = Arrays.asList("calm", "bright", "energetic", "warm", "sad");
        for (Track track : catalogue) {
            if (!track.usable() || excludedTrackIds.contains(track.id) || !seen.add(track.id) || feedback.getOrDefault(track.id, 0) == -1
                    || !Collections.disjoint(track.tags, c.excludedTags)
                    || (!c.language.isEmpty() && !c.language.equals(track.language))) continue;
            if (!matchesGroup(c.tags, track.tags, genre) || !matchesGroup(c.tags, track.tags, mood)) continue;
            int score = feedback.getOrDefault(track.id, 0) == 1 ? 1 : 0;
            for (String tag : track.tags) {
                if (c.tags.contains(tag)) score += 10;
                score += Math.min(2, likedTags.getOrDefault(tag, 0));
            }
            candidates.add(track);
            scores.put(track.id, score);
        }
        // ponytail: linear catalogue scan; indexed retrieval when the curated catalogue outgrows memory.
        candidates.sort(Comparator.<Track>comparingInt(t -> scores.get(t.id)).reversed().thenComparing(t -> t.id));
        List<Pick> picks = new ArrayList<>();
        for (Track track : candidates.subList(0, Math.min(8, candidates.size()))) {
            List<String> matched = new ArrayList<>();
            for (String tag : c.tags) if (track.tags.contains(tag)) matched.add(LABELS.get(tag));
            StringBuilder description = new StringBuilder();
            for (String label : matched.subList(0, Math.min(3, matched.size()))) {
                if (description.length() > 0) description.append(" · ");
                description.append(label);
            }
            String reason = matched.isEmpty() ? "다양한 곡을 들어보며 취향을 찾아보세요." :
                    description + " 음악을 찾는 요청에 어울려요.";
            if (track.tags.stream().anyMatch(likedTags::containsKey)) reason += " 좋아요를 남긴 곡의 태그도 참고했어요.";
            picks.add(new Pick(track, reason));
        }
        String notice = picks.size() < 7 ? "조건에 맞는 곡이 " + picks.size() + "곡이에요. 조건을 줄이면 더 많은 곡을 찾을 수 있어요." : "";
        if (c.language.equals("unsupported")) notice = "요청한 언어의 곡이 현재 카탈로그에 없어 0곡을 찾았어요. 언어 조건을 줄여 다시 추천받아 보세요.";
        if (c.tags.isEmpty() && c.language.isEmpty()) notice = "구체적인 분위기나 장르를 더하면 요청에 맞춰 추천할 수 있어요.";
        return new Result(picks, c, mode, notice);
    }

    private static boolean matchesGroup(List<String> requested, List<String> trackTags, List<String> group) {
        for (String tag : requested) if (group.contains(tag) && trackTags.contains(tag)) return true;
        return Collections.disjoint(requested, group);
    }
}
