package com.mptaptap.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** External platform metadata stays separate from the recommendation Track catalogue. */
public final class ImportMatch {
    public static final int MAX_CANDIDATES=100;
    public enum Status { UNKNOWN, NEEDS_REVIEW, MATCHED, USER_EXCLUDED }
    public static final class PlatformTrack {
        public String platform="",externalId="",isrc="",title="",artist="",version="",album="",sourceUrl="",artworkUrl="";
        public long durationMs;
    }
    public static boolean spotifyArtwork(String url) {
        return url!=null&&url.matches("https://(?:i\\.scdn\\.co|image-cdn-[a-z]{2}\\.spotifycdn\\.com)/image/[0-9a-fA-F]{40}");
    }
    public static final class Candidate {
        public PlatformTrack track;
        public int score;
        public final List<String> reasons=new ArrayList<>();
        private boolean identity,exact,conflict;
    }
    public static final class ReviewItem {
        public PlatformTrack source,selected;
        public Status status=Status.UNKNOWN;
        public final List<Candidate> candidates=new ArrayList<>();
        public void exclude(){selected=null;status=Status.USER_EXCLUDED;}
        public void choose(PlatformTrack track) {
            for(Candidate candidate:candidates)if(candidate.track==track){selected=track;status=Status.MATCHED;return;}
            throw new IllegalArgumentException("제시된 곡 중 하나를 선택해 주세요.");
        }
    }
    private static final Pattern QUALIFIER=Pattern.compile("\\([^)]*\\)|\\[[^]]*\\]|\\s[-–—]\\s.+$");
    private static final Pattern VERSION=Pattern.compile("(?i)(?<![\\p{L}])(?:live|remix|cover|instrumental|acoustic|demo|radio edit|sped up|slowed|remaster(?:ed)?)(?![\\p{L}])|라이브|리믹스|커버|연주곡|반주|어쿠스틱|리마스터");
    /** Featured-artist credits ("(feat. X)", "[ft. X]", " feat. X") name collaborators, not a different song. */
    private static final Pattern FEATURE=Pattern.compile("(?i)^[(\\[]?\\s*(?:feat\\.?|ft\\.?|featuring)\\s");
    private static final Pattern TRAILING_FEATURE=Pattern.compile("(?i)\\s(?:feat\\.?|ft\\.?|featuring)\\s.+$");
    private static final Pattern VIDEO_LABEL=Pattern.compile("(?i)\\s*[(\\[][^)\\]]*(?:official|video|lyrics?|audio|visuali[sz]er|\\bm/?v\\b|가사|뮤직\\s*비디오)[^)\\]]*[)\\]]");
    private static final Pattern ACT_PREFIX=Pattern.compile("^(.+?)\\s+[-–—]\\s+(.+)$"),CHANNEL_SUFFIX=Pattern.compile("(?i)\\s*(?:-\\s*topic|vevo|official)$");
    private static final Pattern CREDIT_SPLIT=Pattern.compile("(?i)\\s*(?:,|&|/|;|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\bx\\b)\\s*");
    private ImportMatch() {}

    public static ReviewItem match(PlatformTrack source,List<PlatformTrack> candidates) {
        validate(source);
        if(candidates==null||candidates.size()>MAX_CANDIDATES)throw new IllegalArgumentException("후보 곡은 100개까지 확인할 수 있어요.");
        ReviewItem result=new ReviewItem();result.source=source;
        for(PlatformTrack track:candidates){validate(track);result.candidates.add(score(source,track));}
        // Stable sort: equal scores keep the platform's search order, so the first suggestion is its most relevant result.
        result.candidates.sort(Comparator.comparingInt((Candidate c)->c.score).reversed());
        if(result.candidates.isEmpty()||result.candidates.get(0).score<300)return result;
        Candidate best=result.candidates.get(0);
        boolean ambiguous=false;
        for(int i=1;i<result.candidates.size();i++) {
            Candidate other=result.candidates.get(i);
            if(best.identity?other.identity&&other.reasons.get(0).equals(best.reasons.get(0)):other.exact&&!other.conflict){ambiguous=true;break;}
        }
        if((best.identity||best.exact)&&!best.conflict&&!ambiguous){result.status=Status.MATCHED;result.selected=best.track;}
        else result.status=Status.NEEDS_REVIEW;
        return result;
    }

