package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReleaseConfigurationCheckTest {
    @Test public void configuredServiceHealthWhenExplicitlyRequested() throws Exception {
        org.junit.Assume.assumeTrue("Private network smoke test is opt-in",
            InstrumentationRegistry.getArguments().getString("serviceHealth","false").equals("true"));
        assertFalse(BuildConfig.API_BASE.isEmpty());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("ok",new ApiClient(context,BuildConfig.API_BASE).object("GET","/health",null).getString("status"));
    }
    @Test public void configuredServiceStartsWithoutAnAddressPromptButRespectsExplicitLocalChoice() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);Map<String,?> previous=settings.getAll();
        try{
            assertTrue(settings.edit().clear().commit());TapTapApplication.initializeServer(context,"");assertEquals(TapTapApplication.defaultServer(),settings.getString("server",""));
            TapTapApplication.initializeServer(context,"https://taptap.example.invalid/");assertEquals("https://taptap.example.invalid",settings.getString("server",""));
            assertTrue(settings.edit().putString("server","http://127.0.0.1:12345").commit());TapTapApplication.initializeServer(context,"https://taptap.example.invalid");assertEquals("http://127.0.0.1:12345",settings.getString("server",""));
            assertTrue(settings.edit().remove("server").putBoolean("serverSelectionMade",true).commit());TapTapApplication.initializeServer(context,"https://taptap.example.invalid");assertFalse(settings.contains("server"));
            try{TapTapApplication.initializeServer(context,"http://127.0.0.1:12345");fail("A configured public service must use HTTPS");}catch(IllegalArgumentException expected){}
            assertFalse(settings.contains("server"));assertTrue(BuildConfig.VERSION_CODE>0);assertFalse(BuildConfig.VERSION_NAME.isEmpty());
            assertEquals("https://taptap.example.invalid",ApiClient.validate("https://taptap.example.invalid/"));
            if(BuildConfig.DEBUG)assertEquals("http://127.0.0.1:12345",ApiClient.validate("http://127.0.0.1:12345"));
            else try{ApiClient.validate("http://127.0.0.1:12345");fail("Release cleartext policy must match address validation");}catch(IllegalArgumentException expected){}
            assertTrue(ApiClient.read(context.getAssets().open("licenses/apache-2.0.txt")).contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"));
            assertTrue(ApiClient.read(context.getAssets().open("licenses/pretendard.txt")).contains("SIL OPEN FONT LICENSE Version 1.1"));
            String notices=ApiClient.read(context.getAssets().open("licenses/dependencies.txt"));assertTrue(notices.contains("org.jspecify:jspecify:1.0.0"));assertTrue(notices.contains("com.kakao.sdk:v2-user:2.25.0"));assertTrue(notices.contains("com.google.guava:listenablefuture:1.0"));
        }finally{
            SharedPreferences.Editor edit=settings.edit().clear();for(Map.Entry<String,?> entry:previous.entrySet()){
                String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,(java.util.Set<String>)value);
            }assertTrue(edit.commit());
        }
    }
}
