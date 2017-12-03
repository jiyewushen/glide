package com.bumptech.glide;

import static com.bumptech.glide.test.Matchers.anyDrawable;
import static com.bumptech.glide.test.Matchers.anyDrawableTarget;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemoryCacheAdapter;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.test.BitmapSubject;
import com.bumptech.glide.test.ConcurrencyHelper;
import com.bumptech.glide.test.GlideApp;
import com.bumptech.glide.test.ResourceIds;
import com.bumptech.glide.test.ResourceIds.raw;
import com.bumptech.glide.test.TearDownGlide;
import com.bumptech.glide.test.WaitModelLoader;
import com.bumptech.glide.test.WaitModelLoader.WaitModel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests various aspects of memory and disk caching to verify resources can be retrieved as we
 * expect.
 */
@RunWith(AndroidJUnit4.class)
public class CachingTest {
  private static final int IMAGE_SIZE_PIXELS = 500;
  // Store at least 10 500x500 pixel Bitmaps with the ARGB_8888 config to be safe.
  private static final long CACHE_SIZE_BYTES =
      IMAGE_SIZE_PIXELS * IMAGE_SIZE_PIXELS * 4 * 10;

  @Rule public TearDownGlide tearDownGlide = new TearDownGlide();
  @Mock private RequestListener<Drawable> requestListener;
  private final ConcurrencyHelper concurrency = new ConcurrencyHelper();

  private Context context;

  @Before
  public void setUp() throws InterruptedException {
    MockitoAnnotations.initMocks(this);
    context = InstrumentationRegistry.getTargetContext();

    Glide.init(
        context, new GlideBuilder().setMemoryCache(new LruResourceCache(CACHE_SIZE_BYTES)));
  }

  @Test
  public void submit_withDisabledMemoryCache_andResourceInActiveResources_loadsFromMemory() {
    Glide.init(
        context, new GlideBuilder().setMemoryCache(new MemoryCacheAdapter()));

    FutureTarget<Drawable> first =
        GlideApp.with(context)
            .load(raw.canonical)
            .submit();
    concurrency.get(first);

    concurrency.get(
        GlideApp.with(context)
            .load(ResourceIds.raw.canonical)
            .listener(requestListener)
            .submit());

    verify(requestListener)
        .onResourceReady(
            anyDrawable(),
            any(),
            anyDrawableTarget(),
            eq(DataSource.MEMORY_CACHE),
            anyBoolean());
  }

  @Test
  public void submit_withRequestClearedFromMemory_doesNotLoadFromMemory() {
    Glide.init(
        context, new GlideBuilder().setMemoryCache(new MemoryCacheAdapter()));

    // Allow the request to be run and GCed without being cleared.
    concurrency.loadOnOtherThread(new Runnable() {
      @Override
      public void run() {
        FutureTarget<Drawable> first =
            GlideApp.with(context)
                .load(raw.canonical)
                .submit();
        concurrency.get(first);
      }
    });

    // Wait for the weak reference to be cleared and the request to be removed from active
    // resources.
    // De-flake by allowing multiple tries
    for (int j = 0; j < 100; j++) {
      Runtime.getRuntime().gc();
      concurrency.pokeMainThread();
      try {
        // Loading again here won't shuffle our resource around because it only changes our
        // reference count from 1 to 2 and back. The reference we're waiting for will only be
        // decremented when the target is GCed.
        Target<Drawable> target =
            concurrency.wait(
                GlideApp.with(context)
                    .load(ResourceIds.raw.canonical)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .submit());
        GlideApp.with(context).clear(target);
      } catch (RuntimeException e) {
        // The item has been cleared from active resources.
        break;
      }
    }

    concurrency.get(
        GlideApp.with(context)
            .load(ResourceIds.raw.canonical)
            .listener(requestListener)
            .submit());

    verify(requestListener)
        .onResourceReady(
            anyDrawable(),
            any(),
            anyDrawableTarget(),
            not(eq(DataSource.MEMORY_CACHE)),
            anyBoolean());
  }

  @Test
  public void submit_withPreviousRequestClearedFromMemory_completesFromDataDiskCache()
      throws InterruptedException, ExecutionException, TimeoutException {
    FutureTarget<Drawable> future = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    concurrency.get(future);
    GlideApp.with(context).clear(future);

    clearMemoryCacheOnMainThread();

    concurrency.get(
        GlideApp.with(context)
            .load(ResourceIds.raw.canonical)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .listener(requestListener)
            .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS));

