package com.mptaptap.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Component
public class PlatformStore {
    public record Pending(String user,String provider,String verifier,Instant expiresAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();

    @Autowired PlatformStore(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,Environment env,Store initialized) {
        this(db,manager,json,env.getProperty("PLATFORM_TOKEN_SECRET",""));
    }
    PlatformStore(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,String secret) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.json=json;this.key=key(secret);
        new ResourceDatabasePopulator(new ClassPathResource("platform-schema.sql")).execute(db.getDataSource());
    }
    boolean configured(){return key!=null;}
    private static byte[] key(String secret) {
        if(secret.isBlank())return null;
        try { byte[] value;try{value=Base64.getDecoder().decode(secret);}catch(IllegalArgumentException error){value=Base64.getUrlDecoder().decode(secret);}return value.length==32?value:null; }
        catch(IllegalArgumentException error){return null;}
    }
    private String crypt(String value,String user,String provider,boolean encrypt) {
        if(key==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"PLATFORM_TOKEN_SECRET에 32바이트 Base64 암호화 키를 설정해 주세요.");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");byte[] iv=new byte[12],input;
            if(encrypt){random.nextBytes(iv);input=value.getBytes(StandardCharsets.UTF_8);}
            else{byte[] packed=Base64.getDecoder().decode(value);if(packed.length<28)throw new IllegalArgumentException();System.arraycopy(packed,0,iv,0,12);input=java.util.Arrays.copyOfRange(packed,12,packed.length);}
            cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD((user+"|"+provider).getBytes(StandardCharsets.UTF_8));byte[] result=cipher.doFinal(input);
            if(!encrypt)return new String(result,StandardCharsets.UTF_8);
            byte[] packed=new byte[iv.length+result.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(result,0,packed,iv.length,result.length);return Base64.getEncoder().encodeToString(packed);
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"플랫폼 연결 정보를 읽을 수 없어요. 서버 암호화 설정을 확인하고 다시 연결해 주세요.");}
    }
    private String randomToken(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    synchronized Map<String,String> start(String user,String provider,String authorization) {
        purgeExpired();String state=randomToken(),verifier=randomToken();Instant expires=Instant.now().plusSeconds(600);
        db.update("DELETE FROM platform_oauth_states WHERE user_id=? AND provider=?",user,provider);
        db.update("INSERT INTO platform_oauth_states VALUES (?,?,?,?,?,?)",hash(state),user,provider,crypt(verifier,user,provider,true),expires.toString(),hash(authorization.substring(7)));
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(hash(verifier)));
        return Map.of("state",state,"challenge",challenge,"expiresAt",expires.toString());
    }
    synchronized Pending pending(String state,String provider,boolean consume) {
        if(state==null||!state.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("연결 요청이 만료됐어요. 앱에서 다시 연결해 주세요.");
        List<Pending> rows=db.query("SELECT s.user_id,s.provider,s.verifier,s.expires_at,(SELECT a.created_at FROM account_sessions a WHERE a.user_id=s.user_id AND a.token_hash=s.session_hash) FROM platform_oauth_states s WHERE s.state_hash=? AND s.provider=? AND (EXISTS (SELECT 1 FROM account_sessions a WHERE a.user_id=s.user_id AND a.token_hash=s.session_hash) OR EXISTS (SELECT 1 FROM users u WHERE u.id=s.user_id AND u.token_hash=s.session_hash AND NOT EXISTS (SELECT 1 FROM accounts ac WHERE ac.user_id=u.id)))",(rs,n)->{
            String sessionCreated=rs.getString(5);
            if(sessionCreated!=null&&!Instant.parse(sessionCreated).plus(Store.ACCOUNT_SESSION_LIFETIME).isAfter(Instant.now()))throw new IllegalArgumentException("연결 요청이 만료됐어요. 앱에서 다시 연결해 주세요.");
            return new Pending(rs.getString(1),rs.getString(2),crypt(rs.getString(3),rs.getString(1),rs.getString(2),false),Instant.parse(rs.getString(4)));
        },hash(state),provider);
        if(rows.isEmpty()||!rows.get(0).expiresAt().isAfter(Instant.now()))throw new IllegalArgumentException("연결 요청이 만료됐어요. 앱에서 다시 연결해 주세요.");
        if(consume)db.update("DELETE FROM platform_oauth_states WHERE state_hash=?",hash(state));
        return rows.get(0);
    }
    synchronized void connection(String user,String provider,PlatformClient.Tokens tokens) {
        String encrypted=crypt(json.writeValueAsString(tokens),user,provider,true);
        tx.executeWithoutResult(status->{db.update("DELETE FROM platform_connections WHERE user_id=? AND provider=?",user,provider);db.update("INSERT INTO platform_connections VALUES (?,?,?)",user,provider,encrypted);});
    }
    synchronized PlatformClient.Tokens tokens(String user,String provider) {
        List<String> rows=db.query("SELECT token_json FROM platform_connections WHERE user_id=? AND provider=?",(rs,n)->rs.getString(1),user,provider);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"먼저 해당 플랫폼 계정을 연결해 주세요.");
        return json.readValue(crypt(rows.get(0),user,provider,false),PlatformClient.Tokens.class);
    }
    synchronized List<Map<String,Object>> connections(String user) {
        return db.query("SELECT provider FROM platform_connections WHERE user_id=? ORDER BY provider",(rs,n)->{
            String provider=rs.getString(1);PlatformClient.Tokens t=tokens(user,provider);
            return Map.<String,Object>of("provider",provider,"connected",true,"accountId",t.accountId(),"expiresAt",t.expiresAt().toString(),"scopes",t.scopes());
        },user);
    }
    @SuppressWarnings("unchecked") synchronized void unlink(String user,String provider) {
        tx.executeWithoutResult(status->{
            db.update("DELETE FROM platform_connections WHERE user_id=? AND provider=?",user,provider);
            db.update("DELETE FROM platform_oauth_states WHERE user_id=? AND provider=?",user,provider);
            db.update("DELETE FROM platform_documents WHERE user_id=? AND provider=?",user,provider);
            for(var row:db.query("SELECT id,document_json FROM platform_documents WHERE user_id=? AND kind='import'",(rs,n)->Map.entry(rs.getString(1),new LinkedHashMap<>((Map<String,Object>)json.readValue(rs.getString(2),Map.class))),user)){
                Map<String,Object> document=row.getValue();
                if(!provider.equals(document.get("targetProvider")))continue;
                for(String field:List.of("reviews","targetProvider","nextOffset"))document.remove(field);
                db.update("UPDATE platform_documents SET document_json=? WHERE user_id=? AND id=? AND kind='import'",json.writeValueAsString(document),user,row.getKey());
            }
        });
    }
    synchronized void create(String user,String id,String kind,String provider,Map<String,Object> body) {
        uuid(id);purgeExpired();
        if(db.queryForObject("SELECT COUNT(*) FROM platform_documents WHERE user_id=?",Integer.class,user)>=300)throw new ResponseStatusException(HttpStatus.CONFLICT,"외부 작업이 너무 많아요. 기존 작업을 정리해 주세요.");
        if(db.queryForObject("SELECT COUNT(*) FROM platform_documents WHERE id=?",Integer.class,id)>0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"외부 작업을 찾을 수 없어요.");
        db.update("INSERT INTO platform_documents VALUES (?,?,?,?,?,?,?)",id,user,kind,provider,json.writeValueAsString(body),Instant.now().toString(),Instant.now().plus(Duration.ofDays(30)).toString());
    }
    synchronized void update(String user,String id,String kind,Map<String,Object> body) {
        document(user,id,kind);db.update("UPDATE platform_documents SET document_json=?,updated_at=? WHERE user_id=? AND id=? AND kind=?",json.writeValueAsString(body),Instant.now().toString(),user,id,kind);
    }
    synchronized void refreshed(String user,String id,Map<String,Object> body) { update(user,id,"import",body);db.update("UPDATE platform_documents SET metadata_expires_at=? WHERE user_id=? AND id=?",Instant.now().plus(Duration.ofDays(30)).toString(),user,id); }
    synchronized Instant metadataCheckedAt(String user,String id){
        List<Instant> rows=db.query("SELECT metadata_expires_at FROM platform_documents WHERE user_id=? AND id=? AND kind='import'",(rs,n)->Instant.parse(rs.getString(1)).minus(Duration.ofDays(30)),user,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"외부 작업을 찾을 수 없어요.");return rows.get(0);
    }
    @SuppressWarnings("unchecked") synchronized Map<String,Object> document(String user,String id,String kind) {
        purgeExpired();List<Map<String,Object>> rows=db.query("SELECT document_json FROM platform_documents WHERE user_id=? AND id=? AND kind=?",(rs,n)->new LinkedHashMap<>((Map<String,Object>)json.readValue(rs.getString(1),Map.class)),user,id,kind);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"외부 작업을 찾을 수 없거나 데이터 갱신이 필요해요.");return rows.get(0);
    }
    synchronized boolean exists(String user,String id,String kind) { return db.queryForObject("SELECT COUNT(*) FROM platform_documents WHERE user_id=? AND id=? AND kind=?",Integer.class,user,id,kind)>0; }
    @SuppressWarnings("unchecked") synchronized List<Map<String,Object>> documents(String user,String kind) {
        purgeExpired();return db.query("SELECT document_json FROM platform_documents WHERE user_id=? AND kind=? ORDER BY updated_at DESC,id",(rs,n)->new LinkedHashMap<>((Map<String,Object>)json.readValue(rs.getString(1),Map.class)),user,kind);
    }
    @SuppressWarnings("unchecked") synchronized List<Map<String,Object>> summaries(String user,String kind){
        purgeExpired();return db.query("SELECT document_json FROM platform_documents WHERE user_id=? AND kind=? ORDER BY updated_at DESC,id",(rs,n)->{
            Map<String,Object> document=json.readValue(rs.getString(1),Map.class),summary=new LinkedHashMap<>();
            for(String field:List.of("id","title","provider","saved","targetProvider","externalPlaylistId","localPlaylistId","needsRefresh","status","counts","playlistUrl"))if(document.containsKey(field))summary.put(field,document.get(field));
            Object items=document.get("items");summary.put("size",items instanceof List<?> rows?rows.size():0);return summary;
        },user,kind);
    }
    synchronized void deleteImport(String user,String id){document(user,id,"import");tx.executeWithoutResult(status->{
        for(var row:db.query("SELECT id,document_json FROM platform_documents WHERE user_id=? AND kind='sync'",(rs,n)->Map.entry(rs.getString(1),json.readTree(rs.getString(2)).path("importId").asString("")),user))if(id.equals(row.getValue()))db.update("DELETE FROM platform_documents WHERE user_id=? AND id=? AND kind='sync'",user,row.getKey());
        db.update("DELETE FROM platform_documents WHERE user_id=? AND id=? AND kind='import'",user,id);
    });}
    synchronized void delete(String user,String id,String kind) { document(user,id,kind);db.update("DELETE FROM platform_documents WHERE user_id=? AND id=? AND kind=?",user,id,kind); }
    @Scheduled(fixedDelay=3_600_000) public synchronized void purgeExpired() {
        Instant now=Instant.now();
        for(var row:db.query("SELECT state_hash,expires_at FROM platform_oauth_states",(rs,n)->Map.entry(rs.getString(1),Instant.parse(rs.getString(2)))))if(!row.getValue().isAfter(now))db.update("DELETE FROM platform_oauth_states WHERE state_hash=?",row.getKey());
        for(var row:db.query("SELECT id,metadata_expires_at FROM platform_documents",(rs,n)->Map.entry(rs.getString(1),Instant.parse(rs.getString(2)))))if(!row.getValue().isAfter(now)){
            List<Map<String,Object>> expired=db.query("SELECT kind,document_json FROM platform_documents WHERE id=?",(rs,n)->Map.<String,Object>of("kind",rs.getString(1),"body",json.readValue(rs.getString(2),Map.class)),row.getKey());
            if(expired.isEmpty())continue;
            if(!"import".equals(expired.get(0).get("kind"))){db.update("DELETE FROM platform_documents WHERE id=?",row.getKey());continue;}
            @SuppressWarnings("unchecked") Map<String,Object> original=(Map<String,Object>)expired.get(0).get("body");if(Boolean.TRUE.equals(original.get("needsRefresh")))continue;
            Map<String,Object> reference=new LinkedHashMap<>();for(String field:List.of("id","provider","externalPlaylistId","localPlaylistId","saved","sourceUrl","videoId","metadataMode"))if(original.containsKey(field))reference.put(field,original.get(field));
            reference.put("needsRefresh",true);reference.put("title","갱신이 필요한 목록");reference.put("items",List.of());reference.put("notice","외부 곡 정보가 만료됐어요. 원본 목록을 다시 조회하거나 타임라인을 새로 확인해 주세요.");
            db.update("UPDATE platform_documents SET document_json=? WHERE id=?",json.writeValueAsString(reference),row.getKey());
        }
    }
    static String uuid(String id) { if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("작업 ID는 소문자 UUID 형식이어야 해요.");return id; }
}