    private static Candidate score(PlatformTrack source,PlatformTrack track) {
        Candidate candidate=new Candidate();candidate.track=track;
        PlatformTrack original=source;source=asSong(source,track.artist);track=asSong(track,original.artist);
        String sourceIsrc=isrc(source.isrc),targetIsrc=isrc(track.isrc);
        boolean external=!clean(source.platform).isEmpty()&&clean(source.platform).equals(clean(track.platform))
                &&!value(source.externalId).isEmpty()&&value(source.externalId).equals(value(track.externalId));
        boolean recording=!sourceIsrc.isEmpty()&&sourceIsrc.equals(targetIsrc);
        String sourceVersion=version(source),targetVersion=version(track);
        boolean title=!baseTitle(source.title).isEmpty()&&baseTitle(source.title).equals(baseTitle(track.title));
        boolean artist=!clean(source.artist).isEmpty()&&(clean(source.artist).equals(clean(track.artist))||credits(track.artist).containsAll(credits(source.artist)));
        candidate.identity=external||recording;candidate.exact=title&&artist&&sourceVersion.equals(targetVersion);
        if(external){candidate.score=1000;candidate.reasons.add("플랫폼 ID 일치");}
        else if(recording){candidate.score=950;candidate.reasons.add("ISRC 일치");}
        else if(title&&artist){candidate.score=800;candidate.reasons.add("곡명·아티스트 일치");}
        else if(title){candidate.score=500;candidate.reasons.add("곡명 일치·아티스트 확인 필요");}
        else {
            candidate.score=(artist?200:0)+(int)(similarity(baseTitle(source.title),baseTitle(track.title))*300);
            candidate.reasons.add("곡명·아티스트 확인 필요");
        }
        if(!sourceVersion.equals(targetVersion)) {candidate.conflict=true;if(!candidate.identity)candidate.score-=250;candidate.reasons.add("라이브·리믹스 등 버전 확인 필요");}
        if(!sourceIsrc.isEmpty()&&!targetIsrc.isEmpty()&&!sourceIsrc.equals(targetIsrc)) {
            candidate.conflict=true;if(!candidate.identity)candidate.score-=100;candidate.reasons.add("ISRC 불일치");
        }
        if(!clean(source.album).isEmpty()&&clean(source.album).equals(clean(track.album))){candidate.score+=20;candidate.reasons.add("앨범 일치");}
        if(source.durationMs>0&&track.durationMs>0) {
            long difference=Math.abs(source.durationMs-track.durationMs);
            if(difference<=2000){candidate.score+=20;candidate.reasons.add("재생 시간 일치");}
            else if(difference>10000){candidate.conflict=true;if(!candidate.identity)candidate.score-=100;candidate.reasons.add("재생 시간 차이 확인 필요");}
        }
        return candidate;
    }
    /** A YouTube video named "Act - Song (Official Video)" on channel "Act Official", read as the song "Song" by "Act". */
    private static PlatformTrack asSong(PlatformTrack track,String otherAct) {
        if(!clean(track.platform).equals("youtube"))return track;
        String artist=CHANNEL_SUFFIX.matcher(value(track.artist)).replaceAll(""),title=VIDEO_LABEL.matcher(value(track.title)).replaceAll("").trim();
        // Channels often drop spaces: "TheWeekndVEVO" is "The Weeknd".
        if(!clean(otherAct).isEmpty()&&clean(artist).replace(" ","").equals(clean(otherAct).replace(" ","")))artist=otherAct;
        Matcher named=ACT_PREFIX.matcher(title);
        if(named.matches()) {
            java.util.Set<String> act=credits(named.group(1));
            if(!credits(artist).isEmpty()&&act.containsAll(credits(artist))||!credits(otherAct).isEmpty()&&act.containsAll(credits(otherAct)))title=named.group(2);
        }
        PlatformTrack song=new PlatformTrack();
        song.platform=track.platform;song.externalId=track.externalId;song.isrc=track.isrc;song.title=title;song.artist=artist;
        song.version=track.version;song.album=track.album;song.sourceUrl=track.sourceUrl;song.artworkUrl=track.artworkUrl;song.durationMs=track.durationMs;
        return song;
    }
    private static String version(PlatformTrack track) {
        StringBuilder key=new StringBuilder(clean(track.version));Matcher qualifier=QUALIFIER.matcher(value(track.title));
        while(qualifier.find())if(VERSION.matcher(qualifier.group()).find()) {
            String part=clean(qualifier.group());
            if(key.length()==0||part.contains(key.toString())){key.setLength(0);key.append(part);}
            else if(!key.toString().contains(part))key.append(' ').append(part);
        }
        return clean(key.toString());
    }
    private static String baseTitle(String title) {
        String text=value(title);Matcher qualifier=QUALIFIER.matcher(text);StringBuffer result=new StringBuffer();
        while(qualifier.find()) {
            String part=qualifier.group();
            boolean drop=VERSION.matcher(part).find()||FEATURE.matcher(part.trim()).find();
            qualifier.appendReplacement(result,drop?"":Matcher.quoteReplacement(part));
        }
        qualifier.appendTail(result);return clean(TRAILING_FEATURE.matcher(result.toString()).replaceAll(""));
    }
    /** The individual acts in a credit such as "Daft Punk, Pharrell Williams" or "Mark Ronson feat. Bruno Mars". */
    private static java.util.Set<String> credits(String artist) {
        java.util.Set<String> names=new java.util.LinkedHashSet<>();
        for(String name:CREDIT_SPLIT.split(value(artist)))if(!clean(name).isEmpty())names.add(clean(name));
        return names;
    }
    private static double similarity(String a,String b) {
        if(a.isEmpty()||b.isEmpty())return 0;
        String[] words=a.split(" ");int shared=0;
        for(String word:words)if((" "+b+" ").contains(" "+word+" "))shared++;
        return (double)shared/Math.max(words.length,b.split(" ").length);
    }
    private static String isrc(String text) {
        String normalized=value(text).replaceAll("[-\\s]","").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")?normalized:"";
    }
    private static String clean(String text) {
        return Normalizer.normalize(value(text),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ");
    }
    private static String value(String text){return text==null?"":text.trim();}
    private static String raw(String text){return text==null?"":text;}
    private static void validate(PlatformTrack track) {
        if(track==null||track.durationMs<0||track.durationMs>86_400_000L
                ||raw(track.platform).length()>32||raw(track.externalId).length()>200||raw(track.isrc).length()>32
                ||raw(track.title).length()>500||raw(track.artist).length()>500||raw(track.version).length()>500
                ||raw(track.album).length()>500||raw(track.sourceUrl).length()>2048)
            throw new IllegalArgumentException("외부 곡 정보의 길이와 재생 시간을 확인해 주세요.");
    }
}
