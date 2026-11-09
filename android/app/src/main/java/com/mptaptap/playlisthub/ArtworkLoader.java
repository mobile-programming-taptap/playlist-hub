package com.mptaptap.playlisthub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import androidx.annotation.MainThread;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Display-only artwork; this cache holds no Activity, view, or recommendation data. */
public final class ArtworkLoader implements AutoCloseable {
    private static final int MAX_BYTES=2*1024*1024;
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Function<String,Bitmap> fetch;
    private volatile boolean closed;

    public ArtworkLoader(){this(ArtworkLoader::download);}
    ArtworkLoader(Function<String,Bitmap> fetch){this.fetch=fetch;}

    /** Resets the placeholder immediately; a successful tag contains the requested video ID. */
    @MainThread public void load(ImageView view,String videoId){
        load(view,videoId,null);
    }

    /** Notifies on the UI thread only while this view still represents the requested artwork. */
    @MainThread public void load(ImageView view,String videoId,Consumer<Bitmap> onReady){
        view.setImageResource(R.drawable.ic_artwork_placeholder);view.setTag(null);
        if(closed)return;
        if(videoId==null||!videoId.matches("[A-Za-z0-9_-]{11}")){if(!closed&&onReady!=null)onReady.accept(null);return;}
        Bitmap cached=peek(videoId);
        if(cached!=null){view.setImageBitmap(cached);view.setTag(videoId);if(!closed&&onReady!=null)onReady.accept(cached);return;}
        Object binding=new Object();view.setTag(binding);
        WeakReference<ImageView> target=new WeakReference<>(view);
        try{worker.execute(()->{
            Bitmap bitmap=null;
            try{bitmap=fetch.apply(videoId);if(bitmap!=null&&!closed)CACHE.put(videoId,bitmap);}
            catch(RuntimeException|OutOfMemoryError ignored){}
            Bitmap result=bitmap;
            if(closed)return;
            main.post(()->{
                ImageView image=target.get();
                if(closed||image==null||image.getTag()!=binding)return;
                if(result!=null){image.setImageBitmap(result);image.setTag(videoId);}
                else image.setTag(null);
                if(!closed&&onReady!=null)onReady.accept(result);
            });
        });}catch(RejectedExecutionException ignored){
            if(view.getTag()==binding){view.setTag(null);if(!closed&&onReady!=null)onReady.accept(null);}
        }
    }

    public Bitmap peek(String videoId){return closed||videoId==null||!videoId.matches("[A-Za-z0-9_-]{11}")?null:CACHE.get(videoId);}

    @Override public void close(){closed=true;worker.shutdownNow();}

    private static Bitmap download(String videoId){
        long deadline=System.nanoTime()+15_000_000_000L;
        for(String size:new String[]{"maxresdefault","hqdefault"}){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            try{Bitmap bitmap=downloadImage(videoId,size,deadline);if(bitmap!=null)return bitmap;}
            catch(IOException ignored){}
        }
        return null;
    }

    private static Bitmap downloadImage(String videoId,String size,long deadline) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL("https://i.ytimg.com/vi/"+videoId+"/"+size+".jpg").openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3500);connection.setReadTimeout(3500);
        try{
            if(connection.getResponseCode()!=200||connection.getContentLength()>MAX_BYTES)return null;
            byte[] content;
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline||output.size()+count>MAX_BYTES)return null;
                    output.write(buffer,0,count);
                }
                content=output.toByteArray();
            }
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(content,0,content.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>1_000_000)return null;
            return BitmapFactory.decodeByteArray(content,0,content.length);
        }finally{connection.disconnect();}
    }
}
