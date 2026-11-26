package com.mptaptap.core;

import java.util.List;

/**
 * 발매 연도와 연대 판정을 한곳에 모은다.
 *
 * MusicBrainz 녹음 검색 응답의 first-release-date 와 releases[].date 형식
 * (YYYY, YYYY-MM, YYYY-MM-DD, 모르면 필드 없음)은 API 문서 기준이고 실측하지 않았다.
 * 발매일이 없는 응답의 비율을 실측한 뒤 기준(예: 연도를 모르는 곡을 어떻게 다룰지)을
 * 바꿀 때는 이 클래스만 고친다.
 */
public final class ReleaseYear {
    /** 발매 연도를 모른다. */
    public static final int UNKNOWN = 0;

    public enum Verdict { MATCH, UNDATED, OUTSIDE }

    private ReleaseYear() {}

    /** first-release-date 가 연도로 시작하면 그 연도, 아니면 발매본 날짜 중 가장 이른 연도, 둘 다 없으면 UNKNOWN. */
    public static int of(String firstReleaseDate, List<String> releaseDates) {
        int first = year(firstReleaseDate);
        if (first != UNKNOWN) return first;
        int earliest = UNKNOWN;
        if (releaseDates != null) for (String date : releaseDates) {
            int year = year(date);
            if (year != UNKNOWN && (earliest == UNKNOWN || year < earliest)) earliest = year;
        }
        return earliest;
    }

    private static int year(String date) {
        if (date == null || !date.matches("\\d{4}(?:-.*)?")) return UNKNOWN;
        int year = Integer.parseInt(date.substring(0, 4));
        return year > 0 ? year : UNKNOWN;
    }

    /** 연대 조건 판정. 연도를 모르면 UNDATED 로 제외하고, 요청한 연대 중 하나에 속하면 통과한다. */
    public static Verdict judge(int year, List<Integer> decades) {
        if (year <= UNKNOWN) return Verdict.UNDATED;
        return decades.contains(year / 10 * 10) ? Verdict.MATCH : Verdict.OUTSIDE;
    }
}
