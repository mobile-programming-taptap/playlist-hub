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
        load(view,videoId,"",onReady);
    }

    @MainThread public void load(ImageView view,String videoId,String artworkUrl,Consumer<Bitmap> onReady){
        view.setImageResource(R.drawable.ic_artwork_placeholder);view.setTag(null);
        if(closed)return;
        String key=key(videoId,artworkUrl);
        if(key==null){if(!closed&&onReady!=null)onReady.accept(null);return;}
        Bitmap cached=CACHE.get(key);
        if(cached!=null){view.setImageBitmap(cached);view.setTag(key);if(!closed&&onReady!=null)onReady.accept(cached);return;}
        Object binding=new Object();view.setTag(binding);
        WeakReference<ImageView> target=new WeakReference<>(view);
        try{worker.execute(()->{
            Bitmap bitmap=null;
            try{bitmap=fetch.apply(key);if(bitmap!=null&&!closed)CACHE.put(key,bitmap);}
            catch(RuntimeException|OutOfMemoryError ignored){}
            Bitmap result=bitmap;
            if(closed)return;
            main.post(()->{
                ImageView image=target.get();
                if(closed||image==null||image.getTag()!=binding)return;
                if(result!=null){image.setImageBitmap(result);image.setTag(key);}
                else image.setTag(null);
                if(!closed&&onReady!=null)onReady.accept(result);
            });
        });}catch(RejectedExecutionException ignored){
            if(view.getTag()==binding){view.setTag(null);if(!closed&&onReady!=null)onReady.accept(null);}
        }
    }

    public Bitmap peek(String videoId){return peek(videoId,"");}
    public Bitmap peek(String videoId,String artworkUrl){String key=key(videoId,artworkUrl);return closed||key==null?null:CACHE.get(key);}
    static String key(String videoId,String artworkUrl){
        if(videoId!=null&&videoId.matches("[A-Za-z0-9_-]{11}"))return videoId;
        return artworkUrl!=null&&artworkUrl.matches("https://coverartarchive\\.org/release/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/front-500")?artworkUrl:null;
    }

    @Override public void close(){closed=true;worker.shutdownNow();}

    private static Bitmap download(String key){
        long deadline=System.nanoTime()+15_000_000_000L;
        if(key.startsWith("https://")){
            try{return downloadImage(key,deadline,true);}catch(IOException ignored){return null;}
        }
        for(String size:new String[]{"maxresdefault","hqdefault"}){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            try{Bitmap bitmap=downloadImage("https://i.ytimg.com/vi/"+key+"/"+size+".jpg",deadline,false);if(bitmap!=null)return bitmap;}
            catch(IOException ignored){}
        }
        return null;
    }

    private static Bitmap downloadImage(String address,long deadline,boolean cover) throws IOException {
        URL url=new URL(address);
        for(int redirects=0;redirects<=3;redirects++){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3500);connection.setReadTimeout(3500);
            try{
            int status=connection.getResponseCode();
            if(cover&&redirects<3&&(status==301||status==302||status==303||status==307||status==308)){
                String location=connection.getHeaderField("Location");if(location==null||location.length()>2048)return null;
                URL next=new URL(url,location);
                String host=next.getHost();
                if(!"https".equals(next.getProtocol())||next.getUserInfo()!=null||next.getRef()!=null||(next.getPort()!=-1&&next.getPort()!=443)
                        ||!(host.equals("coverartarchive.org")||host.equals("archive.org")||host.endsWith(".archive.org")))return null;
                url=next;continue;
            }
            if(status!=200||connection.getContentLength()>MAX_BYTES)return null;
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
        return null;
    }
}
