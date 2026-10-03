package com.mptaptap.server;

import com.mptaptap.core.ImportMatch;
import com.mptaptap.core.ImportMatch.PlatformTrack;
import com.mptaptap.core.VideoTimeline;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class PlatformApi {
    private final Store users;
    private final PlatformStore store;
    private final PlatformClient remote;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ExecutorService transfers=Executors.newFixedThreadPool(2);
    // ponytail: serialize provider writes; per-account serialization if throughput requires it.
    private final Object transferLock=new Object();
    private final java.util.Set<String> activeTransfers=java.util.concurrent.ConcurrentHashMap.newKeySet();
    PlatformApi(Store users,PlatformStore store,PlatformClient remote,ObjectMapper json,PlatformTransactionManager manager){this.users=users;this.store=store;this.remote=remote;this.json=json;this.tx=new TransactionTemplate(manager);}
    @PreDestroy void close(){transfers.shutdownNow();}
    private String user(String auth){return users.user(auth);}
    private static String provider(String p){return PlatformClient.provider("youtube_music".equals(p)?"youtube":p);}

    @GetMapping("/connections/config") public Map<String,Object> config(@RequestHeader(value="Authorization",required=false)String auth){
        user(auth);List<Map<String,Object>> providers=new ArrayList<>();
        for(String p:PlatformClient.PROVIDERS)providers.add(Map.of("provider",p,"configured",store.configured()&&remote.configured(p),"capabilities",List.of("import","match","transfer","explicit_sync"),"notice",p.equals("spotify")?"Spotify의 앱 이용 모드·계정·읽기/쓰기 권한 제한이 적용돼요.":"YouTube Data API 권한·할당량이 적용돼요."));
        return Map.of("providers",providers,"tokenStorageConfigured",store.configured());
    }
    @GetMapping("/connections") public List<Map<String,Object>> connections(@RequestHeader(value="Authorization",required=false)String auth){return store.connections(user(auth)).stream().filter(row->PlatformClient.PROVIDERS.contains(row.get("provider"))).toList();}
    @PostMapping("/connections/{platform}/start") public Map<String,String> start(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String platform){
        String u=user(auth),p=provider(platform);if(!store.configured()||!remote.configured(p))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"서버의 플랫폼 연결 환경과 암호화 키를 먼저 설정해 주세요.");
        Map<String,String> state=store.start(u,p,auth);String url=remote.authorize(p,state.get("state"),state.get("challenge"));
        return Map.of("authorizationUrl",url,"state",state.get("state"),"expiresAt",state.get("expiresAt"));
    }
    @GetMapping("/connections/{platform}/callback") public synchronized ResponseEntity<Void> callback(@PathVariable String platform,@RequestParam String state,@RequestParam(required=false)String code,@RequestParam(required=false)String error){
        String p=provider(platform);PlatformStore.Pending pending=store.pending(state,p,true);
        String status="error";
        if(error==null&&code!=null&&!code.isBlank()&&code.length()<4096)try{store.connection(pending.user(),p,remote.exchange(p,code,pending.verifier()));status="connected";}catch(RuntimeException ignored){}
        return ResponseEntity.status(302).location(URI.create("taptap://connected?provider="+p+"&status="+status)).build();
    }
    @DeleteMapping("/connections/{platform}") public synchronized Map<String,Object> unlink(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String platform){
        String u=user(auth),p=provider(platform);boolean revoked=false;
        try{remote.revoke(p,store.tokens(u,p));revoked=p.equals("youtube");}catch(RuntimeException ignored){}finally{store.unlink(u,p);users.unlinkExternal(u,p);}
        return Map.of("unlinked",true,"providerRevocationRequired",!revoked,"manageAccessUrl",p.equals("spotify")?"https://www.spotify.com/account/apps/":"https://myaccount.google.com/connections");
    }
    private synchronized PlatformClient.Tokens token(String u,String p){
        p=provider(p);
        PlatformClient.Tokens token=store.tokens(u,p);
        if(!token.expiresAt().isAfter(Instant.now().plusSeconds(30))){token=remote.refresh(p,token);store.connection(u,p,token);}return token;
    }
    public record Conversion(String id,String url,String timeline,Integer durationSeconds){}
    @PostMapping("/conversions/youtube") public synchronized Map<String,Object> conversion(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Conversion body){
        String u=user(auth),video=PlatformClient.videoId(body.url()),id=body.id()==null?UUID.randomUUID().toString():PlatformStore.uuid(body.id());
        String fingerprint=PlatformStore.hash(json.writeValueAsString(body));
        if(store.exists(u,id,"import")){Map<String,Object> existing=store.document(u,id,"import");if(!fingerprint.equals(existing.get("requestFingerprint")))throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 작업 ID로 원래 변환을 다시 시도해 주세요.");return existing;}String title="사용자 타임라인",description=body.timeline(),mode="provided_timeline";int duration=body.durationSeconds()==null?-1:body.durationSeconds();
        if(description==null){String access=null;try{access=token(u,"youtube").accessToken();}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=409)throw missing;}PlatformClient.Video metadata=remote.video(video,access);title=metadata.title();description=metadata.description();duration=metadata.durationSeconds();mode="official_metadata";}
        if(duration<-1||duration>21600)throw new IllegalArgumentException("영상 길이는 최대 6시간까지 지원해요.");VideoTimeline.Result parsed=VideoTimeline.parse(description,video,duration);List<Map<String,Object>> entries=new ArrayList<>(),items=new ArrayList<>();
        for(int i=0;i<parsed.entries.size();i++){VideoTimeline.Entry entry=parsed.entries.get(i);Map<String,Object> row=map(entry);row.put("sourceIndex",i);entries.add(row);PlatformTrack track=new PlatformTrack();track.platform="youtube";track.externalId=video;track.title=entry.title;track.artist=entry.artist;track.sourceUrl=entry.sourceUrl;track.durationMs=0;Map<String,Object> item=new LinkedHashMap<>();item.put("sourceIndex",i);item.put("track",map(track));item.put("sourceRow",entry.sourceRow);item.put("timestamp",entry.seconds);item.put("sourceType","timeline");item.put("videoDurationSeconds",Math.max(0,duration));item.put("segmentDurationMs",entry.endSeconds>entry.startSeconds&&entry.startSeconds>=0?(entry.endSeconds-entry.startSeconds)*1000L:0);item.put("recommendationTrackId",null);items.add(item);}
        Map<String,Object> result=new LinkedHashMap<>();result.putAll(Map.of("id",id,"provider","youtube","videoId",video,"externalPlaylistId","","title",title,"durationSeconds",duration,"sourceUrl",body.url(),"entries",entries,"truncated",parsed.truncated,"metadataMode",mode));result.put("items",items);result.put("notice",entries.isEmpty()?"설명에서 타임라인을 찾지 못했어요. 직접 가진 타임라인을 붙여넣어 주세요.":"곡명·아티스트·시간을 확인해 저장해 주세요. 음원 분리나 곡 추측은 하지 않아요.");result.put("saved",false);result.put("requestFingerprint",fingerprint);store.create(u,id,"import","youtube",result);return result;
    }
    public record ImportBody(String id,String provider,String url,String title){}
    @PostMapping("/imports/playlists") public synchronized Map<String,Object> importPlaylist(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ImportBody body){
        String u=user(auth),p=provider(body.provider()),id=body.id()==null?UUID.randomUUID().toString():PlatformStore.uuid(body.id()),source=PlatformClient.playlistId(p,body.url());
        if(store.exists(u,id,"import")){Map<String,Object> previous=store.document(u,id,"import");if(!p.equals(previous.get("provider"))||!source.equals(previous.get("externalPlaylistId")))throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 작업 ID로 원래 목록을 다시 시도해 주세요.");if(!Boolean.TRUE.equals(previous.get("saved"))&&!items(previous,"items").isEmpty())saveImported(u,id,new SaveImport((String)previous.get("title"),null,null));return store.document(u,id,"import");}
        PlatformClient.RemotePlaylist playlist=remote.playlist(p,source,token(u,p));Map<String,Object> result=importDocument(id,p,playlist,body.title());store.create(u,id,"import",p,result);if(!playlist.tracks().isEmpty())saveImported(u,id,new SaveImport((String)result.get("title"),null,null));return store.document(u,id,"import");
    }
    private Map<String,Object> importDocument(String id,String p,PlatformClient.RemotePlaylist remote,String title){
        List<Map<String,Object>> items=new ArrayList<>();for(int i=0;i<remote.tracks().size();i++){Map<String,Object> item=new LinkedHashMap<>();item.put("sourceIndex",remote.sourceIndices().get(i));item.put("track",map(remote.tracks().get(i)));item.put("recommendationTrackId",null);items.add(item);}
        Map<String,Object> value=new LinkedHashMap<>(Map.of("id",id,"provider",p,"externalPlaylistId",remote.externalPlaylistId(),"title",title(title==null||title.isBlank()?remote.title():title),"sourceUrl",remote.sourceUrl(),"items",items,"truncated",remote.truncated(),"saved",false));
        String notice=remote.truncated()?"최대 500개 항목까지만 조회했어요. 원본 목록의 나머지 곡은 포함하지 않았어요.":"";
        value.put("skippedCount",remote.skippedItems().size());value.put("skippedItems",remote.skippedItems());
        if(!remote.skippedItems().isEmpty()){String reasons=remote.skippedItems().stream().map(row->(row.sourceIndex()+1)+"번째 · "+row.reason()).collect(java.util.stream.Collectors.joining(" / "));notice+=(notice.isEmpty()?"":"\n")+"가져오지 못한 "+remote.skippedItems().size()+"개 항목: "+reasons;}
        value.put("notice",notice);return value;
    }
    @GetMapping("/imports/playlists") public List<Map<String,Object>> imports(@RequestHeader(value="Authorization",required=false)String auth,@RequestParam(defaultValue="false")boolean summary){String u=user(auth);return (summary?store.summaries(u,"import"):store.documents(u,"import")).stream().filter(row->PlatformClient.PROVIDERS.contains(row.get("provider"))).toList();}
    @GetMapping("/imports/playlists/{id}") public Map<String,Object> imported(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){Map<String,Object> data=store.document(user(auth),id,"import");provider((String)data.get("provider"));return data;}
    @DeleteMapping("/imports/playlists/{id}") public synchronized Map<String,Boolean> deleteImport(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){store.deleteImport(user(auth),id);return Map.of("deleted",true);}
    public record TimelineEdit(Integer sourceIndex,String title,String artist,Boolean excluded,Integer seconds){}
    public record SaveImport(String title,String playlistId,List<TimelineEdit> entries){}
    @PostMapping("/imports/playlists/{id}/save") public synchronized Map<String,Object> saveImport(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody SaveImport body){return saveImported(user(auth),id,body);}
    private Map<String,Object> saveImported(String u,String id,SaveImport body){
        Map<String,Object> data=store.document(u,id,"import");provider((String)data.get("provider"));List<Map<String,Object>> items=items(data,"items");Map<Integer,TimelineEdit> edits=new LinkedHashMap<>();
        if(body.entries()!=null){if(body.entries().size()!=items.size())throw new IllegalArgumentException("모든 원본 행을 확인해 주세요.");for(TimelineEdit e:body.entries())if(e.sourceIndex()==null||edits.put(e.sourceIndex(),e)!=null)throw new IllegalArgumentException("원본 행을 중복 없이 지정해 주세요.");}
        List<Map<String,Object>> rows=new ArrayList<>(),reviewed=new ArrayList<>();
        for(Map<String,Object> item:items){
            int index=((Number)item.get("sourceIndex")).intValue();TimelineEdit edit=edits.get(index);
            if(!edits.isEmpty()&&edit==null)throw new IllegalArgumentException("원본 행을 확인해 주세요.");if(edit!=null&&Boolean.TRUE.equals(edit.excluded()))continue;
            Map<String,Object> row=new LinkedHashMap<>(cast(item.get("track"))),checked=new LinkedHashMap<>(item);
            if(edit!=null){row.put("title",text(edit.title(),300));row.put("artist",optionalText(edit.artist(),300));}
            if(item.containsKey("timestamp")){
                int seconds=edit!=null&&edit.seconds()!=null?edit.seconds():((Number)item.get("timestamp")).intValue();
                int length=((Number)item.getOrDefault("videoDurationSeconds",-1)).intValue(),maximum=length>0?length-1:21600;
                if(seconds<0||seconds>maximum)throw new IllegalArgumentException("영상 범위 안의 시간을 입력하거나 해당 행을 제외해 주세요.");
                checked.put("timestamp",seconds);row.put("sourceUrl","https://www.youtube.com/watch?v="+data.get("videoId")+"&t="+seconds+"s");
            }else if(edit!=null&&edit.seconds()!=null)throw new IllegalArgumentException("영상 타임라인의 시간만 수정할 수 있어요.");
            checked.put("track",new LinkedHashMap<>(row));reviewed.add(checked);row.put("sourceIndex",index);if(checked.containsKey("timestamp")){row.put("timestamp",checked.get("timestamp"));row.put("sourceType","timeline");row.put("videoDurationSeconds",Math.max(0,((Number)checked.getOrDefault("videoDurationSeconds",0)).intValue()));}
            if(item.containsKey("sourceRow"))row.put("sourceRow",item.get("sourceRow"));rows.add(row);
        }
        if(rows.isEmpty())throw new IllegalArgumentException("저장할 곡을 하나 이상 선택해 주세요.");String playlist=body.playlistId()==null?id:body.playlistId();String name=title(body.title()==null?(String)data.get("title"):body.title());
        // Keep users -> store monitors through commit; expiry writes must not invert a row lock and the store monitor.
        synchronized(users){synchronized(store){return tx.execute(status->{
            boolean sameSource=Boolean.TRUE.equals(data.get("saved"))&&playlist.equals(data.get("localPlaylistId"));
            Map<String,Object> target;
            if(body.playlistId()==null&&!sameSource)target=users.createPlaylist(u,name,playlist);
            else{try{target=users.ownedPlaylist(u,playlist);}catch(ResponseStatusException missing){if(missing.getStatusCode().value()!=404)throw missing;target=users.createPlaylist(u,name,PlatformStore.uuid(playlist));}}
            Instant checkedAt=store.metadataCheckedAt(u,id);
            List<Map<String,Object>> saved=sameSource&&body.entries()!=null?users.refreshExternal(u,playlist,rows,id,checkedAt):users.addExternal(u,playlist,rows,id,checkedAt);
            if(sameSource)users.rename(u,playlist,name);
            String savedTitle=sameSource?name:(String)target.get("title");
            if(!reviewed.equals(data.get("reviewedItems"))){data.remove("reviews");data.remove("targetProvider");data.remove("nextOffset");}
            data.put("saved",true);data.put("title",savedTitle);data.put("localPlaylistId",playlist);data.put("reviewedItems",reviewed);store.update(u,id,"import",data);
            return Map.of("id",playlist,"title",savedTitle,"items",saved,"size",saved.size());
        });}}
    }
    public record MatchBody(String importId,String targetProvider,Integer offset){}
    @PostMapping("/matches") public synchronized Map<String,Object> matches(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody MatchBody body){
        String u=user(auth),p=provider(body.targetProvider());Map<String,Object> data=store.document(u,body.importId(),"import");provider((String)data.get("provider"));List<Map<String,Object>> source=items(data,data.containsKey("reviewedItems")?"reviewedItems":"items");List<Map<String,Object>> reviews=data.containsKey("reviews")&&p.equals(data.get("targetProvider"))?items(data,"reviews"):new ArrayList<>();
        if(reviews.isEmpty())for(Map<String,Object> item:source){Map<String,Object> r=new LinkedHashMap<>();r.put("sourceIndex",item.get("sourceIndex"));r.put("source",item.get("track"));r.put("candidates",List.of());r.put("status","NOT_SEARCHED");r.put("selected",null);r.put("approved",false);reviews.add(r);}
        int offset=body.offset()==null?0:body.offset();if(offset<0||offset>=Math.max(1,source.size()))throw new IllegalArgumentException("곡 검토 위치를 확인해 주세요.");PlatformClient.Tokens token=token(u,p);int end=Math.min(source.size(),offset+20);
        for(int i=offset;i<end;i++){PlatformTrack track=json.convertValue(source.get(i).get("track"),PlatformTrack.class),scoring=track;
            // A timeline's ID identifies the whole video, not the recording named in this row.
            if("timeline".equals(source.get(i).get("sourceType"))){scoring=json.convertValue(map(track),PlatformTrack.class);scoring.externalId="";}
            ImportMatch.ReviewItem review=ImportMatch.match(scoring,remote.search(p,track,token));review.source=track;Map<String,Object> r=map(review);r.put("sourceIndex",source.get(i).get("sourceIndex"));r.put("approved",false);reviews.set(i,r);
        }
        data.put("reviews",reviews);data.put("targetProvider",p);data.put("nextOffset",end<source.size()?end:-1);store.update(u,body.importId(),"import",data);return Map.of("importId",body.importId(),"targetProvider",p,"items",reviews,"nextOffset",end<source.size()?end:-1);
    }
    public record Selection(Integer sourceIndex,String targetId,Boolean excluded){}
    @GetMapping("/matches/{id}") public Map<String,Object> storedReview(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestParam String targetProvider){Map<String,Object> data=store.document(user(auth),id,"import");provider((String)data.get("provider"));String p=provider(targetProvider);if(!p.equals(data.get("targetProvider")))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"저장된 대상 플랫폼 검토가 없어요.");return Map.of("importId",id,"targetProvider",p,"items",items(data,"reviews"),"nextOffset",data.getOrDefault("nextOffset",0));}
    public record ReviewBody(String targetProvider,List<Selection> selections){}
    @PutMapping("/matches/{id}") public synchronized Map<String,Object> review(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody ReviewBody body){
        String u=user(auth),p=provider(body.targetProvider());Map<String,Object> data=store.document(u,id,"import");provider((String)data.get("provider"));if(!p.equals(data.get("targetProvider")))throw new IllegalArgumentException("먼저 대상 플랫폼의 곡 후보를 조회해 주세요.");List<Map<String,Object>> rows=items(data,"reviews");if(body.selections()==null||body.selections().size()>500)throw new IllegalArgumentException("검토할 곡을 지정해 주세요.");HashSet<Integer> seen=new HashSet<>();
        for(Selection choice:body.selections()){if(choice.sourceIndex()==null||!seen.add(choice.sourceIndex()))throw new IllegalArgumentException("행을 중복 없이 지정해 주세요.");Map<String,Object> row=rows.stream().filter(r->((Number)r.get("sourceIndex")).intValue()==choice.sourceIndex()).findFirst().orElseThrow(()->new IllegalArgumentException("원본 행을 찾을 수 없어요."));if(Boolean.TRUE.equals(choice.excluded())){row.put("status","USER_EXCLUDED");row.put("selected",null);}else{Map<String,Object> selected=items(row,"candidates").stream().map(c->cast(c.get("track"))).filter(t->choice.targetId()!=null&&choice.targetId().equals(t.get("externalId"))).findFirst().orElseThrow(()->new IllegalArgumentException("제시된 후보 곡 중 하나를 선택해 주세요."));row.put("selected",selected);row.put("status","MATCHED");}row.put("approved",true);}
        store.update(u,id,"import",data);return Map.of("importId",id,"targetProvider",p,"items",rows);
    }
    public record TransferBody(String id,String importId,String targetProvider,String title,Boolean confirmed){}
    public record Confirm(Boolean confirmed){}
    @PostMapping("/transfers") public synchronized Map<String,Object> transfer(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody TransferBody body){
        String u=user(auth),id=PlatformStore.uuid(body.id()),p=provider(body.targetProvider());confirmed(body.confirmed());String name=title(body.title());
        if(store.exists(u,id,"transfer")){Map<String,Object> existing=store.document(u,id,"transfer");if(!body.importId().equals(existing.get("importId"))||!p.equals(existing.get("provider"))||!name.equals(existing.get("title")))throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 작업 ID로 원래 이전을 다시 시도해 주세요.");if(!"COMPLETED".equals(existing.get("status")))launch(u,id);return counted(existing);}
        Map<String,Object> source=store.document(u,body.importId(),"import");provider((String)source.get("provider"));if(!p.equals(source.get("targetProvider")))throw new IllegalArgumentException("대상 플랫폼의 곡 후보를 먼저 검토해 주세요.");List<Map<String,Object>> selected=new ArrayList<>();List<Integer> excluded=new ArrayList<>();
        for(Map<String,Object> row:items(source,"reviews")){if(!Boolean.TRUE.equals(row.get("approved")))throw new IllegalArgumentException("모든 곡을 선택하거나 제외한 뒤 이전해 주세요.");if("USER_EXCLUDED".equals(row.get("status"))){excluded.add(((Number)row.get("sourceIndex")).intValue());continue;}Map<String,Object> track=cast(row.get("selected"));Map<String,Object> item=new LinkedHashMap<>(Map.of("sourceIndex",row.get("sourceIndex"),"targetId",track.get("externalId"),"status","PENDING","message",""));selected.add(item);}
        if(selected.isEmpty())throw new IllegalArgumentException("이전할 곡을 하나 이상 선택해 주세요.");token(u,p);Map<String,Object> job=new LinkedHashMap<>(Map.of("id",id,"importId",body.importId(),"provider",p,"title",name,"status","PENDING","externalPlaylistId","","items",selected,"createStarted",false,"playlistUrl","","notice","사용자가 확인한 곡만 비공개 목록으로 이전합니다."));job.put("excludedSourceIndices",excluded);store.create(u,id,"transfer",p,counted(job));launch(u,id);return job;
    }
    @GetMapping("/transfers") public List<Map<String,Object>> transferHistory(@RequestHeader(value="Authorization",required=false)String auth,@RequestParam(defaultValue="false")boolean summary){String u=user(auth);List<Map<String,Object>> jobs=(summary?store.summaries(u,"transfer"):store.documents(u,"transfer")).stream().filter(row->PlatformClient.PROVIDERS.contains(row.get("provider"))).toList();for(Map<String,Object> job:jobs){if(!summary)counted(job);job.put("playlistUrl",PlatformClient.playlistUrl((String)job.get("provider"),(String)job.getOrDefault("externalPlaylistId","")));if(activeTransfers.contains(u+":"+job.get("id")))job.put("status","RUNNING");}return jobs;}
    @GetMapping("/transfers/{id}") public Map<String,Object> transferStatus(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){return status(user(auth),id);}
    private Map<String,Object> status(String u,String id){Map<String,Object> job=counted(store.document(u,id,"transfer"));job.put("playlistUrl",PlatformClient.playlistUrl((String)job.get("provider"),(String)job.getOrDefault("externalPlaylistId","")));if(activeTransfers.contains(u+":"+id))job.put("status","RUNNING");return job;}
    @DeleteMapping("/transfers/{id}") public synchronized Map<String,Boolean> deleteTransfer(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){String u=user(auth);store.document(u,id,"transfer");if(activeTransfers.contains(u+":"+id))throw new ResponseStatusException(HttpStatus.CONFLICT,"진행 중인 전송은 완료된 뒤 기록을 지워 주세요.");store.delete(u,id,"transfer");return Map.of("deleted",true);}
    @PostMapping("/transfers/{id}/retry") public Map<String,Object> retry(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody Confirm body){String u=user(auth);confirmed(body.confirmed());Map<String,Object> job=store.document(u,id,"transfer");provider((String)job.get("provider"));if(!"COMPLETED".equals(job.get("status")))launch(u,id);return status(u,id);}
    private void launch(String u,String id){String key=u+":"+id;if(!activeTransfers.add(key))return;try{transfers.execute(()->{try{synchronized(transferLock){runTransfer(u,id);}}finally{activeTransfers.remove(key);}});}catch(java.util.concurrent.RejectedExecutionException closed){activeTransfers.remove(key);throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"서버가 종료 중이에요. 같은 작업으로 다시 시도해 주세요.");}}
    private void runTransfer(String u,String id){
        Map<String,Object> job;
        try{job=store.document(u,id,"transfer");}catch(ResponseStatusException removed){return;}if("COMPLETED".equals(job.get("status")))return;job.put("status","RUNNING");store.update(u,id,"transfer",job);String p=(String)job.get("provider");
        try{
            PlatformClient.Tokens token=token(u,p);String playlist=(String)job.get("externalPlaylistId");if(!store.exists(u,id,"transfer"))return;
            if(playlist.isEmpty()){
                String found=remote.findCreated(p,"TapTap transfer "+id,token);if(!found.isEmpty())playlist=found;
                else if(Boolean.TRUE.equals(job.get("createStarted"))){job.put("status","UNKNOWN");job.put("notice","목록 생성 결과를 확인하지 못했어요. 새 목록을 중복 생성하지 않고 원격 상태 확인을 기다립니다.");store.update(u,id,"transfer",counted(job));return;}
                else{job.put("createStarted",true);store.update(u,id,"transfer",job);if(!store.exists(u,id,"transfer"))return;playlist=remote.createPrivate(p,(String)job.get("title"),"TapTap transfer "+id,token);}
                job.put("externalPlaylistId",playlist);job.put("playlistUrl",PlatformClient.playlistUrl(p,playlist));store.update(u,id,"transfer",job);
            }
            List<String> existing=remote.existingIds(p,playlist,token);List<Map<String,Object>> rows=items(job,"items");
            boolean prefix=existing.size()<=rows.size();for(int i=0;prefix&&i<existing.size();i++)prefix=existing.get(i).equals(rows.get(i).get("targetId"));
            if(!prefix){job.put("status","UNKNOWN");job.put("notice","원격 목록의 순서가 바뀌었어요. 항목을 중복 추가하거나 덮어쓰지 않고 확인을 기다립니다.");store.update(u,id,"transfer",counted(job));return;}
            for(int i=0;i<existing.size();i++){rows.get(i).put("status","ADDED");rows.get(i).put("message","");}
            for(int i=existing.size(),attempted=0;i<rows.size()&&attempted++<20;i++){
                Map<String,Object> item=rows.get(i);if("ADDED".equals(item.get("status"))){item.put("status","UNKNOWN");item.put("message","원격 목록에서 이전 저장 결과가 확인되지 않아요.");}if("UNKNOWN".equals(item.get("status")))break;
                item.put("status","UNKNOWN");store.update(u,id,"transfer",counted(job));
                try{if(!store.exists(u,id,"transfer"))return;remote.add(p,playlist,(String)item.get("targetId"),token);item.put("status","ADDED");item.put("message","");}
                catch(PlatformClient.Failure failure){item.put("status",failure.status>=400&&failure.status<500?"FAILED":"UNKNOWN");item.put("message",failure.getMessage());break;}
                store.update(u,id,"transfer",counted(job));
            }
            boolean complete=rows.stream().allMatch(r->"ADDED".equals(r.get("status")));job.put("status",complete?"COMPLETED":rows.stream().anyMatch(r->"UNKNOWN".equals(r.get("status")))?"UNKNOWN":"PARTIAL");job.put("notice",complete?"플랫폼 목록에 저장했어요.":"항목별 상태를 확인하세요. 응답이 유실된 항목은 원격 조회로 확인하며 중복 전송하지 않습니다. 한 번에 최대 20곡을 처리합니다.");
        }catch(PlatformClient.Failure failure){if(failure.status>=400&&failure.status<500&&((String)job.get("externalPlaylistId")).isEmpty())job.put("createStarted",false);job.put("status","PARTIAL");job.put("notice",failure.getMessage());}
        catch(RuntimeException error){job.put("status","UNKNOWN");job.put("notice","원격 상태를 확인하지 못했어요. 같은 작업으로 다시 상태를 확인해 주세요.");}
        try{store.update(u,id,"transfer",counted(job));}catch(ResponseStatusException removed){}
    }
    @PostMapping("/sync/{id}/preview") public synchronized Map<String,Object> syncPreview(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){
        String u=user(auth);Map<String,Object> data=store.document(u,id,"import");String p=(String)data.get("provider"),external=(String)data.get("externalPlaylistId");if(external.isEmpty())throw new IllegalArgumentException("타임라인은 원격 목록 동기화 대상이 아니에요.");PlatformClient.RemotePlaylist fresh=remote.playlist(p,external,token(u,p));if(fresh.truncated())throw new IllegalArgumentException("500곡을 넘는 목록은 삭제 동기화를 적용하지 않아요.");Map<String,Object> after=importDocument(id,p,fresh,Boolean.TRUE.equals(data.get("needsRefresh"))?null:(String)data.get("title"));after.put("sourceUrl",data.get("sourceUrl"));String preview=UUID.randomUUID().toString();List<Map<String,Object>> beforeItems=currentItems(u,data),afterItems=items(after,"items");
        Map<String,Object> result=new LinkedHashMap<>(Map.of("previewId",preview,"importId",id,"requiresConfirmation",true,"beforeFingerprint",PlatformStore.hash(json.writeValueAsString(beforeItems)),"after",after,"createdAt",Instant.now().toString()));result.putAll(difference(beforeItems,afterItems));store.create(u,preview,"sync",p,result);return result;
    }
    public record SyncApply(Boolean confirmed,String previewId){}
    @PostMapping("/sync/{id}/apply") public synchronized Map<String,Object> syncApply(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody SyncApply body){
        String u=user(auth);confirmed(body.confirmed());synchronized(users){synchronized(store){return tx.execute(status->{
            Map<String,Object> preview=store.document(u,body.previewId(),"sync"),current=store.document(u,id,"import");
            if(!id.equals(preview.get("importId"))||Instant.parse((String)preview.get("createdAt")).plusSeconds(900).isBefore(Instant.now())||!preview.get("beforeFingerprint").equals(PlatformStore.hash(json.writeValueAsString(currentItems(u,current)))))throw new ResponseStatusException(HttpStatus.CONFLICT,"목록이 바뀌었거나 확인이 만료됐어요. 다시 미리 보기를 열어주세요.");
            Map<String,Object> fresh=cast(preview.get("after"));provider((String)current.get("provider"));provider((String)fresh.get("provider"));
            if(Boolean.TRUE.equals(current.get("saved"))){
                String local=(String)current.get("localPlaylistId");List<Map<String,Object>> rows=new ArrayList<>();
                for(Map<String,Object> item:items(fresh,"items")){Map<String,Object> row=new LinkedHashMap<>(cast(item.get("track")));row.put("sourceIndex",item.get("sourceIndex"));rows.add(row);}
                users.refreshExternal(u,local,rows,id);fresh.put("saved",true);fresh.put("localPlaylistId",local);
            }
            store.refreshed(u,id,fresh);store.delete(u,body.previewId(),"sync");return fresh;
        });}}
    }
    private List<Map<String,Object>> currentItems(String u,Map<String,Object> document){
        if(!Boolean.TRUE.equals(document.get("saved")))return items(document,"items");
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> stored:users.externalItems(u,(String)document.get("localPlaylistId"),(String)document.get("id"))){
            Map<String,Object> snapshot=cast(stored.get("track")),track=trackMetadata(snapshot),item=new LinkedHashMap<>();
            item.put("track",track);item.put("sourceIndex",snapshot.get("sourceIndex"));if(snapshot.containsKey("timestamp"))item.put("timestamp",snapshot.get("timestamp"));result.add(item);
        }return result;
    }
    private static Map<String,Object> trackMetadata(Map<String,Object> snapshot){Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("platform","externalId","isrc","title","artist","version","album","durationMs","sourceUrl"))if(snapshot.containsKey(field)){Object value=snapshot.get(field);result.put(field,value instanceof Number n?n.longValue():value);}return result;}
    private static Map<String,Integer> difference(List<Map<String,Object>> before,List<Map<String,Object>> after){
        Map<String,java.util.ArrayDeque<Map.Entry<Integer,Map<String,Object>>>> occurrences=new LinkedHashMap<>();
        for(int i=0;i<before.size();i++){Map<String,Object> track=cast(before.get(i).get("track"));String key=track.get("platform")+"|"+track.get("externalId");occurrences.computeIfAbsent(key,k->new java.util.ArrayDeque<>()).add(Map.entry(i,before.get(i)));}
        int added=0,removed=before.size(),changed=0;
        for(int i=0;i<after.size();i++){Map<String,Object> track=cast(after.get(i).get("track"));var queue=occurrences.get(track.get("platform")+"|"+track.get("externalId"));if(queue==null||queue.isEmpty()){added++;continue;}var previous=queue.remove();removed--;if(previous.getKey()!=i||!trackMetadata(track).equals(trackMetadata(cast(previous.getValue().get("track")))))changed++;}
        return Map.of("added",added,"removed",removed,"changed",changed);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object object){return new LinkedHashMap<>(json.convertValue(object,Map.class));}
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object object){return (Map<String,Object>)object;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> object,String key){Object value=object.get(key);return value==null?new ArrayList<>():new ArrayList<>((List<Map<String,Object>>)value);}
    private static void confirmed(Boolean value){if(!Boolean.TRUE.equals(value))throw new IllegalArgumentException("변경 내용을 확인한 뒤 적용해 주세요.");}
    private static Map<String,Object> counted(Map<String,Object> job){Object excluded=job.get("excludedSourceIndices");Map<String,Integer> counts=new LinkedHashMap<>(Map.of("added",0,"failed",0,"unknown",0,"pending",0,"excluded",excluded instanceof List<?> rows?rows.size():0));for(Map<String,Object> item:items(job,"items")){String key="ADDED".equals(item.get("status"))?"added":"FAILED".equals(item.get("status"))?"failed":"UNKNOWN".equals(item.get("status"))?"unknown":"pending";counts.put(key,counts.get(key)+1);}job.put("counts",counts);return job;}
    private static String optionalText(String value,int limit){if(value==null)return "";if(value.strip().length()>limit)throw new IllegalArgumentException("입력 길이를 확인해 주세요.");return value.strip();}
    private static String title(String value){return text(value,80);}
    private static String text(String value,int limit){if(value==null||value.strip().isEmpty()||value.strip().length()>limit)throw new IllegalArgumentException("입력 길이와 내용을 확인해 주세요.");return value.strip();}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<Map<String,String>> malformed(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","입력 내용을 확인해 주세요."));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message",error.getMessage()==null?"입력을 확인해 주세요.":error.getMessage()));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> rejected(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).body(Map.of("code","PLATFORM_REQUEST_FAILED","message",error.getReason()==null?"플랫폼 요청을 확인해 주세요.":error.getReason()));}
    @ExceptionHandler(PlatformClient.Failure.class) public ResponseEntity<Map<String,String>> remoteFailure(PlatformClient.Failure error){return ResponseEntity.status(error.status==401?409:error.status==429?429:502).body(Map.of("code","PLATFORM_UNAVAILABLE","message",error.getMessage()));}
}
