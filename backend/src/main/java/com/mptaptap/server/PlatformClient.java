package com.mptaptap.server;

import com.mptaptap.core.ImportMatch.PlatformTrack;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Official API endpoints only. External data never enters the recommendation catalogue. */
@Component
public class PlatformClient {
    static final List<String> PROVIDERS=List.of("spotify","youtube","apple_music");
    static final int MAX_ITEMS=500;
    public record Tokens(String accessToken,String refreshToken,String accountId,String scopes,Instant expiresAt) {}
    public record SkippedItem(int sourceIndex,String reason) {}
    public record RemotePlaylist(String externalPlaylistId,String title,String sourceUrl,List<PlatformTrack> tracks,boolean truncated,List<Integer> sourceIndices,List<SkippedItem> skippedItems) {
        RemotePlaylist(String id,String title,String url,List<PlatformTrack> tracks,boolean truncated){this(id,title,url,tracks,truncated,java.util.stream.IntStream.range(0,tracks.size()).boxed().toList(),List.of());}
    }
    public record Video(String videoId,String title,String description,int durationSeconds,String sourceUrl) {}
    static final class Failure extends RuntimeException {
        final int status;
        Failure(int status) { super(status==429?"플랫폼 요청 한도에 도달했어요. 잠시 후 다시 시도해 주세요.":status==401?"플랫폼 계정을 다시 연결해 주세요.":status==403?"플랫폼이 이 작업을 허용하지 않았어요. 계정 권한과 앱의 이용 모드를 확인해 주세요.":"플랫폼 응답을 확인하지 못했어요. 상태를 확인한 뒤 다시 시도해 주세요.");this.status=status; }
    }
    private final ObjectMapper json;
    private final Map<String,String> config;
    private final Map<String,String> endpoints;
    private final java.util.concurrent.ScheduledExecutorService bodyDeadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"platform-body-deadline");thread.setDaemon(true);return thread;});
    @PreDestroy void close(){http.shutdownNow();bodyDeadlines.shutdownNow();}
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired PlatformClient(Environment env,ObjectMapper json) {
        this(json,configuration(env),Map.of("spotify","https://api.spotify.com/v1","spotify_auth","https://accounts.spotify.com","youtube","https://www.googleapis.com/youtube/v3","google_auth","https://oauth2.googleapis.com","google_authorize","https://accounts.google.com/o/oauth2/v2/auth","apple_music","https://api.music.apple.com/v1"));
    }
    // Package-only endpoint injection keeps local HTTP tests outside the production config surface.
    PlatformClient(ObjectMapper json,Map<String,String> config,Map<String,String> endpoints) { this.json=json;this.config=Map.copyOf(config);this.endpoints=Map.copyOf(endpoints); }
    private static Map<String,String> configuration(Environment env) {
        Map<String,String> values=new LinkedHashMap<>();
        for(String key:List.of("SPOTIFY_CLIENT_ID","SPOTIFY_REDIRECT_URI","GOOGLE_CLIENT_ID","GOOGLE_CLIENT_SECRET","GOOGLE_REDIRECT_URI","YOUTUBE_API_KEY","PUBLIC_BASE_URL","APPLE_MUSIC_DEVELOPER_TOKEN","APPLE_MUSIC_STOREFRONT")) values.put(key,env.getProperty(key,""));
        return values;
    }
    static String provider(String provider) { if(provider==null||!PROVIDERS.contains(provider))throw new IllegalArgumentException("지원하는 플랫폼을 선택해 주세요.");return provider; }
    String setting(String key) { return config.getOrDefault(key,""); }
    boolean configured(String provider) {
        provider(provider);
        if(provider.equals("apple_music"))return !setting("APPLE_MUSIC_DEVELOPER_TOKEN").isBlank()&&validRedirect(provider);
        String prefix=provider.equals("spotify")?"SPOTIFY":"GOOGLE";
        return !setting(prefix+"_CLIENT_ID").isBlank()&&(provider.equals("spotify")||!setting(prefix+"_CLIENT_SECRET").isBlank())&&validRedirect(provider);
    }
    private boolean validRedirect(String provider) {
        try {
            URI base=URI.create(setting("PUBLIC_BASE_URL")),redirect=URI.create(redirect(provider));
            boolean secure="https".equals(base.getScheme())||("http".equals(base.getScheme())&&List.of("127.0.0.1","localhost","[::1]").contains(base.getHost()));
            return secure&&!(provider.equals("spotify")&&"localhost".equals(base.getHost()))&&base.getHost()!=null&&base.getUserInfo()==null&&base.getQuery()==null&&base.getFragment()==null&&redirect.toString().equals(base.toString().replaceAll("/$","")+"/connections/"+provider+"/callback");
        }catch(IllegalArgumentException error){return false;}
    }
    private String redirect(String provider) { return provider.equals("apple_music")?setting("PUBLIC_BASE_URL").replaceAll("/$","")+"/connections/apple_music/callback":setting((provider.equals("spotify")?"SPOTIFY":"GOOGLE")+"_REDIRECT_URI"); }
    String authorize(String provider,String state,String challenge) {
        if(!configured(provider)||provider.equals("apple_music"))throw new IllegalArgumentException("플랫폼 연결 환경을 먼저 설정해 주세요.");
        Map<String,String> params=new LinkedHashMap<>(Map.of("client_id",setting((provider.equals("spotify")?"SPOTIFY":"GOOGLE")+"_CLIENT_ID"),"response_type","code","redirect_uri",redirect(provider),"state",state,"code_challenge",challenge,"code_challenge_method","S256"));
        params.put("scope",provider.equals("spotify")?"playlist-read-private playlist-read-collaborative playlist-modify-private":"https://www.googleapis.com/auth/youtube.force-ssl");
        if(provider.equals("youtube")){params.put("access_type","offline");params.put("prompt","consent");}
        return (provider.equals("spotify")?endpoints.get("spotify_auth")+"/authorize":endpoints.get("google_authorize"))+"?"+form(params);
    }
    Tokens exchange(String provider,String code,String verifier) {
        Map<String,String> params=new LinkedHashMap<>(Map.of("grant_type","authorization_code","code",code,"code_verifier",verifier,"redirect_uri",redirect(provider),"client_id",setting((provider.equals("spotify")?"SPOTIFY":"GOOGLE")+"_CLIENT_ID")));
        if(provider.equals("youtube"))params.put("client_secret",setting("GOOGLE_CLIENT_SECRET"));
        JsonNode reply=send("POST",tokenEndpoint(provider),null,form(params),"application/x-www-form-urlencoded");
        String access=required(reply,"access_token");
        return new Tokens(access,reply.path("refresh_token").asString(""),account(provider,access),reply.path("scope").asString(""),Instant.now().plusSeconds(Math.max(30,reply.path("expires_in").asLong(3600))));
    }
    Tokens refresh(String provider,Tokens previous) {
        if(provider.equals("apple_music")||previous.refreshToken().isEmpty())throw new Failure(401);
        Map<String,String> params=new LinkedHashMap<>(Map.of("grant_type","refresh_token","refresh_token",previous.refreshToken(),"client_id",setting((provider.equals("spotify")?"SPOTIFY":"GOOGLE")+"_CLIENT_ID")));
        if(provider.equals("youtube"))params.put("client_secret",setting("GOOGLE_CLIENT_SECRET"));
        JsonNode reply=send("POST",tokenEndpoint(provider),null,form(params),"application/x-www-form-urlencoded");
        return new Tokens(required(reply,"access_token"),reply.path("refresh_token").asString(previous.refreshToken()),previous.accountId(),reply.path("scope").asString(previous.scopes()),Instant.now().plusSeconds(Math.max(30,reply.path("expires_in").asLong(3600))));
    }
    private String tokenEndpoint(String provider) { return provider.equals("spotify")?endpoints.get("spotify_auth")+"/api/token":endpoints.get("google_auth")+"/token"; }
    Tokens appleToken(String userToken) {
        if(!configured("apple_music")||userToken==null||userToken.isBlank()||userToken.length()>8192||userToken.chars().anyMatch(c->c<33||c>126))throw new IllegalArgumentException("MusicKit에서 허용한 Apple Music 사용자 토큰이 필요해요.");
        String account=account("apple_music",userToken);
        return new Tokens(userToken,"",account,"library",Instant.now().plus(Duration.ofDays(30)));
    }
    void revoke(String provider,Tokens tokens) {
        if(provider.equals("youtube"))send("POST",endpoints.get("google_auth")+"/revoke",null,form(Map.of("token",tokens.refreshToken().isEmpty()?tokens.accessToken():tokens.refreshToken())),"application/x-www-form-urlencoded");
        // Spotify and Apple expose user-controlled revocation, not a documented token revoke endpoint.
    }
    private String account(String provider,String token) {
        if(provider.equals("spotify"))return required(api(provider,"GET","/me",token,null),"id");
        JsonNode rows=api(provider,"GET",provider.equals("youtube")?"/channels?part=id&mine=true":"/me/storefront",token,null).path(provider.equals("youtube")?"items":"data");
        if(rows.isEmpty())throw new Failure(403);
        return required(rows.get(0),"id");
    }
    Video video(String id,String token) {
        validId("youtube",id);
        if((token==null||token.isEmpty())&&setting("YOUTUBE_API_KEY").isEmpty())throw new IllegalArgumentException("YouTube Data API 키 또는 연결된 계정이 필요해요. 직접 가진 타임라인은 별도로 변환할 수 있어요.");
        String path="/videos?part=snippet,contentDetails&id="+id;
        if(token==null||token.isEmpty())path+="&key="+encode(setting("YOUTUBE_API_KEY"));
        JsonNode rows=api("youtube","GET",path,token,null).path("items");
        if(rows.isEmpty())throw new Failure(404);
        JsonNode row=rows.get(0);
        int duration;
        try { duration=Math.toIntExact(Duration.parse(row.path("contentDetails").path("duration").asString()).getSeconds()); }
        catch(RuntimeException error){throw new Failure(502);}
        return new Video(id,row.path("snippet").path("title").asString(""),row.path("snippet").path("description").asString(""),duration,trackUrl("youtube",id));
    }
    RemotePlaylist playlist(String provider,String id,Tokens tokens) {
        validPlaylistId(provider,id);
        if(provider.equals("spotify")) {
            JsonNode detail=api(provider,"GET","/playlists/"+id,tokens.accessToken(),null);
            if(!detail.path("owner").path("id").asString().equals(tokens.accountId())&&!detail.path("collaborative").asBoolean())throw new Failure(403);
            return spotifyPlaylist(id,detail.path("name").asString(""),tokens.accessToken());
        }
        if(provider.equals("youtube")) {
            JsonNode rows=api(provider,"GET","/playlists?part=snippet,status,contentDetails&id="+id,tokens.accessToken(),null).path("items");
            if(rows.isEmpty())throw new Failure(404);
            JsonNode detail=rows.get(0);
            if(!detail.path("snippet").path("channelId").asString().equals(tokens.accountId()))throw new Failure(403);
            return youtubePlaylist(id,detail.path("snippet").path("title").asString(""),tokens.accessToken());
        }
        String libraryId=id;
        if(!id.startsWith("p.")){
            libraryId="";
            for(int offset=0;offset<MAX_ITEMS;offset+=100){JsonNode page=api(provider,"GET","/me/library/playlists?limit=100&offset="+offset,tokens.accessToken(),null);for(JsonNode row:page.path("data"))if(id.equals(row.path("attributes").path("playParams").path("globalId").asString("")))libraryId=required(row,"id");if(!libraryId.isEmpty()||page.path("next").asString("").isEmpty())break;}
            if(libraryId.isEmpty())throw new Failure(403);
        }
        JsonNode detail=api(provider,"GET","/me/library/playlists/"+libraryId,tokens.accessToken(),null).path("data");
        if(detail.isEmpty())throw new Failure(404);
        RemotePlaylist playlist=applePlaylist(libraryId,detail.get(0).path("attributes").path("name").asString(""),tokens.accessToken());
        return new RemotePlaylist(id,playlist.title(),"https://music.apple.com/library/playlist/"+id,playlist.tracks(),playlist.truncated());
    }
    private RemotePlaylist spotifyPlaylist(String id,String title,String token) {
        List<PlatformTrack> tracks=new ArrayList<>();List<Integer> sourceIndices=new ArrayList<>();List<SkippedItem> skipped=new ArrayList<>();boolean more=false;
        for(int offset=0;offset<MAX_ITEMS;offset+=50) {
            JsonNode page=api("spotify","GET","/playlists/"+id+"/items?limit=50&offset="+offset,token,null);
            int sourceIndex=offset;
            for(JsonNode row:page.path("items")) {
                JsonNode item=row.path("item");if(item.isMissingNode())item=row.path("track");
                String reason=row.path("is_local").asBoolean()||item.path("is_local").asBoolean()?"로컬 파일은 다른 플랫폼에서 찾을 수 없어요.":!item.path("type").asString("track").equals("track")?"음악 곡이 아닌 항목은 이전하지 않아요.":item.path("id").asString("").isEmpty()?"플랫폼에서 곡 정보를 제공하지 않았어요.":"";
                if(reason.isEmpty()){tracks.add(spotifyTrack(item));sourceIndices.add(sourceIndex);}else skipped.add(new SkippedItem(sourceIndex,reason));
                sourceIndex++;
            }
            more=!page.path("next").isNull()&&!page.path("next").asString("").isEmpty();if(!more)break;
        }
        return new RemotePlaylist(id,title,"https://open.spotify.com/playlist/"+id,tracks,more,sourceIndices,skipped);
    }
    private RemotePlaylist youtubePlaylist(String id,String title,String token) {
        List<PlatformTrack> tracks=new ArrayList<>();String pageToken="";
        for(int page=0;page<10;page++) {
            JsonNode result=api("youtube","GET","/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId="+id+(pageToken.isEmpty()?"":"&pageToken="+encode(pageToken)),token,null);
            for(JsonNode row:result.path("items")) {
                String video=row.path("contentDetails").path("videoId").asString(row.path("snippet").path("resourceId").path("videoId").asString(""));
                if(!video.matches("[A-Za-z0-9_-]{11}"))continue;
                tracks.add(track("youtube",video,row.path("snippet").path("title").asString(""),row.path("snippet").path("videoOwnerChannelTitle").asString(""),0,"",""));
            }
            pageToken=result.path("nextPageToken").asString("");if(pageToken.isEmpty())break;
        }
        return new RemotePlaylist(id,title,"https://www.youtube.com/playlist?list="+id,tracks,!pageToken.isEmpty());
    }
    private RemotePlaylist applePlaylist(String id,String title,String token) {
        List<PlatformTrack> tracks=new ArrayList<>();boolean more=false;
        for(int offset=0;offset<MAX_ITEMS;offset+=100) {
            JsonNode page=api("apple_music","GET","/me/library/playlists/"+id+"/tracks?limit=100&offset="+offset,token,null);
            for(JsonNode row:page.path("data"))tracks.add(appleTrack(row));
            more=!page.path("next").asString("").isEmpty();if(!more)break;
        }
        return new RemotePlaylist(id,title,"https://music.apple.com/library/playlist/"+id,tracks,more);
    }
    List<PlatformTrack> search(String provider,PlatformTrack source,Tokens tokens) {
        String query=(source.title+" "+source.artist).strip();if(query.length()>300)query=query.substring(0,300);
        List<PlatformTrack> result=new ArrayList<>();
        if(provider.equals("spotify")) {
            if(source.isrc!=null&&!source.isrc.isEmpty())query="isrc:"+source.isrc;
            for(JsonNode row:api(provider,"GET","/search?type=track&limit=10&q="+encode(query),tokens.accessToken(),null).path("tracks").path("items"))result.add(spotifyTrack(row));
        }else if(provider.equals("youtube")) {
            for(JsonNode row:api(provider,"GET","/search?part=snippet&type=video&maxResults=5&q="+encode(query),tokens.accessToken(),null).path("items")) {
                String id=row.path("id").path("videoId").asString("");if(id.matches("[A-Za-z0-9_-]{11}"))result.add(track(provider,id,row.path("snippet").path("title").asString(""),row.path("snippet").path("channelTitle").asString(""),0,"",""));
            }
        }else {
            String storefront=setting("APPLE_MUSIC_STOREFRONT");if(!storefront.matches("[a-z]{2}"))storefront="us";
            for(JsonNode row:api(provider,"GET","/catalog/"+storefront+"/search?types=songs&limit=10&term="+encode(query),tokens.accessToken(),null).path("results").path("songs").path("data"))result.add(appleTrack(row));
        }
        return result;
    }
    String findCreated(String provider,String marker,Tokens tokens) {
        String pageToken="";
        for(int page=0;page<10;page++) {
            String path=provider.equals("spotify")?"/me/playlists?limit=50&offset="+(page*50):provider.equals("youtube")?"/playlists?part=snippet&mine=true&maxResults=50"+(pageToken.isEmpty()?"":"&pageToken="+encode(pageToken)):"/me/library/playlists?limit=100&offset="+(page*100);
            JsonNode reply=api(provider,"GET",path,tokens.accessToken(),null);
            for(JsonNode p:reply.path(provider.equals("apple_music")?"data":"items")) {
                JsonNode description=provider.equals("spotify")?p.path("description"):p.path(provider.equals("youtube")?"snippet":"attributes").path("description");
                String text=description.isObject()?description.path("standard").asString(""):description.asString("");
                if(text.equals(marker))return required(p,"id");
            }
            if(provider.equals("youtube")){pageToken=reply.path("nextPageToken").asString("");if(pageToken.isEmpty())break;}
            else if(reply.path("next").asString("").isEmpty())break;
        }
        return "";
    }
    String createPrivate(String provider,String title,String marker,Tokens tokens) {
        Object body;String path;
        if(provider.equals("spotify")){path="/me/playlists";body=Map.of("name",title,"public",false,"description",marker);}
        else if(provider.equals("youtube")){path="/playlists?part=snippet,status";body=Map.of("snippet",Map.of("title",title,"description",marker),"status",Map.of("privacyStatus","private"));}
        else{path="/me/library/playlists";body=Map.of("attributes",Map.of("name",title,"description",marker));}
        JsonNode reply=api(provider,"POST",path,tokens.accessToken(),body);
        return required(provider.equals("apple_music")?reply.path("data").get(0):reply,"id");
    }
    List<String> existingIds(String provider,String id,Tokens tokens) {
        RemotePlaylist p=provider.equals("spotify")?spotifyPlaylist(id,"",tokens.accessToken()):provider.equals("youtube")?youtubePlaylist(id,"",tokens.accessToken()):applePlaylist(id,"",tokens.accessToken());
        if(p.truncated())throw new Failure(409);
        return p.tracks().stream().map(t->t.externalId).toList();
    }
    void add(String provider,String playlist,String externalId,Tokens tokens) {
        validPlaylistId(provider,playlist);validId(provider,externalId);
        if(provider.equals("spotify"))api(provider,"POST","/playlists/"+playlist+"/items",tokens.accessToken(),Map.of("uris",List.of("spotify:track:"+externalId)));
        else if(provider.equals("youtube"))api(provider,"POST","/playlistItems?part=snippet",tokens.accessToken(),Map.of("snippet",Map.of("playlistId",playlist,"resourceId",Map.of("kind","youtube#video","videoId",externalId))));
        else api(provider,"POST","/me/library/playlists/"+playlist+"/tracks",tokens.accessToken(),Map.of("data",List.of(Map.of("id",externalId,"type","songs"))));
    }
    private JsonNode api(String provider,String method,String path,String token,Object body) {
        return send(method,endpoints.get(provider)+path,provider.equals("apple_music")?setting("APPLE_MUSIC_DEVELOPER_TOKEN"):token,body==null?null:json.writeValueAsString(body),"application/json",provider.equals("apple_music")?token:null);
    }
    private JsonNode send(String method,String url,String token,String body,String contentType) { return send(method,url,token,body,contentType,null); }
    private JsonNode send(String method,String url,String token,String body,String contentType,String musicUserToken) {
        if((token!=null&&(!token.isEmpty())&&(token.length()>8192||token.chars().anyMatch(c->c<33||c>126)))||(musicUserToken!=null&&(musicUserToken.length()>8192||musicUserToken.chars().anyMatch(c->c<33||c>126))))throw new Failure(502);
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if(token!=null&&!token.isEmpty())request.header("Authorization","Bearer "+token);
        if(musicUserToken!=null&&!musicUserToken.isEmpty())request.header("Music-User-Token",musicUserToken);
        if(body!=null)request.header("Content-Type",contentType);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        try {
            HttpResponse<java.io.InputStream> response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            java.util.concurrent.atomic.AtomicBoolean expired=new java.util.concurrent.atomic.AtomicBoolean();
            var timer=bodyDeadlines.schedule(()->{expired.set(true);try{response.body().close();}catch(IOException ignored){}},Math.max(1,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);
            try(var input=response.body()) {
                byte[] bytes=input.readNBytes(2*1024*1024+1);
                if(expired.get())throw new Failure(504);
                if(response.statusCode()<200||response.statusCode()>=300)throw new Failure(response.statusCode());
                if(bytes.length>2*1024*1024)throw new Failure(502);
                return bytes.length==0?json.createObjectNode():json.readTree(bytes);
            }finally{timer.cancel(false);}
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new Failure(503);}
        catch(IOException|tools.jackson.core.JacksonException error){throw new Failure(502);}
    }
    static String playlistUrl(String provider,String id){if(id==null||id.isEmpty())return "";validPlaylistId(provider,id);return provider.equals("spotify")?"https://open.spotify.com/playlist/"+id:provider.equals("youtube")?"https://www.youtube.com/playlist?list="+id:"";}
    static String playlistId(String provider,String url) {
        provider(provider);URI uri;
        try{uri=URI.create(url);}catch(RuntimeException error){throw new IllegalArgumentException("플랫폼의 원본 목록 URL을 입력해 주세요.");}
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IllegalArgumentException("HTTPS 원본 목록 URL을 입력해 주세요.");
        String host=uri.getHost(),id="";
        if(provider.equals("spotify")&&"open.spotify.com".equals(host)&&uri.getPath().matches("/playlist/[A-Za-z0-9]+"))id=uri.getPath().substring(10);
        if(provider.equals("youtube")&&List.of("www.youtube.com","youtube.com","music.youtube.com").contains(host)&&"/playlist".equals(uri.getPath()))id=query(uri,"list");
        if(provider.equals("apple_music")&&"music.apple.com".equals(host)&&uri.getPath().contains("/playlist/"))id=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
        validPlaylistId(provider,id);return id;
    }
    static String videoId(String url) {
        URI uri;try{uri=URI.create(url);}catch(RuntimeException error){throw new IllegalArgumentException("YouTube 영상 URL을 확인해 주세요.");}
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IllegalArgumentException("YouTube HTTPS 영상 URL을 입력해 주세요.");
        String id="";
        if("youtu.be".equals(uri.getHost()))id=uri.getPath().substring(1);
        else if(List.of("youtube.com","www.youtube.com","music.youtube.com").contains(uri.getHost())&&"/watch".equals(uri.getPath()))id=query(uri,"v");
        validId("youtube",id);return id;
    }
    private static String query(URI uri,String key) {
        if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){String[] parts=pair.split("=",2);if(parts.length==2&&parts[0].equals(key))return java.net.URLDecoder.decode(parts[1],StandardCharsets.UTF_8);}
        return "";
    }
    static void validId(String provider,String id) { if(id==null||!id.matches(provider.equals("spotify")?"[A-Za-z0-9]{22}":provider.equals("youtube")?"[A-Za-z0-9_-]{11}":"[A-Za-z0-9.-]{1,100}"))throw new IllegalArgumentException("플랫폼 곡 ID를 확인해 주세요."); }
    static void validPlaylistId(String provider,String id) { if(id==null||!id.matches(provider.equals("spotify")?"[A-Za-z0-9]{22}":provider.equals("apple_music")?"[A-Za-z0-9._-]{1,100}":"[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("플랫폼 목록 ID를 확인해 주세요."); }
    private static String required(JsonNode row,String field) { if(row==null||row.path(field).asString("").isBlank())throw new Failure(502);return row.path(field).asString(); }
    static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static String form(Map<String,String> params) { return params.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&")); }
    private static PlatformTrack spotifyTrack(JsonNode row) {
        List<String> names=new ArrayList<>();for(JsonNode artist:row.path("artists"))names.add(artist.path("name").asString(""));String artists=String.join(", ",names);
        return track("spotify",row.path("id").asString(""),row.path("name").asString(""),artists,row.path("duration_ms").asLong(0),row.path("external_ids").path("isrc").asString(""),row.path("album").path("name").asString(""));
    }
    private static PlatformTrack appleTrack(JsonNode row) {
        JsonNode a=row.path("attributes");return track("apple_music",a.path("playParams").path("catalogId").asString(row.path("id").asString("")),a.path("name").asString(""),a.path("artistName").asString(""),a.path("durationInMillis").asLong(0),a.path("isrc").asString(""),a.path("albumName").asString(""));
    }
    private static PlatformTrack track(String platform,String id,String title,String artist,long duration,String isrc,String album) {
        PlatformTrack t=new PlatformTrack();t.platform=platform;t.externalId=id;t.title=title;t.artist=artist;t.durationMs=duration;t.isrc=isrc;t.album=album;t.version="";t.sourceUrl=trackUrl(platform,id);return t;
    }
    private static String trackUrl(String provider,String id) { return provider.equals("spotify")?"https://open.spotify.com/track/"+id:provider.equals("youtube")?"https://www.youtube.com/watch?v="+id:"https://music.apple.com/song/"+id; }
}
