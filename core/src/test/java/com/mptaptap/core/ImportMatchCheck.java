package com.mptaptap.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ImportMatchCheck {
    public static void main(String[] args){run();System.out.println("PASS: supplied video timelines and deterministic external-song review matching.");}
    public static void run() {
        VideoTimeline.Result timeline=VideoTimeline.parse("A description\r\n[00:00] NewJeans — Hype boy\r\n02:59 Blueming by IU\r\n1:02:03 제목: 봄날 | 가수: 방탄소년단", "12345678901",4000);
        assert timeline.entries.size()==3;
        VideoTimeline.Entry first=timeline.entries.get(0),last=timeline.entries.get(2);
        assert first.sourceLine==2&&first.sourceRow.equals("[00:00] NewJeans — Hype boy");
        assert first.title.equals("Hype boy")&&first.artist.equals("NewJeans")&&first.needsReview;
        assert first.seconds==0&&first.startSeconds==0&&first.endSeconds==179;
        assert first.sourceUrl.equals("https://www.youtube.com/watch?v=12345678901&t=0s");
        assert !timeline.entries.get(1).needsReview&&timeline.entries.get(1).title.equals("Blueming")&&timeline.entries.get(1).artist.equals("IU");
        assert last.seconds==3723&&last.endSeconds==4000&&last.title.equals("봄날")&&last.artist.equals("방탄소년단")&&!last.needsReview;
        timeline=VideoTimeline.parse("０１：０２　Artist–Title\n01 : 03 Artist | Title Two\n1. 01:04 Song without artist\n01:05", "");
        assert timeline.entries.size()==4&&timeline.entries.get(0).seconds==62;
        assert timeline.entries.get(0).title.equals("Title")&&timeline.entries.get(0).sourceRow.startsWith("０１：０２");
        assert timeline.entries.get(1).seconds==63;
        assert timeline.entries.get(2).title.equals("Song without artist")&&timeline.entries.get(2).artist.isEmpty()&&timeline.entries.get(2).needsReview;
        assert timeline.entries.get(3).title.isEmpty()&&timeline.entries.get(3).needsReview;
        timeline=VideoTimeline.parse("00:60 Bad by Artist\n1:2:03 Bad by Artist\n00:05 Good by Artist\n00:03 Earlier by Artist\n00:03 Earlier by Artist\n00:10 Multiple 00:11 times\n-00:01 Negative by Artist\n00:01:02:03 Invalid by Artist", "12345678901",8);
        assert timeline.entries.size()==8;
        assert timeline.entries.get(0).seconds==-1&&timeline.entries.get(1).seconds==-1;
        assert !timeline.entries.get(2).needsReview&&timeline.entries.get(2).endSeconds==-1;
        assert timeline.entries.get(3).reviewReasons.contains("시간 순서 확인")&&timeline.entries.get(3).reviewReasons.contains("중복 시간");
        assert timeline.entries.get(4).reviewReasons.contains("반복된 곡");
        assert timeline.entries.get(5).reviewReasons.contains("한 줄에 여러 시간 표시")&&timeline.entries.get(5).reviewReasons.contains("영상 길이 밖의 시간");
        assert timeline.entries.get(6).seconds==-1&&timeline.entries.get(7).seconds==-1;
        assert timeline.entries.get(0).sourceUrl.equals("https://www.youtube.com/watch?v=12345678901");
        StringBuilder description=new StringBuilder();
        for(int i=0;i<101;i++)description.append(i).append(":00 Track ").append(i).append(" by Artist\n");
        timeline=VideoTimeline.parse(description.toString(),"",10000);
        assert timeline.entries.size()==100&&timeline.truncated&&timeline.entries.get(99).endSeconds==-1;
        assert VideoTimeline.parse("No timestamps, no invented tracks.","").entries.isEmpty();
        expectInvalid(()->VideoTimeline.parse("x".repeat(65537),""));
        expectInvalid(()->VideoTimeline.parse("00:00 "+"x".repeat(2000),""));
        expectInvalid(()->VideoTimeline.parse("00:00 Track","bad&video=1"));

        ImportMatch.PlatformTrack source=track("spotify","song1","Hype boy","NewJeans");
        ImportMatch.PlatformTrack exact=track("spotify","song1","Ｈｙｐｅ　ｂｏｙ","NEWJEANS");
        ImportMatch.PlatformTrack named=track("spotify","song2","Hype boy","NewJeans");
        ImportMatch.ReviewItem review=ImportMatch.match(source,Arrays.asList(named,exact));
        assert review.status==ImportMatch.Status.MATCHED&&review.selected==exact&&review.candidates.get(0).score==1000;
        review.exclude();assert review.status==ImportMatch.Status.USER_EXCLUDED&&review.selected==null;
        review.choose(named);assert review.status==ImportMatch.Status.MATCHED&&review.selected==named;
        ImportMatch.ReviewItem selectedReview=review;
        expectInvalid(()->selectedReview.choose(track("spotify","forged","Hype boy","NewJeans")));
        source=track("youtube","video1","Hype boy","NewJeans");source.isrc="KR-A0B-22-12345";
        exact=track("spotify","song1","Hype boy","NewJeans");exact.isrc="KRA0B2212345";
        named=track("spotify","song2","Hype boy","NewJeans");
        review=ImportMatch.match(source,Arrays.asList(named,exact));assert review.status==ImportMatch.Status.MATCHED&&review.selected==exact&&review.candidates.get(0).reasons.contains("ISRC 일치");
        source=track("youtube","","Hype boy","NewJeans");
        review=ImportMatch.match(source,Collections.singletonList(named));assert review.status==ImportMatch.Status.MATCHED;
        ImportMatch.PlatformTrack duplicate=track("spotify","other","Hype boy","NewJeans");duplicate.album="An album";
        source.album="An album";
        review=ImportMatch.match(source,Arrays.asList(named,duplicate));assert review.status==ImportMatch.Status.NEEDS_REVIEW&&review.selected==null;
        ImportMatch.PlatformTrack live=track("spotify","live","Hype boy (Live)","NewJeans");
        review=ImportMatch.match(source,Collections.singletonList(live));assert review.status==ImportMatch.Status.NEEDS_REVIEW;
        source.title="Hype boy (Live)";source.version="Live";
        review=ImportMatch.match(source,Collections.singletonList(live));assert review.status==ImportMatch.Status.MATCHED;
        source=track("youtube","","Live your life","Artist");named=track("spotify","x","Live your life","Artist");
        assert ImportMatch.match(source,Collections.singletonList(named)).status==ImportMatch.Status.MATCHED;
        source=track("youtube","","Hype boy","NewJeans");named=track("spotify","x","Hype boy","NewJeans");
        source.durationMs=180000;named.durationMs=181000;
        assert ImportMatch.match(source,Collections.singletonList(named)).status==ImportMatch.Status.MATCHED;
        named.durationMs=230000;
        assert ImportMatch.match(source,Collections.singletonList(named)).status==ImportMatch.Status.NEEDS_REVIEW;
        source.artist="";named.durationMs=180000;
        assert ImportMatch.match(source,Collections.singletonList(named)).status==ImportMatch.Status.NEEDS_REVIEW;
        named.title="Unrelated recording";named.artist="Other artist";
        assert ImportMatch.match(source,Collections.singletonList(named)).status==ImportMatch.Status.UNKNOWN;
        assert ImportMatch.match(source,Collections.emptyList()).status==ImportMatch.Status.UNKNOWN;
        source=track("spotify","same","Song","Artist");live=track("spotify","same","Song (Live)","Artist");named=track("spotify","other","Song","Artist");
        review=ImportMatch.match(source,Arrays.asList(named,live));
        assert review.candidates.get(0).track==live&&review.status==ImportMatch.Status.NEEDS_REVIEW : "ID must stay ahead of a conflicting name match for review";
        List<ImportMatch.PlatformTrack> tooMany=new ArrayList<>(Collections.nCopies(101,named));
        ImportMatch.PlatformTrack finalSource=source;
        expectInvalid(()->ImportMatch.match(finalSource,tooMany));
        named.title="x".repeat(501);ImportMatch.PlatformTrack oversized=named;
        expectInvalid(()->ImportMatch.match(finalSource,Collections.singletonList(oversized)));
    }
    private static ImportMatch.PlatformTrack track(String platform,String id,String title,String artist) {
        ImportMatch.PlatformTrack result=new ImportMatch.PlatformTrack();result.platform=platform;result.externalId=id;result.title=title;result.artist=artist;return result;
    }
    private static void expectInvalid(Runnable action){try{action.run();throw new AssertionError("Invalid input accepted");}catch(IllegalArgumentException expected){}}
}
