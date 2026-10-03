package com.mptaptap.core;

import java.util.List;

public final class PlaylistText {
    private PlaylistText() {}

    public static String format(String title, List<Track> tracks) {
        if (title == null || title.trim().isEmpty() || title.trim().length() > 80 || tracks == null || tracks.size() > 500)
            throw new IllegalArgumentException("목록 이름과 곡 정보를 확인해 주세요.");
        StringBuilder text = new StringBuilder(line(title)).append("\n총 ").append(tracks.size()).append("곡\n");
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            if (track == null || !track.usable()) throw new IllegalArgumentException("공유할 곡의 출처를 확인할 수 없어요.");
            String url = track.youtubeVideoId.isEmpty() ? track.sourceUrl : "https://music.youtube.com/watch?v=" + track.youtubeVideoId;
            text.append("\n").append(i + 1).append(". ").append(line(track.title)).append(" — ").append(line(track.artist))
                    .append("\n").append(url).append("\n");
        }
        return text.append("\n곡 정보: MusicBrainz 핵심 메타데이터(CC0).\n음원 파일은 포함하지 않아요.").toString();
    }

    private static String line(String value) { return value.replaceAll("[\\r\\n\\t]+", " ").trim(); }
}
