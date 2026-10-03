package com.mptaptap.playlisthub;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArtworkLoaderCheckTest {
    @Test public void staleImagesFailuresCacheAndClosedLoaderKeepTheCorrectArtwork() throws Exception {
        String oldId=id(),newId=id(),badId=id(),closeId=id();
        Bitmap old=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888),latest=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
        old.eraseColor(Color.RED);latest.eraseColor(Color.BLUE);
        CountDownLatch oldEntered=new CountDownLatch(1),releaseOld=new CountDownLatch(1),oldReturned=new CountDownLatch(1);
        CountDownLatch closeEntered=new CountDownLatch(1),releaseClose=new CountDownLatch(1),closeReturned=new CountDownLatch(1);
        AtomicInteger fetched=new AtomicInteger();ImageView[] view=new ImageView[1];
        ArtworkLoader loader=new ArtworkLoader(videoId->{
            fetched.incrementAndGet();
            if(videoId.equals(badId))return null;
            if(videoId.equals(newId))return latest;
            CountDownLatch entered=videoId.equals(oldId)?oldEntered:closeEntered;
            CountDownLatch release=videoId.equals(oldId)?releaseOld:releaseClose;
            CountDownLatch returned=videoId.equals(oldId)?oldReturned:closeReturned;
            entered.countDown();
            try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            finally{returned.countDown();}
            return old;
        });
        try{
            main(()->{view[0]=new ImageView(InstrumentationRegistry.getInstrumentation().getTargetContext());loader.load(view[0],oldId);});
            assertTrue(oldEntered.await(3,TimeUnit.SECONDS));
            main(()->loader.load(view[0],newId));awaitTag(view[0],newId);
            main(()->assertSame(latest,((BitmapDrawable)view[0].getDrawable()).getBitmap()));
            releaseOld.countDown();assertTrue(oldReturned.await(3,TimeUnit.SECONDS));
            Thread.sleep(300);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(()->{assertEquals(newId,view[0].getTag());assertSame(latest,((BitmapDrawable)view[0].getDrawable()).getBitmap());});
            int beforeCache=fetched.get();main(()->loader.load(view[0],newId));
            main(()->{assertEquals(newId,view[0].getTag());assertSame(latest,((BitmapDrawable)view[0].getDrawable()).getBitmap());});
            assertEquals(beforeCache,fetched.get());
            main(()->loader.load(view[0],badId));awaitTag(view[0],null);
            main(()->assertFalse(view[0].getDrawable() instanceof BitmapDrawable));
            int beforeInvalid=fetched.get();main(()->loader.load(view[0],"../unsafe"));
            main(()->{assertNull(view[0].getTag());assertFalse(view[0].getDrawable() instanceof BitmapDrawable);});
            assertEquals(beforeInvalid,fetched.get());
            main(()->loader.load(view[0],closeId));assertTrue(closeEntered.await(3,TimeUnit.SECONDS));
            loader.close();releaseClose.countDown();assertTrue(closeReturned.await(3,TimeUnit.SECONDS));
            Thread.sleep(300);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(()->{assertNotEquals(closeId,view[0].getTag());assertFalse(view[0].getDrawable() instanceof BitmapDrawable);loader.load(view[0],newId);assertNull(view[0].getTag());});
        }finally{releaseOld.countDown();releaseClose.countDown();loader.close();}
    }

    private static String id(){return UUID.randomUUID().toString().replace("-","").substring(0,11);}
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private static void awaitTag(ImageView view,String expected) throws InterruptedException {
        long deadline=System.nanoTime()+3_000_000_000L;boolean[] ready=new boolean[1];
        while(System.nanoTime()<deadline){main(()->ready[0]=java.util.Objects.equals(expected,view.getTag()));if(ready[0])return;Thread.sleep(20);}
        throw new AssertionError("Artwork did not finish loading");
    }
}
