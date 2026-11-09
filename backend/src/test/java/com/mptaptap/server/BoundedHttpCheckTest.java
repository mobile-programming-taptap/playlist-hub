package com.mptaptap.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpCheckTest {
    @Test void exactByteLimitOversizedBodyAndPostHeaderStallAreBounded() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var worker=Executors.newCachedThreadPool();
        CountDownLatch headers=new CountDownLatch(1),release=new CountDownLatch(1);server.setExecutor(worker);
        server.createContext("/exact",exchange->{byte[] body="x".repeat(100_000).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});
        server.createContext("/large",exchange->{exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){output.write(new byte[100_001]);}catch(IOException closed){}finally{exchange.close();}});
        server.createContext("/stall",exchange->{try{exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write('x');exchange.getResponseBody().flush();headers.countDown();release.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        try(BoundedHttp client=new BoundedHttp(Duration.ofSeconds(1))) {
            assertEquals(100_000,client.send(HttpRequest.newBuilder(URI.create(base+"/exact")).timeout(Duration.ofSeconds(2)).GET().build(),100_000).body().length);
            assertThrows(IOException.class,()->client.send(HttpRequest.newBuilder(URI.create(base+"/large")).timeout(Duration.ofSeconds(2)).GET().build(),100_000));
            long started=System.nanoTime();
            assertThrows(IOException.class,()->client.send(HttpRequest.newBuilder(URI.create(base+"/stall")).timeout(Duration.ofMillis(250)).GET().build(),100_000));
            assertEquals(0,headers.getCount(),"The deadline must cover the body after response headers arrived.");
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(2))<0);assertEquals(1,release.getCount());
        } finally {release.countDown();server.stop(0);worker.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS);}
    }

    @Test void openAiCannotBufferAnOversizedResponseBeforeValidation() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){output.write(new byte[100_001]);}catch(IOException closed){}finally{exchange.close();}});server.start();
        OpenAi api=new OpenAi(new ObjectMapper(),"fixture-only","check-model","http://127.0.0.1:"+server.getAddress().getPort()+"/responses");
        try {assertThrows(IOException.class,()->api.plan("프랑스어 샹송",java.util.List.of(),null,java.util.Map.of()));}
        finally {api.close();server.stop(0);}
    }
}
