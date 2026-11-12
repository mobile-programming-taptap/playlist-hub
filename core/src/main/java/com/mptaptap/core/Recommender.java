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
    private static final Map<String,String> LANGUAGE_WORDS=new LinkedHashMap<>();
    public static final List<String> GENRES = List.of("acoustic", "ballad", "kpop", "pop", "rock", "piano",
            "jazz", "classical", "hiphop", "rnb", "edm", "blues", "metal", "indie", "folk", "soul", "funk",
            "disco", "citypop", "lofi", "jpop", "synthpop", "dreampop", "ambient", "house", "techno", "trance",
            "punk", "reggae", "bossa_nova", "latin", "country", "soundtrack", "gospel", "trap", "unsupported_genre");
    /** 부정된 분위기는 그 자체로 검색되지 않는다. 반대편 긍정 태그는 검색된다. */
    private static final Map<String, List<String>> OPPOSITES = Map.of(
            "calm", List.of("energetic", "bright"),
            "bright", List.of("calm", "sad"),
            "energetic", List.of("calm"),
            "sad", List.of("warm", "bright"),
            "warm", List.of("calm"));
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
                {"pop", "팝", "(?<!k[ -])(?<![a-z-])pop|(?<![가-힣a-z-])팝(?=$|\\s|[.,!?]|송|음악|[은는을를으도가이만])"},
                {"rock", "록", "락|록|rock"},
                {"piano", "피아노", "피아노|piano"},
                {"jazz", "재즈", "재즈|(?<![a-z])jazz(?![a-z])"},
                {"classical", "클래식", "클래식|고전\\s*음악|(?<![a-z])classical(?![a-z])"},
                {"hiphop", "힙합", "힙합|(?<![a-z])hip[ -]?hop(?![a-z])"},
                {"rnb", "R&B", "알앤비|알엔비|(?<![a-z])r\\s*(?:&|and|n)\\s*b(?![a-z])"},
                {"edm", "전자 음악", "일렉트로닉|일렉트로니카|전자\\s*음악|(?<![a-z])(?:edm|electronic)(?![a-z])"},
                {"blues", "블루스", "블루스|(?<![a-z])blues(?![a-z])"},
                {"metal", "메탈", "메탈|헤비메탈|(?<![a-z])(?:heavy[ -]?)?metal(?![a-z])"},
                {"indie", "인디", "인디(?:\\s*(?:팝|록|락))?|(?<![a-z])indie(?:[ -]+(?:pop|rock))?(?![a-z])"},
                {"folk", "포크", "포크|(?<![a-z])folk(?![a-z])"},
                {"soul", "소울", "소울|(?<![a-z])soul(?![a-z])"},
                {"funk", "훵크", "훵크|펑키|(?<![a-z])funk(?![a-z])"},
                {"disco", "디스코", "디스코|(?<![a-z])disco(?![a-z])"},
                {"citypop", "시티팝", "시티\\s*팝|(?<![a-z])city[ -]*pop(?![a-z])"},
                {"lofi", "로파이", "로[ -]?파이(?:\\s*힙합)?|(?<![a-z])lo[ -]?fi(?:[ -]+hip[ -]?hop)?(?![a-z])"},
                {"jpop", "J-pop", "제이\\s*팝|(?<![a-z])j[ -]?pop(?![a-z])"},
                {"synthpop", "신스팝", "신스\\s*팝|(?<![a-z])synth[ -]*pop(?![a-z])"},
                {"dreampop", "드림팝", "드림\\s*팝|(?<![a-z])dream[ -]*pop(?![a-z])"},
                {"ambient", "앰비언트", "[앰엠]비언트|(?<![a-z])ambient(?![a-z])"},
                {"house", "하우스", "하우스|(?<![a-z])house(?![a-z])"},
                {"techno", "테크노", "테크노|(?<![a-z])techno(?![a-z])"},
                {"trance", "트랜스", "트랜스|(?<![a-z])trance(?![a-z])"},
                {"punk", "펑크", "펑크\\s*(?:록|락)?|(?<![a-z])punk(?:[ -]*rock)?(?![a-z])"},
                {"reggae", "레게", "레게|(?<![a-z])reggae(?![a-z])"},
                {"bossa_nova", "보사노바", "보[사싸]\\s*노바|(?<![a-z])bossa[ -]*nova(?![a-z])"},
                {"latin", "라틴", "라틴|(?<![a-z])latin(?![a-z])"},
                {"country", "컨트리", "컨트리|(?<![a-z])country(?![a-z])"},
                {"soundtrack", "OST", "사운드\\s*트랙|영화\\s*음악|(?<![a-z])(?:soundtrack|ost)(?![a-z])"},
                {"gospel", "가스펠", "가스펠|(?<![a-z])gospel(?![a-z])"},
                {"trap", "트랩", "트랩|(?<![a-z])trap(?![a-z])"},
                // "가사ㄴㄴ", "노래방ㄴㄴ" 같은 축약 부정도 연주곡 요청으로 읽는다.
                {"instrumental", "가사 없는", "가사\\s*없는|가사\\s*없이|가사\\s*ㄴㄴ|가사\\s*no|보컬\\s*없는|보컬\\s*ㄴㄴ|노래방\\s*ㄴㄴ|연주곡|instrumental|no vocals"},
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
        LABELS.put("unsupported_genre", "현재 지원하지 않는 장르");
        LANGUAGE_WORDS.put("ko","한국어|한국 노래|한국곡|\\bkorean\\b");LANGUAGE_WORDS.put("en","영어|영미|\\benglish\\b");
        LANGUAGE_WORDS.put("ja","일본어|일본 노래|\\bjapanese\\b");LANGUAGE_WORDS.put("zh","중국어|\\b(?:chinese|mandarin)\\b");
        LANGUAGE_WORDS.put("es","스페인어|\\bspanish\\b");LANGUAGE_WORDS.put("fr","프랑스어|불어|\\bfrench\\b");
        LANGUAGE_WORDS.put("de","독일어|\\bgerman\\b");
    }

    public static final class Conditions {
        public List<String> tags = new ArrayList<>();
        public List<String> excludedTags = new ArrayList<>();
        public String language = "";
        /** 사전이 매핑하지 못한 표현. AI 해석이 덮을 수 있으므로 실패로 단정하지 않는다. */
        public List<String> unread = new ArrayList<>();
        /** 최종 결과에서 반드시 만족해야 하는 tags 부분집합. 비면 장르·instrumental에서 자동 도출한다. */
        public List<String> hardTags = new ArrayList<>();
        /** 후보 선택과 추천 순서에만 반영하는 선호. 곡을 탈락시키지 않는다. */
        public List<String> softTags = new ArrayList<>();
        /** 직접 요청으로 제외한 가수. 표기가 달라도 대표 이름으로 비교한다. */
        public List<String> excludedArtists = new ArrayList<>();
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

    /** 사전 태그 + 열린 어휘. 'mood:'는 사전이 아직 모르는 AI 생성 태그를 담는다. */
    public static boolean validTag(String tag){
        return tag!=null&&(LABELS.containsKey(tag)
                ||tag.matches("genre:[a-z0-9][a-z0-9_-]{0,63}")
                ||tag.matches("mood:[a-z0-9][a-z0-9_-]{0,63}"));
    }
    public static boolean dictionary(String tag){return tag!=null&&LABELS.containsKey(tag);}
    public static boolean mood(String tag){return tag!=null&&tag.startsWith("mood:");}
    public static boolean validLanguage(String language){return language!=null&&language.length()<=35&&(language.isEmpty()||language.equals("none")||language.equals("unsupported")||language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*"));}
    public static boolean genre(String tag){return tag!=null&&(GENRES.contains(tag)||tag.startsWith("genre:"));}

    /**
     * 결과에서 반드시 지켜야 하는 조건인가.
     * 장르와 연주곡(가사 없음)은 hard, 분위기와 상황은 soft로 본다.
     */
    public static boolean isHardTag(String tag) {
        if (tag == null) return false;
        if (genre(tag)) return true;
        return tag.equals("instrumental");
    }
    public static boolean isSoftTag(String tag) { return tag != null && !isHardTag(tag); }

    /** 표기가 흔들리는 아티스트 이름을 비교 가능한 형태로 만든다. */
    public static String primaryArtist(String credit) {
        if (credit == null) return "";
        String value = Normalizer.normalize(credit, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        value = value.split("\\s*(?:feat\\.?|featuring|with|&|,|\\bx\\b|vs\\.?)\\s*", 2)[0];
        value = value.replaceAll("\\s*(?:ensemble|orchestra|band|quartet|trio|group)$", "");
        return value.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static String label(String tag){
        if(tag==null)return "";
        if(tag.startsWith("genre:"))return tag.substring(6).replace('_',' ').replace('-',' ');
        if(tag.startsWith("mood:"))return tag.substring(5).replace('_',' ').replace('-',' ');
        return LABELS.getOrDefault(tag,tag);
    }
    public static String languageLabel(String language){
        if(language==null||language.isEmpty())return "";if(language.equals("none"))return "연주곡";if(language.equals("unsupported"))return "언어 확인 필요";
        String name=Locale.forLanguageTag(language).getDisplayLanguage(Locale.KOREAN);return name.isEmpty()?language:name;
    }

    /** ponytail: dictionary and fixed wording only; extend for verified requests, leave additional meaning to the interpreter. */
    public static boolean isKnownConditionRequest(String request) {
        Conditions c=parse(request);
        if(c.tags.isEmpty()&&c.excludedTags.isEmpty())return false;
        // 사전이 읽지 못한 표현이 남아 있으면 AI 해석에 맡긴다.
        if(!c.unread.isEmpty())return false;
        String remaining=Normalizer.normalize(request,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        remaining=remaining.replaceAll(String.join("|",WORDS.values())," ");
        return remaining.matches("(?s)(?:[\\p{Z}\\s.,!?·/]|처음부터|새로\\s*시작|조건\\s*초기화|음악|노래|곡|장르|송"
                +"|추천(?:해\\s*줘|해\\s*주세요|해주세요|해)?|으로|로|바꿔\\s*(?:줘|주세요)|듣고\\s*싶어"
                +"|빼고|빼|제외하고|제외|말고|ㄴㄴ|노노|[은는을를가이도만와과]"
                +"|하게|한|게|할|때|날|에|더"
                +"|\\b(?:reset|start\\s+over|recommend|music|songs?|genre|please|only|instead|no|not|exclude|avoid|without|and|or)\\b)*");
    }

    public static Conditions parse(String request) {
        if (request == null || request.trim().isEmpty() || request.length() > 500)
            throw new IllegalArgumentException("듣고 싶은 음악을 1~500자로 입력해 주세요.");
        String input = Normalizer.normalize(request, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // "조용하면 졸려"처럼 상태를 이유로 드는 진술은 그 상태 자체를 피하려는 요청이다.
        // 문장을 지우지 않고 부정 구간으로 남겨 두어야 "조용함"이 제외 조건으로 읽힌다.
        List<int[]> reasonNegations = new ArrayList<>();
        java.util.regex.Matcher reason = Pattern.compile(
                "([가-힣]+(?:하면|하니까|하니|해서|한데|하더니)\\s*(?:[가-힣]+\\s*){0,2}"
                + "(?:졸려|졸리|졸음|지루|지겹|심심|답답|따분|힘들|피곤|잠\\s*(?:이|은|와|옴)))")
                .matcher(input);
        while (reason.find()) reasonNegations.add(new int[]{reason.start(), reason.end()});
        Conditions c = new Conditions();

        List<int[]> genreSpans=new ArrayList<>();
        for(var word:WORDS.entrySet())if(GENRES.contains(word.getKey())) {
            var matches=Pattern.compile(word.getValue()).matcher(input);
            while(matches.find())genreSpans.add(new int[]{matches.start(),matches.end()});
        }
        for (Map.Entry<String, String> word : WORDS.entrySet()) {
            java.util.regex.Matcher matcher = Pattern.compile(word.getValue()).matcher(input);
            while (matcher.find()) {
                // A subgenre alias such as city pop or punk rock owns its contained pop/rock token.
                int start=matcher.start(),end=matcher.end();
                if(GENRES.contains(word.getKey())&&genreSpans.stream().anyMatch(span->span[0]<=start&&span[1]>=end&&span[1]-span[0]>end-start))continue;
                boolean negated=negative(input,start,end)||inside(spansOf(reason, input, reasonNegations), start, end);
                List<String> target = negated ? c.excludedTags : c.tags;
                if (!target.contains(word.getKey())) target.add(word.getKey());
                // "안 잔잔한"은 calm:AVOID만으로 검색이 안 된다. 반대편 긍정 태그를 함께 요구한다.
                // "잔잔한 곡은 제외"처럼 뒤에서 빼는 말은 반대편을 원하는 것이 아니므로
                // 부정어가 대상 앞에 온 경우에만 반대편 태그를 요구한다.
                if(negated&&frontNegated(input,start))for(String opposite:OPPOSITES.getOrDefault(word.getKey(),List.of()))
                    if(!c.tags.contains(opposite)&&!c.excludedTags.contains(opposite))c.tags.add(opposite);
            }
        }
        c.tags.removeAll(c.excludedTags);
        for(LanguageMention mention:languages(input))if(!mention.negative)c.language=mention.code;
        c.unread = unread(input);
        // "가사 없는/가사ㄴㄴ"은 연주곡을 원하는 요청이므로 language none 이다.
        // 반대로 "연주곡 제외"는 연주곡을 빼달라는 요청이므로 language 를 비운 채로 둔다.
        if (c.tags.contains("instrumental")) c.language = "none";
        return c;
    }

    private static final Pattern SEPARATORS = Pattern.compile("[\\p{Z}\\s.,!?·/]+");
    private static final Pattern FILLER = Pattern.compile("(?:[은는을를가이도만와과]|하게|한|게|할|때|날|에|더|음악|노래|곡|장르|송"
            + "|추천|추천해|추천해줘|추천해주세요|바꿔|바꿔줘|바꿔주세요|빼고|빼|제외|제외하고|말고|듣고|싶어|싶은|좀|너무|정도|같은|느낌)");
    /** 남은 토큰 = 사전이 읽지 못한 표현. 화면 안내와 AI 보완 판단에 쓴다. */
    private static List<String> unread(String input){
        // 조건이 아니라 명령인 표현은 먼저 지운다.
        String body = input.replaceAll("(?:처음부터|새로\\s*시작|조건\\s*초기화|start\\s+over|reset)"," ")
                .replaceAll("\\b(?:recommend|recommends|recommendation|music|song|songs|please|only|instead|genre|exclude|excludes|excluded|excluding|avoid|avoids|avoided|without|except|no|not)\\b"," ");
        // 사전이 읽은 표현을 원문에서 먼저 지운다. "가사 없는"처럼 여러 단어가 붙어야 뜻이 되는
        // 표현을 토큰 단위로 쪼개면 놓치기 때문이다.
        // 사전이 읽은 표현을 원문에서 먼저 지운다. 조각을 남기지 않도록 매칭 구간을 한 번에 표시한 뒤 지운다.
        // "city pop"처럼 두 단어로 된 별칭은 "pop"만 지우면 "city"가 남기 때문이다.
        boolean[] consumed = new boolean[body.length()];
        for (String pattern : WORDS.values()) {
            java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(body);
            while (matcher.find()) for (int i=matcher.start();i<matcher.end();i++) consumed[i]=true;
        }
        for (String pattern : LANGUAGE_WORDS.values()) {
            java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(body);
            while (matcher.find()) for (int i=matcher.start();i<matcher.end();i++) consumed[i]=true;
        }
        StringBuilder remaining = new StringBuilder();
        for (int i=0;i<body.length();i++) remaining.append(consumed[i]?' ':body.charAt(i));
        List<String> result = new ArrayList<>();
        for (String token : SEPARATORS.split(remaining)) {
            if (token.isEmpty()) continue;
            String stripped = token.replaceAll("(?:은|는|을|를|이|가|도|만|와|과|으로|로|에|의|께|서|까지|부터|할|하게|한|게|때|날|더)$", "");
            if (stripped.isEmpty() || FILLER.matcher(stripped).matches()) continue;
            result.add(token);
        }
        return result.size() > 8 ? new ArrayList<>(result.subList(0, 8)) : result;
    }

    /** 부정어가 대상 앞에 오는가. "안 잔잔한"은 참, "잔잔한 곡은 제외"는 거짓. */
    private static boolean frontNegated(String input,int start){
        String head=input.substring(0,start);
        return head.matches("(?s).*(?:\\b(?:no|not|never|without|except)\\s+|(?:안|못)\\s*)$");
    }

    private static boolean negative(String input,int start,int end){
        String tail=input.substring(end,Math.min(input.length(),end+32)),head=input.substring(0,start);
        return tail.matches("^[한은는을를가이도만\\s]*(?:음악|노래|곡|건|장르|가사|보컬|music|songs?|lyrics?|vocals?)?[은는을를가이도만\\s]*(?:빼|제외|말고|싫|아니|않|안|없|ㄴㄴ|노노|no).*" )
                ||head.matches("(?s).*\\b(?:no|not|never|exclude[ds]?|excluding|avoid|without|except|don't|don’t|dislike|hate)\\s+$");
    }
    private static List<int[]> spansOf(java.util.regex.Matcher ignored, String input, List<int[]> span){return span;}
    private static boolean inside(List<int[]> spans,int start,int end){
        for (int[] span : spans) if (span[0] <= start && end <= span[1]) return true;
        return false;
    }
    private static final class LanguageMention {
        final String code;final int start,end;boolean negative;
        LanguageMention(String code,int start,int end){this.code=code;this.start=start;this.end=end;}
    }
    private static List<LanguageMention> languages(String input){
        List<LanguageMention> mentions=new ArrayList<>();
        for(var language:LANGUAGE_WORDS.entrySet()){var match=Pattern.compile(language.getValue()).matcher(input);while(match.find())mentions.add(new LanguageMention(language.getKey(),match.start(),match.end()));}
        mentions.sort(Comparator.comparingInt(mention->mention.start));
        for(int first=0;first<mentions.size();){int end=first+1;
            while(end<mentions.size()&&input.substring(mentions.get(end-1).end,mentions.get(end).start).matches("(?:[\\s,;/·]|and|or|및|와|과|랑|이랑|그리고|또는)*"))end++;
            boolean excluded=negative(input,mentions.get(first).start,mentions.get(end-1).end);
            for(int i=first;i<end;i++)mentions.get(i).negative=excluded;first=end;
        }
        return mentions;
    }
    public static boolean languageExcluded(String request,String language){
        if(language==null||language.isEmpty())return false;
        for(LanguageMention mention:languages(Normalizer.normalize(request,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)))
            if(mention.negative&&(language.equals(mention.code)||language.startsWith(mention.code+"-")))return true;
        return false;
    }
    public static boolean ambiguousLanguage(String request){
        Set<String> choices=new LinkedHashSet<>();for(LanguageMention mention:languages(Normalizer.normalize(request,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)))choices.add(mention.code);
        return choices.size()>1;
    }

    public static Conditions validate(Conditions c) {
        if (c == null || c.tags == null || c.excludedTags == null || c.language == null
                || c.tags.size()>64 || c.excludedTags.size()>64 || !c.tags.stream().allMatch(Recommender::validTag) || !c.excludedTags.stream().allMatch(Recommender::validTag)
                || !validLanguage(c.language))
            throw new IllegalArgumentException("추천 조건을 해석하지 못했습니다.");
        c.tags = new ArrayList<>(new LinkedHashSet<>(c.tags));
        c.excludedTags = new ArrayList<>(new LinkedHashSet<>(c.excludedTags));
        if(c.unread==null)c.unread=new ArrayList<>();
        c.unread = new ArrayList<>(new LinkedHashSet<>(c.unread));
        c.tags.removeAll(c.excludedTags);
        // "가사 없는/가사ㄴㄴ"은 연주곡을 원하는 요청이다. "연주곡 제외"는 반대이므로 건드리지 않는다.
        if (c.tags.contains("instrumental")) c.language = "none";

        if (c.hardTags == null) c.hardTags = new ArrayList<>();
        if (c.softTags == null) c.softTags = new ArrayList<>();
        if (c.excludedArtists == null) c.excludedArtists = new ArrayList<>();
        c.excludedArtists = c.excludedArtists.stream().map(Recommender::normalizeArtist)
                .filter(name -> !name.isEmpty()).distinct().toList();
        if (c.hardTags.isEmpty()) c.hardTags = c.tags.stream().filter(Recommender::isHardTag).toList();
        if (c.softTags.isEmpty()) c.softTags = c.tags.stream().filter(Recommender::isSoftTag).toList();
        return c;
    }

    /** 가수 제외 비교용 정규화. */
    public static String normalizeArtist(String value){return primaryArtist(value);}

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
        List<String> hardGenres = c.hardTags.stream().filter(Recommender::genre).toList();
        for (Track track : catalogue) {
            if (!track.usable() || excludedTrackIds.contains(track.id) || !seen.add(track.id) || feedback.getOrDefault(track.id, 0) == -1
                    || !Collections.disjoint(track.tags, c.excludedTags)
                    || (!c.language.isEmpty() && (c.language.equals("unsupported")||!(c.language.equals(track.language)||track.language.startsWith(c.language+"-")))))
                continue;
            // 직접 제외한 가수는 표기가 달라도 결과에서 뺀다.
            String artist = normalizeArtist(track.artist);
            boolean artistExcluded = false;
            for (String excluded : c.excludedArtists)
                if (artist.equals(excluded) || artist.startsWith(excluded)) { artistExcluded = true; break; }
            if (artistExcluded) continue;
            // 필수 장르는 완충 없이 만족해야 한다.
            if (c.tags.contains("unsupported_genre")
                    || (!hardGenres.isEmpty() && Collections.disjoint(hardGenres, track.tags)))
                continue;
            // 필수 비장르 태그(연주곡 등)도 곡 태그에 있어야 한다.
            boolean hardSatisfied = true;
            for (String tag : c.hardTags) {
                if (genre(tag)) continue;
                if (!track.tags.contains(tag)) { hardSatisfied = false; break; }
            }
            if (!hardSatisfied) continue;
            int score = feedback.getOrDefault(track.id, 0) == 1 ? 1 : 0;
            // 선호 조건은 곡을 버리지 않고 순서만 바꾼다.
            for (String tag : c.softTags) {
                if (!track.tags.contains(tag)) continue;
                score += mood(tag) ? 6 : 4;
            }
            for (String tag : track.tags) {
                if (c.hardTags.contains(tag)) score += 10;
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
            for (String tag : c.tags) if (track.tags.contains(tag)) matched.add(label(tag));
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
        String notice;
        if (picks.isEmpty() && !hardGenres.isEmpty())
            notice = "요청한 장르 조건(" + hardGenres.stream().map(Recommender::label).collect(java.util.stream.Collectors.joining(", "))
                    + ")에 맞는 곡을 확인하지 못해 이번 추천은 0곡이에요. 조건을 줄이거나 다른 장르로 시도해 보세요.";
        else if (picks.isEmpty() && !c.language.isEmpty() && !c.language.equals("none"))
            notice = "요청한 언어 조건에 맞는 곡을 확인하지 못해 이번 추천은 0곡이에요. 조건을 바꿔 보세요.";
        else if (picks.isEmpty())
            notice = "조건에 맞는 곡을 확인하지 못했어요. 조건을 줄이면 더 많은 곡을 찾을 수 있어요.";
        else if (picks.size() < 7)
            notice = "조건에 맞는 곡이 " + picks.size() + "곡이에요. 분위기 조건을 줄이면 더 많은 곡을 찾을 수 있어요.";
        else notice = "";
        if (c.tags.isEmpty() && c.language.isEmpty()) notice = "구체적인 분위기나 장르를 더하면 요청에 맞춰 추천할 수 있어요.";
        return new Result(picks, c, mode, notice);
    }
}
