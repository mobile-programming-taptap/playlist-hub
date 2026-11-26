package com.mptaptap.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses timestamped track lists from video descriptions, comments, or user input. */
public final class VideoTimeline {
    public static final int MAX_ENTRIES=100, MAX_DESCRIPTION_CHARS=65536, MAX_ROW_CHARS=2000;
    private static final Pattern TIME=Pattern.compile("(?<![\\d:])\\d{1,4}(?:[\\p{Z}\\t]*:[\\p{Z}\\t]*\\d{1,3}){1,3}(?![\\d:])");
    private static final Pattern ROW_PREFIX=Pattern.compile("[\\[({#*•·-]*\\s*(?:\\d{1,3}[.)]?)?\\s*");
    private static final Pattern RANGE_SEPARATOR=Pattern.compile("[\\p{Z}\\s\\]}):]*[~～∼–—-][\\p{Z}\\s]*");
    private static final Pattern TRAILING_CLOSE=Pattern.compile("[\\p{Z}\\s\\])}.,;]*"),TRAILING_NUMBER=Pattern.compile("^[\\[({#*•·-]*\\s*\\d{1,3}[.)]\\s*"),
            TRAILING_OPEN=Pattern.compile("[\\p{Z}\\s\\[({|:;,–—-]+$");
    private static final Pattern SEPARATOR=Pattern.compile("\\s*[–—|]\\s*|\\s+[-/]\\s+|\\s+(?i:by)\\s+");
    private static final Pattern TITLE_FIRST=Pattern.compile("(?i)^(?:title|곡명|제목)\\s*:\\s*(.+?)\\s*[|;/]\\s*(?:artist|아티스트|가수)\\s*:\\s*(.+)$");
    private static final Pattern ARTIST_FIRST=Pattern.compile("(?i)^(?:artist|아티스트|가수)\\s*:\\s*(.+?)\\s*[|;/]\\s*(?:title|곡명|제목)\\s*:\\s*(.+)$");
    private VideoTimeline() {}

    public static final class Entry {
        public String sourceRow="", timestamp="", title="", artist="", sourceUrl="";
        public int sourceLine, seconds=-1, startSeconds=-1, endSeconds=-1;
        public boolean needsReview;
        public final List<String> reviewReasons=new ArrayList<>();
        private void review(String reason) { needsReview=true; if(!reviewReasons.contains(reason))reviewReasons.add(reason); }
    }
    public static final class Result {
        public final List<Entry> entries=new ArrayList<>();
        public boolean truncated;
    }

