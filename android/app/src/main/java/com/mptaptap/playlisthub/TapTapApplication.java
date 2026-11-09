package com.mptaptap.playlisthub;

import android.app.Application;
import android.content.Context;
import com.kakao.sdk.common.KakaoSdk;

public final class TapTapApplication extends Application {
    static boolean kakaoConfigured(){return !BuildConfig.KAKAO_NATIVE_APP_KEY.isEmpty();}
    @Override public void onCreate(){
        super.onCreate();
        initializeServer(this,BuildConfig.API_BASE);
        if(kakaoConfigured())KakaoSdk.init(this,BuildConfig.KAKAO_NATIVE_APP_KEY);
    }
    static void initializeServer(Context context,String configured){
        String base=configured.isEmpty()?defaultServer():ApiClient.validate(configured);
        if(base.isEmpty())return;
        if(!configured.isEmpty()&&!base.startsWith("https://"))throw new IllegalArgumentException("앱의 서버 연결 설정을 확인해 주세요.");
        android.content.SharedPreferences settings=context.getSharedPreferences("settings",MODE_PRIVATE);
        boolean changed=!base.equals(settings.getString("serviceDefault",""));
        android.content.SharedPreferences.Editor edit=settings.edit().putString("serviceDefault",base);
        // Keep credentials scoped to their original server; switching services never copies tokens or data.
        if((changed&&settings.contains("server"))||(!settings.contains("server")&&!settings.getBoolean("serverSelectionMade",false)))edit.putString("server",base);
        edit.apply();
    }
    static String defaultServer(){
        if(!BuildConfig.API_BASE.isEmpty())return ApiClient.validate(BuildConfig.API_BASE);
        if(BuildConfig.DEBUG&&("ranchu".equals(android.os.Build.HARDWARE)||"goldfish".equals(android.os.Build.HARDWARE)))return "http://10.0.2.2:8080";
        return "";
    }
}