    verify(requestListener)
        .onResourceReady(
            anyDrawable(),
            any(),
            anyDrawableTarget(),
            eq(DataSource.DATA_DISK_CACHE),
            anyBoolean());
  }

  @Test
  public void submit_withPreviousButNoLongerReferencedIdenticalRequest_completesFromMemoryCache()
      throws InterruptedException, TimeoutException, ExecutionException {
    // We can't allow any mocks (RequestListner, Target etc) to reference this request or the test
    // will fail due to the transient strong reference to the request.
    concurrency.get(
        GlideApp.with(context)
            .load(ResourceIds.raw.canonical)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS));

    // Force the collection of weak references now that the listener/request in the first load is no
    // longer referenced.
    Runtime.getRuntime().gc();
    concurrency.pokeMainThread();

    concurrency.get(
        GlideApp.with(context)
            .load(ResourceIds.raw.canonical)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .listener(requestListener)
            .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS));

    verify(requestListener)
        .onResourceReady(
            anyDrawable(),
            any(),
            anyDrawableTarget(),
            eq(DataSource.MEMORY_CACHE),
            anyBoolean());
  }

  @Test
  public void submit_withPreviousButNoLongerReferencedIdenticalRequest_doesNotRecycleBitmap()
      throws InterruptedException, TimeoutException, ExecutionException {
    // We can't allow any mocks (RequestListener, Target etc) to reference this request or the test
    // will fail due to the transient strong reference to the request.
    Bitmap bitmap =
        concurrency.get(
            GlideApp.with(context)
                .asBitmap()
                .load(ResourceIds.raw.canonical)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS));

    // Force the collection of weak references now that the listener/request in the first load is no
    // longer referenced.
    Runtime.getRuntime().gc();
    concurrency.pokeMainThread();

    FutureTarget<Bitmap> future = GlideApp.with(context)
        .asBitmap()
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    concurrency.get(future);
    Glide.with(context).clear(future);

    clearMemoryCacheOnMainThread();

    BitmapSubject.assertThat(bitmap).isNotRecycled();
  }

  @Test
  public void clearDiskCache_doesNotPreventFutureLoads()
      throws ExecutionException, InterruptedException, TimeoutException {
    FutureTarget<Drawable> future = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    concurrency.get(future);
    GlideApp.with(context).clear(future);

    clearMemoryCacheOnMainThread();
    GlideApp.get(context).clearDiskCache();

    future = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    concurrency.get(future);

    GlideApp.with(context).clear(future);
    clearMemoryCacheOnMainThread();

    concurrency.get(
        GlideApp.with(context)
            .load(ResourceIds.raw.canonical)
            .listener(requestListener)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS));

    verify(requestListener)
        .onResourceReady(
            anyDrawable(),
            any(),
            anyDrawableTarget(),
            eq(DataSource.DATA_DISK_CACHE),
            anyBoolean());
  }

  // Tests #2428.
  @Test
  public void onlyRetrieveFromCache_withPreviousRequestLoadingFromSource_doesNotBlock() {
    final WaitModel<Integer> waitModel = WaitModelLoader.Factory.waitOn(ResourceIds.raw.canonical);

    FutureTarget<Drawable> loadFromSourceFuture = GlideApp.with(context)
        .load(waitModel)
        .submit();

    FutureTarget<Drawable> onlyFromCacheFuture = GlideApp.with(context)
        .load(waitModel)
        .onlyRetrieveFromCache(true)
        .submit();
    try {
      onlyFromCacheFuture.get(1000, TimeUnit.MILLISECONDS);
      fail("Expected only from cache Future to time out");
    } catch (InterruptedException | TimeoutException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      // Expected.
    }
    waitModel.countDown();

    assertThat(concurrency.get(loadFromSourceFuture)).isNotNull();
  }

  // Tests #2428.
  @Test
  public void submit_withRequestLoadingWithOnlyRetrieveFromCache_andNotInCache_doesNotFail() {
    // Block the main thread so that we know that both requests will be queued but not started at
    // the same time.
    final CountDownLatch blockMainThread = new CountDownLatch(1);
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
         try {
          blockMainThread.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    });

    // Queue the retrieve from cache request first.
    final Future<Drawable> firstQueuedFuture = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .onlyRetrieveFromCache(true)
        .submit();

    // Then queue the normal request.
    FutureTarget<Drawable> expectedFuture =
        GlideApp.with(context).load(ResourceIds.raw.canonical).submit();

    // Run the requests.
    blockMainThread.countDown();

    // Verify that the request that didn't have retrieve from cache succeeds
    assertThat(concurrency.get(expectedFuture)).isNotNull();
    // The first request only from cache should fail because the item is not in cache.
    assertThrows(RuntimeException.class, new ThrowingRunnable() {
      @Override
      public void run() throws Throwable {
        concurrency.get(firstQueuedFuture);
      }
    });
  }

  private void clearMemoryCacheOnMainThread() throws InterruptedException {
    concurrency.runOnMainThread(new Runnable() {
      @Override
      public void run() {
        Glide.get(context).clearMemory();
      }
    });
  }
}