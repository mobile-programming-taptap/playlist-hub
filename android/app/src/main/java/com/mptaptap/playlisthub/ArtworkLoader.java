package com.mptaptap.playlisthub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import androidx.annotation.MainThread;
import com.mptaptap.core.ImportMatch;
import org.json.JSONObject;
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
        loadKey(view,key(videoId,artworkUrl),onReady);
    }

    private void loadKey(ImageView view,String key,Consumer<Bitmap> onReady){
        view.setImageResource(R.drawable.ic_artwork_placeholder);view.setTag(null);
        if(closed)return;
        if(key==null){if(!closed&&onReady!=null)onReady.accept(null);return;}
        Bitmap cached=CACHE.get(key);
        if(cached!=null){view.setImageBitmap(cached);view.setTag(key);if(!closed&&onReady!=null)onReady.accept(cached);return;}
        Object binding=new Object();view.setTag(binding);
        WeakReference<ImageView> target=new WeakReference<>(view);
        try{worker.execute(()->{
            Bitmap bitmap=null;
            try{bitmap=CACHE.get(key);if(bitmap==null){bitmap=fetch.apply(key);if(bitmap!=null&&!closed)CACHE.put(key,bitmap);}}
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
        if(ImportMatch.spotifyArtwork(artworkUrl))return artworkUrl;
        return artworkUrl!=null&&artworkUrl.matches("https://coverartarchive\\.org/release/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/front-500")?artworkUrl:null;
    }

    @MainThread void loadTrack(ImageView view,JSONObject track){
        loadKey(view,trackKey(track),null);
    }

    static String trackKey(JSONObject track){
        if(track==null)return null;
        String image=key(videoId(track),track.optString("artworkUrl"));
        if(image!=null)return image;
        String provider=track.optString("platform",track.optString("sourceProvider"));
        String id=track.optString("externalId",track.optString("sourceId"));
        return provider.equals("spotify")&&id.matches("[A-Za-z0-9]{22}")?"spotify:"+id:null;
    }

    static String videoId(JSONObject track){
        if(track==null)return "";
        String id=track.optString("youtubeVideoId");
        if(validVideoId(id))return id;
        String provider=track.optString("platform",track.optString("sourceProvider"));
        if(provider.equals("youtube")||provider.equals("youtube_music")){
            id=track.optString("externalId",track.optString("sourceId"));
            if(validVideoId(id))return id;
        }
        return youtubeIdFromUrl(track.optString("sourceUrl"));
    }

    static String youtubeIdFromUrl(String address){
        Uri source=Uri.parse(address);
        if(!"https".equals(source.getScheme())||!source.isHierarchical()||source.getUserInfo()!=null
                ||source.getPort()!=-1&&source.getPort()!=443)return "";
        String host=source.getHost(),id;
        if("youtu.be".equals(host))id=source.getLastPathSegment();
        else if(("www.youtube.com".equals(host)||"youtube.com".equals(host)||"music.youtube.com".equals(host))&&"/watch".equals(source.getPath()))
            id=source.getQueryParameter("v");
        else return "";
        return validVideoId(id)?id:"";
    }

    private static boolean validVideoId(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{11}");}

    @Override public void close(){closed=true;worker.shutdownNow();}

    private static Bitmap download(String key){
        long deadline=System.nanoTime()+15_000_000_000L;
        if(key.startsWith("spotify:")){
            try{
                String address=spotifyThumbnail(key.substring("spotify:".length()),deadline);
                return address==null?null:downloadImage(address,deadline,false);
            }catch(IOException|org.json.JSONException ignored){return null;}
        }
        if(key.startsWith("https://")){
            try{return downloadImage(key,deadline,!ImportMatch.spotifyArtwork(key));}catch(IOException ignored){return null;}
        }
        for(String size:new String[]{"maxresdefault","hqdefault"}){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            try{Bitmap bitmap=downloadImage("https://i.ytimg.com/vi/"+key+"/"+size+".jpg",deadline,false);if(bitmap!=null)return bitmap;}
            catch(IOException ignored){}
        }
        return null;
    }

    private static String spotifyThumbnail(String id,long deadline) throws IOException,org.json.JSONException {
        if(!id.matches("[A-Za-z0-9]{22}"))return null;
        URL url=new URL("https://open.spotify.com/oembed?url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F"+id);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3500);connection.setReadTimeout(3500);
        try{
            int limit=16*1024;
            if(connection.getResponseCode()!=200||connection.getContentLength()>limit)return null;
            byte[] body=readBounded(connection,limit,deadline);
            if(body==null)return null;
            String address=new JSONObject(new String(body,java.nio.charset.StandardCharsets.UTF_8)).optString("thumbnail_url");
            return ImportMatch.spotifyArtwork(address)?address:null;
        }finally{connection.disconnect();}
    }

    private static byte[] readBounded(HttpURLConnection connection,int limit,long deadline) throws IOException {
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline||output.size()+count>limit)return null;
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }

    private static Bitmap downloadImage(String address,long deadline,boolean cover) throws IOException {
        URL url=new URL(address);
        boolean spotify=ImportMatch.spotifyArtwork(address);
        for(int redirects=0;redirects<=3;redirects++){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3500);connection.setReadTimeout(3500);
            try{
            int status=connection.getResponseCode();
            if((cover||spotify)&&redirects<3&&(status==301||status==302||status==303||status==307||status==308)){
                String location=connection.getHeaderField("Location");if(location==null||location.length()>2048)return null;
                URL next=new URL(url,location);
                String host=next.getHost();
                boolean knownHost=host.equals("coverartarchive.org")||host.equals("archive.org")||host.endsWith(".archive.org");
                if(spotify?!ImportMatch.spotifyArtwork(next.toExternalForm()):!knownHost||!"https".equals(next.getProtocol())
                        ||next.getUserInfo()!=null||next.getRef()!=null||next.getPort()!=-1&&next.getPort()!=443)return null;
                url=next;continue;
            }
            if(status!=200||connection.getContentLength()>MAX_BYTES)return null;
            byte[] content=readBounded(connection,MAX_BYTES,deadline);
            if(content==null||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(content,0,content.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>1_000_000)return null;
            return BitmapFactory.decodeByteArray(content,0,content.length);
            }finally{connection.disconnect();}
        }
        return null;
    }
}