    public static Result parse(String description,String videoId) { return parse(description,videoId,-1); }
    /** Reads lists with the time first ("0:00 Song") or last ("Song 0:00") and keeps whichever finds more songs. */
    public static Result parseAutomatic(String description,String videoId,int durationSeconds) {
        Result leading=parse(description,videoId,durationSeconds,true,false),trailing=parse(description,videoId,durationSeconds,true,true);
        return trailing.entries.size()>leading.entries.size()?trailing:leading;
    }
    public static Result parse(String description,String videoId,int durationSeconds) {
        return parse(description,videoId,durationSeconds,false,false);
    }
    private static Result parse(String description,String videoId,int durationSeconds,boolean automatic,boolean trailing) {
        if(description==null||description.length()>MAX_DESCRIPTION_CHARS||durationSeconds< -1
                ||videoId==null||(!videoId.isEmpty()&&!videoId.matches("[A-Za-z0-9_-]{11}")))
            throw new IllegalArgumentException("영상 ID와 설명 길이를 확인해 주세요.");
        if(durationSeconds==0)durationSeconds=-1;
        Result result=new Result(); Map<Integer,Entry> times=new HashMap<>(); Map<String,Entry> songs=new HashMap<>();
        int lineNumber=0, previous=-1;
        for(String row:description.split("\\r\\n|\\r|\\n",-1)) {
            lineNumber++; if(!automatic&&row.length()>MAX_ROW_CHARS)throw new IllegalArgumentException("설명 한 줄은 2000자까지 입력할 수 있어요.");
            String normalized=Normalizer.normalize(row,Normalizer.Form.NFKC);
            Matcher time=TIME.matcher(normalized); if(!time.find())continue;
            int begin=time.start(),end=time.end();
            String before=normalized.substring(0,begin).trim();
            if(ROW_PREFIX.matcher(before).matches()&&!(begin>0&&normalized.charAt(begin-1)=='-'))before="";
            if(automatic&&(trailing==before.isEmpty()||row.length()>MAX_ROW_CHARS))continue;
            Entry entry=new Entry();entry.sourceRow=row;entry.sourceLine=lineNumber;entry.timestamp=time.group();
            entry.seconds=seconds(entry.timestamp);entry.startSeconds=entry.seconds;
            if(trailing) {
                // The time must close the row: "Artist - Title 0:00" or "3. Artist - Title (0:00)".
                if(time.find()||!TRAILING_CLOSE.matcher(normalized.substring(end)).matches())continue;
                parseSong(entry,spaces(TRAILING_OPEN.matcher(TRAILING_NUMBER.matcher(before).replaceFirst("")).replaceFirst("")));
                if(entry.title.isEmpty()||entry.seconds<=previous||durationSeconds>=0&&entry.seconds>=durationSeconds)continue;
            } else {
            if(begin>0&&normalized.charAt(begin-1)=='-')entry.seconds=entry.startSeconds=-1;
            if(entry.seconds<0)entry.review("시간 형식 확인");
            if(time.find()) {
                if(RANGE_SEPARATOR.matcher(normalized.substring(end,time.start())).matches()) {
                    int rangeEnd=seconds(time.group());
                    if(rangeEnd>entry.seconds&&entry.seconds>=0&&(durationSeconds<0||rangeEnd<=durationSeconds))entry.endSeconds=rangeEnd;
                    else entry.review("종료 시간 확인");
                    end=time.end();
                    if(time.find())entry.review("한 줄에 여러 시간 표시");
                } else entry.review("한 줄에 여러 시간 표시");
            }
            String after=normalized.substring(end).replaceFirst("^[\\p{Z}\\s\\]})|:;,-]+","").trim();
            parseSong(entry,spaces(before+" "+after));
            if(automatic&&(entry.title.isEmpty()||entry.seconds<=previous||durationSeconds>=0&&entry.seconds>=durationSeconds))continue;
            }
            if(result.entries.size()==MAX_ENTRIES) { result.truncated=true; break; }
            if(entry.title.isEmpty())entry.review("곡명 누락");
            if(entry.artist.isEmpty())entry.review("아티스트 누락");
            if(entry.seconds>=0) {
                if(previous>entry.seconds)entry.review("시간 순서 확인");previous=Math.max(previous,entry.seconds);
                if(durationSeconds>=0&&entry.seconds>=durationSeconds)entry.review("영상 길이 밖의 시간");
                Entry sameTime=times.putIfAbsent(entry.seconds,entry);
                if(sameTime!=null){sameTime.review("중복 시간");entry.review("중복 시간");}
            }
            if(!entry.title.isEmpty()) {
                String key=(entry.title+"\n"+entry.artist).toLowerCase(Locale.ROOT);
                Entry sameSong=songs.putIfAbsent(key,entry);
                if(sameSong!=null){sameSong.review("반복된 곡");entry.review("반복된 곡");}
            }
            if(!videoId.isEmpty())entry.sourceUrl="https://www.youtube.com/watch?v="+videoId+(entry.seconds<0?"":"&t="+entry.seconds+"s");
            result.entries.add(entry);
        }
        for(int i=0;i<result.entries.size();i++) {
            Entry current=result.entries.get(i);
            if(current.seconds<0||current.endSeconds>=0)continue;
            int next=i+1<result.entries.size()?result.entries.get(i+1).seconds:result.truncated?-1:durationSeconds;
            if(next>current.seconds&&(durationSeconds<0||next<=durationSeconds))current.endSeconds=next;
        }
        return result;
    }

    private static int seconds(String timestamp) {
        String[] parts=timestamp.replaceAll("[\\p{Z}\\s]+","").split(":");
        if(parts.length!=2&&parts.length!=3)return -1;
        for(int i=1;i<parts.length;i++)if(!parts[i].matches("\\d{2}")||Integer.parseInt(parts[i])>=60)return -1;
        int total=0;for(String part:parts)total=total*60+Integer.parseInt(part);return total;
    }
    private static void parseSong(Entry entry,String body) {
        Matcher title=TITLE_FIRST.matcher(body),artist=ARTIST_FIRST.matcher(body);
        if(title.matches()){entry.title=spaces(title.group(1));entry.artist=spaces(title.group(2));return;}
        if(artist.matches()){entry.artist=spaces(artist.group(1));entry.title=spaces(artist.group(2));return;}
        Matcher separator=SEPARATOR.matcher(body);
        if(separator.find()) {
            String left=spaces(body.substring(0,separator.start())),right=spaces(body.substring(separator.end()));
            if(separator.group().trim().equalsIgnoreCase("by")){entry.title=left;entry.artist=right;}
            else {entry.artist=left;entry.title=right;entry.review("아티스트·곡명 순서 확인");}
        } else entry.title=body;
    }
    private static String spaces(String text) { return text.replaceAll("[\\p{Z}\\s]+"," ").trim(); }
}
