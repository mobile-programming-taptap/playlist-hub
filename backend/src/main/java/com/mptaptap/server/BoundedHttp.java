package com.mptaptap.server;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The deadline includes reading the body, even when headers arrived on time. */
final class BoundedHttp implements AutoCloseable {
    record Reply(int status,byte[] body) {}
    private final HttpClient client;
    private final java.util.concurrent.ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"recommendation-body-deadline");thread.setDaemon(true);return thread;});
    BoundedHttp(Duration connectTimeout){client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();}
    Reply send(HttpRequest request,int limit) throws IOException,InterruptedException {
        long deadline=System.nanoTime()+request.timeout().orElse(Duration.ofSeconds(15)).toNanos();
        HttpResponse<java.io.InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        AtomicBoolean expired=new AtomicBoolean();
        var timer=deadlines.schedule(()->{expired.set(true);try{response.body().close();}catch(IOException ignored){}},Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        try(var input=response.body()) {
            byte[] bytes=input.readNBytes(limit+1);
            if(expired.get())throw new IOException("RESPONSE_TIMEOUT");
            if(bytes.length>limit)throw new IOException("RESPONSE_TOO_LARGE");
            return new Reply(response.statusCode(),bytes);
        }finally{timer.cancel(false);}
    }
    @Override public void close(){client.shutdownNow();deadlines.shutdownNow();}
}
