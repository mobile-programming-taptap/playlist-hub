package com.mptaptap.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reject oversized and abusive requests before JSON parsing, database writes or provider calls. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLimits extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES=512*1024, MAX_BUCKETS=10_000;
    private record Key(String address,String group) {}
    private record Bucket(int count,Instant expires) {}
    private final Map<Key,Bucket> buckets=new HashMap<>();
    private final Clock clock;
    private Instant cleanupAt=Instant.MIN;

    public RequestLimits(){this(Clock.systemUTC());}
    RequestLimits(Clock clock){this.clock=clock;}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String address=request.getRemoteAddr()==null?"unknown":request.getRemoteAddr();
        // Servlet paths are decoded; matrix parameters must not create another throttling identity.
        String path=request.getServletPath().replaceAll(";[^/]*","").replaceAll("/{2,}","/");
        String method=request.getMethod();
        long retry=take(address,"requests",240,Duration.ofMinutes(1));
        if(retry==0&&method.equals("POST")&&path.equals("/sessions"))retry=take(address,"sessions",30,Duration.ofHours(1));
        if(retry==0&&method.equals("POST")&&path.equals("/recommendations"))retry=take(address,"recommendations",60,Duration.ofHours(1));
        if(retry==0&&method.equals("POST")&&(path.equals("/accounts/login")||path.equals("/accounts/register")||path.equals("/accounts/kakao")))retry=take(address,"authentication",10,Duration.ofMinutes(1));
        if(retry==0&&providerWork(method,path))retry=take(address,"platform",20,Duration.ofMinutes(1));
        if(retry>0){response.setHeader("Retry-After",Long.toString(retry));reject(response,429,"REQUEST_LIMIT","요청이 많아요. 잠시 후 다시 시도해 주세요.");return;}
        if(request.getContentLengthLong()>MAX_BODY_BYTES){reject(response,413,"REQUEST_TOO_LARGE","요청 내용이 너무 커요. 입력을 줄여 다시 시도해 주세요.");return;}
        byte[] body=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
        if(body.length>MAX_BODY_BYTES){reject(response,413,"REQUEST_TOO_LARGE","요청 내용이 너무 커요. 입력을 줄여 다시 시도해 주세요.");return;}
        chain.doFilter(body.length==0?request:new BufferedRequest(request,body),response);
    }

    private static boolean providerWork(String method,String path){
        return method.equals("GET")&&(path.matches("/connections/(spotify|youtube|youtube_music)/playlists")||path.matches("/playback/(youtube|tracks)/[^/]+"))
            ||method.equals("POST")&&(path.equals("/matches")||path.equals("/conversions/youtube")||path.equals("/imports/playlists")||path.matches("/sync/[^/]+/preview"));
    }

    // shortcut: process-local limits reset on restart; use shared counters before running multiple replicas.
    private synchronized long take(String address,String group,int limit,Duration window){
        Instant now=clock.instant();
        if(!now.isBefore(cleanupAt)){buckets.entrySet().removeIf(entry->!entry.getValue().expires().isAfter(now));cleanupAt=now.plusSeconds(30);}
        Key key=new Key(address,group);Bucket previous=buckets.get(key);
        if(previous!=null&&!previous.expires().isAfter(now))previous=null;
        if(previous==null){if(buckets.size()>=MAX_BUCKETS)return 30;buckets.put(key,new Bucket(1,now.plus(window)));return 0;}
        if(previous.count()>=limit)return Math.max(1,(Duration.between(now,previous.expires()).toMillis()+999)/1000);
        buckets.put(key,new Bucket(previous.count()+1,previous.expires()));return 0;
    }

    private static void reject(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public int getContentLength(){return body.length;}
        @Override public long getContentLengthLong(){return body.length;}
        @Override public ServletInputStream getInputStream(){
            ByteArrayInputStream input=new ByteArrayInputStream(body);
            return new ServletInputStream(){
                @Override public int read(){return input.read();}
                @Override public int read(byte[] target,int offset,int length){return input.read(target,offset,length);}
                @Override public boolean isFinished(){return input.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new IllegalStateException("Request JSON is read synchronously.");}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
