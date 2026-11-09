package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Blocking requests: called only from background workers. */
final class ApiClient {
    static final String CONSENT_VERSION="2";
    static final class ConsentRequired extends IllegalStateException {ConsentRequired(){super("서비스 이용 안내를 확인하고 동의해 주세요.");}}
    static final class ApiError extends IllegalStateException {
        final int statusCode;
        final String code;
        ApiError(int statusCode, String message) { this(statusCode,"",message); }
        ApiError(int statusCode,String code,String message) { super(message);this.statusCode=statusCode;this.code=code; }
    }
    final String base;
    private final SharedPreferences credentials;
    private final SharedPreferences settings;
    private volatile String token;
    ApiClient(Context context, String base) {
        this.base = validate(base); credentials = context.getSharedPreferences("server_credentials", Context.MODE_PRIVATE);settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        token = credentials.getString(this.base, "");
    }
    static String validate(String value) {
        String cleaned = value.trim().replaceAll("/+$", "");
        URI uri = URI.create(cleaned); String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() > 65535
                || !("https".equals(uri.getScheme()) || BuildConfig.DEBUG && "http".equals(uri.getScheme()) && localHost(host)) || !uri.getPath().isEmpty())
            throw new IllegalArgumentException(BuildConfig.DEBUG?"HTTPS 서버 주소를 입력해 주세요. 로컬 테스트는 http://10.0.2.2:8080을 사용할 수 있어요.":"HTTPS 서버 주소를 입력해 주세요.");
        return cleaned;
    }
    static boolean localHost(String host) {
        String octet = "(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})";
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                || host.matches("10\\." + octet + "\\." + octet + "\\." + octet)
                || host.matches("192\\.168\\." + octet + "\\." + octet)
                || host.matches("172\\.(?:1[6-9]|2[0-9]|3[01])\\." + octet + "\\." + octet));
    }
    static boolean consentAccepted(Context context,String base){return CONSENT_VERSION.equals(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("serviceConsent:"+validate(base),""));}
    static void acceptConsent(Context context,String base){if(!context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("serviceConsent:"+validate(base),CONSENT_VERSION).commit())throw new IllegalStateException("이용 동의를 저장하지 못했어요. 다시 시도해 주세요.");}
    static void declineConsent(Context context,String base){SharedPreferences selected=context.getSharedPreferences("settings",Context.MODE_PRIVATE);if(validate(base).equals(selected.getString("server","")))selected.edit().remove("server").remove("serviceConsent:"+validate(base)).putBoolean("serverSelectionMade",true).apply();}
    void connect() throws Exception {
        token=credentials.getString(base,"");
        object("GET", "/health", null);
        if (token.isEmpty()) {
            String fresh = object("POST", "/sessions", null).getString("token");
            synchronized(credentials){
                if(!identity().isEmpty())throw new IllegalStateException("계정이 변경되었어요. 연결을 다시 확인해 주세요.");
                if (!fresh.matches("[A-Za-z0-9_-]{43}") || !credentials.edit().putString(base, fresh).commit()) throw new IllegalStateException("서버 연결 정보를 보관하지 못했어요.");
                token = fresh;
            }
        }
    }
    String identity() { return credentials.getString(base,""); }
    boolean loginExpired(){return credentials.getBoolean("expired:"+base,false);}
    static String ownerFingerprint(String base,String identity) {
        return java.util.UUID.nameUUIDFromBytes((base+"|"+identity).getBytes(StandardCharsets.UTF_8)).toString();
    }
    boolean ownsCurrentSession() { return token.equals(identity()); }
    void replaceSession(String fresh) {
        synchronized(credentials){
            if(!ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
            if(fresh==null||!fresh.matches("[A-Za-z0-9_-]{43}")||!credentials.edit().putString(base,fresh).remove("expired:"+base).commit())
                throw new IllegalStateException("로그인 정보를 보관하지 못했어요.");
            token=fresh;
        }
    }
    void clearSession() {
        synchronized(credentials){
            if(!ownsCurrentSession())throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
            if(!credentials.edit().remove(base).remove("expired:"+base).commit())throw new IllegalStateException("로그인 정보를 초기화하지 못했어요.");
            token="";
        }
    }
    JSONObject object(String method, String path, JSONObject body) throws Exception { return new JSONObject(request(method, path, body)); }
    JSONArray array(String path) throws Exception { return new JSONArray(request("GET", path, null)); }
    String request(String method, String path, JSONObject body) throws Exception {
        String requestToken=credentials.getString(base,"");
        if(!requestToken.equals(token))throw new IllegalStateException("계정이 변경되었어요. 새 계정에서 다시 요청해 주세요.");
        if(!(method.equals("GET")&&path.equals("/health"))&&!CONSENT_VERSION.equals(settings.getString("serviceConsent:"+base,"")))throw new ConsentRequired();
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(method.equals("POST")&&(path.equals("/recommendations")||path.equals("/conversions/youtube"))?35000:22000); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!requestToken.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + requestToken);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int code = connection.getResponseCode(); InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String content = stream == null ? "" : read(stream);
            if (code < 200 || code >= 300) {
                String errorCode="",message = code == 401 ? "서버 연결 정보가 만료되었어요. 설정에서 연결을 확인해 주세요." : "서버 요청에 실패했어요. 잠시 후 다시 시도해 주세요.";
                try {JSONObject error=new JSONObject(content);errorCode=error.optString("code");message=error.optString("message",message);}catch(Exception ignored){}
                boolean expired=code==401&&errorCode.equals("SESSION_EXPIRED");
                if (code == 401 && (expired||!path.equals("/accounts/login") && !path.equals("/accounts/kakao") && !(path.equals("/accounts/me")&&method.equals("DELETE")))) {
                    synchronized(credentials){if(requestToken.equals(identity())) {
                        token = "";
                        SharedPreferences.Editor change=credentials.edit().remove(base);if(expired)change.putBoolean("expired:"+base,true);
                        if (!change.commit()) throw new IllegalStateException("연결 정보를 초기화하지 못했어요. 다시 시도해 주세요.");
                        if(expired&&base.equals(settings.getString("authorizationBase",""))&&ownerFingerprint(base,requestToken).equals(settings.getString("authorizationOwner","")))
                            settings.edit().remove("authorizationBase").remove("authorizationOwner").remove("authorizationProvider").remove("authorizationLibrary").remove("authorizationStarted").apply();
                    }}
                }
                throw new ApiError(code,errorCode,message);
            }
            return content;
        } finally { connection.disconnect(); }
    }
    static String read(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 2_000_000) throw new IllegalStateException("서버 응답이 너무 커요.");
                output.write(bytes, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
