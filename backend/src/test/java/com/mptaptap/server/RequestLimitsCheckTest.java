package com.mptaptap.server;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestLimitsCheckTest {
    @Test void realHttpLimitsDeclaredAndStreamingBodiesBeforeCreatingOwners() throws Exception {
        try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(TapTapApplication.class).run(
                "--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:requestLimits"+java.util.UUID.randomUUID()+";MODE=PostgreSQL",
                "--spring.datasource.username=sa","--spring.datasource.password=","--taptap.openai.key=",
                "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            String base="http://127.0.0.1:"+((org.springframework.boot.web.server.context.WebServerApplicationContext)context).getWebServer().getPort();
            var client=java.net.http.HttpClient.newHttpClient();var db=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            byte[] oversized=new byte[RequestLimits.MAX_BODY_BYTES+1];
            for(boolean chunked:new boolean[]{false,true}) {
                var publisher=chunked?java.net.http.HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(oversized)):java.net.http.HttpRequest.BodyPublishers.ofByteArray(oversized);
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/sessions")).timeout(Duration.ofSeconds(5)).POST(publisher).build();
                var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(413,response.statusCode());assertTrue(response.body().contains("REQUEST_TOO_LARGE"));
            }
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
            // Oversized requests also consumed the rate budget; changing an untrusted header cannot reset it.
            for(int i=0;i<28;i++) {
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/sessions")).header("X-Forwarded-For","198.51.100."+i).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(201,client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            var blocked=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/sessions;ignored=new")).header("X-Forwarded-For","203.0.113.1").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(429,blocked.statusCode());assertTrue(blocked.headers().firstValue("Retry-After").isPresent());
            assertEquals(28,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        }
    }

    @Test void oversizedDeclaredAndChunkedBodiesNeverReachApplication() throws Exception {
        RequestLimits limits=new RequestLimits();
        AtomicInteger calls=new AtomicInteger();
        for(long declared:new long[]{RequestLimits.MAX_BODY_BYTES+1L,-1}){
            Response response=new Response();
            limits.doFilterInternal(request("POST","/recommendations","192.0.2.1",new byte[RequestLimits.MAX_BODY_BYTES+1],declared),response.proxy(),(req,res)->calls.incrementAndGet());
            assertEquals(413,response.status);assertTrue(response.text.toString().contains("REQUEST_TOO_LARGE"));
        }
        assertEquals(0,calls.get(),"JSON parsing, AI spending and database writes must not start for oversized input.");
        byte[] unicode="{\"title\":\"공부할 때 夜 🎵\"}".getBytes(StandardCharsets.UTF_8);
        Response good=new Response();
        limits.doFilterInternal(request("POST","/playlists","192.0.2.1",unicode,-1),good.proxy(),(req,res)->{assertArrayEquals(unicode,req.getInputStream().readAllBytes());calls.incrementAndGet();});
        assertEquals(1,calls.get());assertEquals(200,good.status);
    }

    @Test void newSessionsAndSpoofedHeadersCannotResetAddressBudget() throws Exception {
        MutableClock clock=new MutableClock();RequestLimits limits=new RequestLimits(clock);AtomicInteger created=new AtomicInteger();
        for(int i=0;i<30;i++)send(limits,"POST","/sessions","192.0.2.2",created);
        Response blocked=send(limits,"POST","/sessions;ignored=another","192.0.2.2",created);
        assertEquals(429,blocked.status);assertEquals(30,created.get());assertEquals("3600",blocked.headers.get("Retry-After"));
        assertEquals(200,send(limits,"POST","/sessions","192.0.2.3",created).status);
        for(int i=0;i<60;i++)send(limits,"POST","/recommendations","192.0.2.2",created);
        assertEquals(429,send(limits,"POST","/recommendations","192.0.2.2",created).status);
        clock.advance(Duration.ofHours(1));
        assertEquals(200,send(limits,"POST","/sessions","192.0.2.2",created).status);
        assertEquals(200,send(limits,"POST","/recommendations","192.0.2.2",created).status);
    }

    @Test void costlyOperationsShareBudgetButLocalStatusAndOtherAddressesRecover() throws Exception {
        MutableClock clock=new MutableClock();RequestLimits limits=new RequestLimits(clock);AtomicInteger calls=new AtomicInteger();
        for(int i=0;i<20;i++)assertEquals(200,send(limits,"POST",i%2==0?"/matches":"/conversions/youtube","192.0.2.4",calls).status);
        assertEquals(429,send(limits,"GET","/connections/youtube/playlists","192.0.2.4",calls).status);
        assertEquals(429,send(limits,"GET","/playback/tracks/any-song","192.0.2.4",calls).status);
        assertEquals(200,send(limits,"GET","/transfers/test-job","192.0.2.4",calls).status);
        assertEquals(200,send(limits,"POST","/matches","192.0.2.5",calls).status);
        clock.advance(Duration.ofMinutes(1));assertEquals(200,send(limits,"POST","/matches","192.0.2.4",calls).status);
    }

    private static Response send(RequestLimits limits,String method,String path,String address,AtomicInteger calls) throws Exception {
        Response response=new Response();limits.doFilterInternal(request(method,path,address,new byte[0],0),response.proxy(),(req,res)->calls.incrementAndGet());return response;
    }
    private static HttpServletRequest request(String method,String path,String address,byte[] body,long declared){
        ByteArrayInputStream input=new ByteArrayInputStream(body);
        ServletInputStream stream=new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int o,int l){return input.read(b,o,l);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};
        return (HttpServletRequest)Proxy.newProxyInstance(RequestLimitsCheckTest.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,m,args)->switch(m.getName()){
            case "getMethod"->method;case "getServletPath","getRequestURI"->path;case "getRemoteAddr"->address;case "getContentLengthLong"->declared;case "getInputStream"->stream;
            // Neither caller-supplied forwarding headers nor arbitrary bearer strings are trusted as the address.
            case "getHeader"->"203.0.113."+System.nanoTime();case "toString"->"request fixture";default->throw new UnsupportedOperationException(m.getName());
        });
    }
    private static final class Response {
        int status=200;StringWriter text=new StringWriter();Map<String,String> headers=new HashMap<>();
        HttpServletResponse proxy(){return (HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletResponse.class},(proxy,m,args)->{
            switch(m.getName()){case "setStatus"->status=(int)args[0];case "setHeader"->headers.put((String)args[0],(String)args[1]);case "getWriter"->{return new PrintWriter(text);}case "setCharacterEncoding","setContentType"->{}default->throw new UnsupportedOperationException(m.getName());}return null;
        });}
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-19T00:00:00Z");void advance(Duration value){now=now.plus(value);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}
    }
}
