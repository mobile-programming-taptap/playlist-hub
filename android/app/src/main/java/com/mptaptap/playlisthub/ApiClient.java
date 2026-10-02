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

/** Blocking requests: called only from MainActivity's serial worker. */
final class ApiClient {
    final String base;
    private final SharedPreferences credentials;
    private String token;
    ApiClient(Context context, String base) {
        this.base = validate(base); credentials = context.getSharedPreferences("server_credentials", Context.MODE_PRIVATE);
        token = credentials.getString(this.base, "");
    }
    static String validate(String value) {
        String cleaned = value.trim().replaceAll("/+$", "");
        URI uri = URI.create(cleaned); String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() > 65535
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && localHost(host)) || !uri.getPath().isEmpty())
            throw new IllegalArgumentException("HTTPS 서버 주소를 입력해 주세요. 로컬 테스트는 http://10.0.2.2:8080을 사용할 수 있어요.");
        return cleaned;
    }
    static boolean localHost(String host) {
        String octet = "(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})";
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                || host.matches("10\\." + octet + "\\." + octet + "\\." + octet)
                || host.matches("192\\.168\\." + octet + "\\." + octet)
                || host.matches("172\\.(?:1[6-9]|2[0-9]|3[01])\\." + octet + "\\." + octet));
    }
    void connect() throws Exception {
        object("GET", "/health", null);
        if (token.isEmpty()) {
            String fresh = object("POST", "/sessions", null).getString("token");
            if (!fresh.matches("[A-Za-z0-9_-]{43}") || !credentials.edit().putString(base, fresh).commit()) throw new IllegalStateException("서버 연결 정보를 보관하지 못했어요.");
            token = fresh;
        }
    }
    JSONObject object(String method, String path, JSONObject body) throws Exception { return new JSONObject(request(method, path, body)); }
    JSONArray array(String path) throws Exception { return new JSONArray(request("GET", path, null)); }
    String request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(22000); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int code = connection.getResponseCode(); InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String content = stream == null ? "" : read(stream);
            if (code < 200 || code >= 300) {
                String message = code == 401 ? "서버 연결 정보가 만료되었어요. 설정에서 연결을 확인해 주세요." : "서버 요청에 실패했어요. 잠시 후 다시 시도해 주세요.";
                try { message = new JSONObject(content).optString("message", message); } catch (Exception ignored) { }
                throw new IllegalStateException(message);
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
