package com.mptaptap.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only direct user requests belong here; platform metadata is never conversation input. */
public final class Conversation {
    private Conversation() {}
    public static List<String> requests(List<String> history, String prompt) {
        Recommender.parse(prompt);
        if (history != null && history.size() > 8) throw new IllegalArgumentException("이전 요청은 8개까지 이어갈 수 있어요.");
        List<String> result = new ArrayList<>();
        if (history != null) for (String request : history) { Recommender.parse(request); result.add(request); }
        result.add(prompt);
        return result;
    }
    public static Recommender.Conditions parse(List<String> history, String prompt) {
        return parse(null,history,prompt);
    }
    public static Recommender.Conditions copyConditions(Recommender.Conditions source) {
        if(source==null)throw new IllegalArgumentException("이전 추천 조건을 확인해 주세요.");
        Recommender.Conditions copy=new Recommender.Conditions();
        copy.tags=source.tags==null?null:new ArrayList<>(source.tags);
        copy.excludedTags=source.excludedTags==null?null:new ArrayList<>(source.excludedTags);
        copy.language=source.language;
        return Recommender.validate(copy);
    }
    public static Recommender.Conditions parse(Recommender.Conditions previousConditions, List<String> history, String prompt) {
        List<String> validated=requests(history,prompt);
        Recommender.Conditions result = previousConditions==null?new Recommender.Conditions():copyConditions(previousConditions);
        for (String request : previousConditions==null?validated:List.of(prompt)) {
            if (request.matches("(?is).*(처음부터|새로 시작|조건 초기화|start over|reset).*")) result = new Recommender.Conditions();
            Recommender.Conditions next = Recommender.parse(request);
            for (List<String> group : List.of(List.of("calm","bright","energetic","warm","sad"),
                    List.of("acoustic","ballad","kpop","pop","rock","piano"))) {
                if (!Collections.disjoint(next.tags, group)) result.tags.removeAll(group);
            }
            result.tags.removeAll(next.excludedTags);
            if(next.excludedTags.contains("instrumental")&&result.language.equals("none"))result.language="";
            result.excludedTags.removeAll(next.tags);
            for (String tag : next.tags) if (!result.tags.contains(tag)) result.tags.add(tag);
            for (String tag : next.excludedTags) if (!result.excludedTags.contains(tag)) result.excludedTags.add(tag);
            if (!next.language.isEmpty()) {
                result.language = next.language;
                if (!next.language.equals("none")) result.tags.remove("instrumental");
            }
            if (request.matches("(?is).*(언어 상관없|언어 무관|any language).*")) result.language = "";
        }
        return Recommender.validate(result);
    }
    public static Recommender.Result recommend(List<Track> catalogue, Recommender.Conditions conditions,
            Map<String,Integer> feedback, String mode, List<String> kept, List<String> excluded) {
        List<String> keep = kept == null ? List.of() : kept;
        List<String> skip = excluded == null ? List.of() : excluded;
        Set<String> ids = new LinkedHashSet<>(); for (Track t : catalogue) ids.add(t.id);
        if (keep.size() > 8 || skip.size() > 100 || new LinkedHashSet<>(keep).size()!=keep.size()
                || !ids.containsAll(keep) || !ids.containsAll(skip) || !Collections.disjoint(keep,skip))
            throw new IllegalArgumentException("유지하거나 제외할 추천 곡을 다시 확인해 주세요.");
        Set<String> excludedIds = new LinkedHashSet<>(skip); excludedIds.addAll(keep);
        Recommender.Result fresh = Recommender.recommend(catalogue, conditions, feedback, mode, excludedIds);
        List<Recommender.Pick> picks = new ArrayList<>();
        for (String id : keep) {
            if(feedback.getOrDefault(id,0)==-1)throw new IllegalArgumentException("싫어요를 남긴 곡은 유지할 수 없어요. 반응을 취소한 뒤 선택해 주세요.");
            Track track = catalogue.stream().filter(t -> t.id.equals(id) && t.usable()).findFirst().orElseThrow();
            picks.add(new Recommender.Pick(track,"이전 추천에서 직접 유지한 곡이에요."));
        }
        picks.addAll(fresh.tracks.subList(0,Math.min(8-picks.size(),fresh.tracks.size())));
        String notice = fresh.notice;
        if(keep.size()>0 && !conditions.language.equals("unsupported") && (!conditions.tags.isEmpty()||!conditions.language.isEmpty()))
            notice=picks.size()<7?"조건에 맞는 곡과 유지한 곡을 합해 "+picks.size()+"곡이에요. 조건을 줄이면 더 많은 곡을 찾을 수 있어요.":"";
        if(!keep.isEmpty()&&conditions.language.equals("unsupported"))notice="요청한 언어의 곡이 현재 카탈로그에 없어 새 추천을 찾지 못했어요.";
        if (!keep.isEmpty()) notice = "직접 유지한 "+keep.size()+"곡은 새 조건과 관계없이 남겼어요. " + notice;
        return new Recommender.Result(picks, conditions, mode, notice.trim());
    }
}
