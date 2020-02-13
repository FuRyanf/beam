/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.samza.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.beam.runners.core.StateNamespaces;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.samza.util.FutureUtils;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions;
import org.apache.samza.operators.Scheduler;
import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * Bundle management for the {@link DoFnOp} that handles lifecycle of a bundle. It also serves as a
 * proxy for the {@link DoFnOp} to process watermark and decides to 1. Hold watermark if there is at
 * least one bundle in progress. 2. Propagates the watermark to downstream DAG, if all the previous
 * bundles have completed.
 *
 * <p>A bundle is considered complete only when the outputs corresponding to each element in the
 * bundle have been resolved. The output of an element is considered resolved based on the nature of
 * the ParDoFn 1. In case of synchronous ParDo, outputs of the element is resolved immediately after
 * the processElement returns. 2. In case of asynchronous ParDo, outputs of the element is resolved
 * when all the future emitted by the processElement is resolved.
 *
 * @param <OutT> output type of the {@link DoFnOp}
 */
public class BundleManager<OutT> {
  private static final long MIN_BUNDLE_CHECK_TIME_MS = 10L;

  private final long maxBundleSize;
  private final long maxBundleTimeMs;
  private final BundleProgressListener<OutT> bundleProgressListener;
  private final FutureCollector<OutT> futureCollector;
  private final Scheduler<KeyedTimerData<Void>> bundleTimerScheduler;
  private final String bundleCheckTimerId;

  // Number elements belonging to the current active bundle
  private transient AtomicLong currentBundleElementCount;
  // Number of bundles that are in progress but not yet finished
  private transient AtomicLong pendingBundleCount;
  // Denotes the start time of the current active bundle
  private transient AtomicLong bundleStartTime;
  // Denotes if there is an active in progress bundle. Note at a given time, we can have multiple
  // bundle in progress.
  // This flag denotes if there is a bundle that is current and hasn't been closed.
  private transient AtomicBoolean isBundleStarted;
  // Holder for watermark which gets propagated when the bundle is finished.
  private transient Instant bundleWatermarkHold;
  // A container for futures belonging to the current active bundle
  private transient List<CompletionStage<Collection<WindowedValue<OutT>>>>
      currentBundleResultFutures;

  public BundleManager(
      BundleProgressListener<OutT> bundleProgressListener,
      FutureCollector<OutT> futureCollector,
      long maxBundleSize,
      long maxBundleTimeMs,
      Scheduler<KeyedTimerData<Void>> bundleTimerScheduler,
      String bundleCheckTimerId) {
    this.maxBundleSize = maxBundleSize;
    this.maxBundleTimeMs = maxBundleTimeMs;
    this.bundleProgressListener = bundleProgressListener;
    this.bundleTimerScheduler = bundleTimerScheduler;
    this.bundleCheckTimerId = bundleCheckTimerId;
    this.futureCollector = futureCollector;

    if (maxBundleSize > 1) {
      scheduleNextBundleCheck();
    }

    // instance variable initialization for bundle tracking
    this.bundleStartTime = new AtomicLong(Long.MAX_VALUE);
    this.currentBundleResultFutures = Collections.synchronizedList(new ArrayList<>());
    this.currentBundleElementCount = new AtomicLong(0L);
    this.isBundleStarted = new AtomicBoolean(false);
    this.pendingBundleCount = new AtomicLong(0L);
  }

  /*
   * Schedule in processing time to check whether the current bundle should be closed. Note that
   * we only approximately achieve max bundle time by checking as frequent as half of the max bundle
   * time set by users. This would violate the max bundle time by up to half of it but should
   * acceptable in most cases (and cheaper than scheduling a timer at the beginning of every bundle).
   */
  private void scheduleNextBundleCheck() {
    final Instant nextBundleCheckTime =
        Instant.now().plus(Duration.millis(maxBundleTimeMs / 2 + MIN_BUNDLE_CHECK_TIME_MS));
    final TimerInternals.TimerData timerData =
        TimerInternals.TimerData.of(
            this.bundleCheckTimerId,
            StateNamespaces.global(),
            nextBundleCheckTime,
            TimeDomain.PROCESSING_TIME);
    bundleTimerScheduler.schedule(
        new KeyedTimerData<>(new byte[0], null, timerData), nextBundleCheckTime.getMillis());
  }

  void tryStartBundle() {
    futureCollector.prepare();

    if (isBundleStarted.compareAndSet(false, true)) {
      // make sure the previous bundle is sealed and futures are cleared
      Preconditions.checkArgument(
          currentBundleResultFutures.isEmpty(),
          "Current bundle futures should be empty" + "before starting a new bundle.");
      bundleStartTime.set(System.currentTimeMillis());
      pendingBundleCount.incrementAndGet();
      bundleProgressListener.onBundleStarted();
    }

    currentBundleElementCount.incrementAndGet();
  }

  void processWatermark(Instant watermark, OpEmitter<OutT> emitter) {
    // only propagate watermark immediately if no bundle is in progress and all of the previous
    // bundles have completed.
    if (!isBundleStarted.get() && pendingBundleCount.get() == 0) {
      bundleProgressListener.onWatermark(watermark, emitter);
    } else {
      // if there is a bundle in progress, hold back the watermark until end of the bundle
      this.bundleWatermarkHold = watermark;
      if (watermark.isEqual(BoundedWindow.TIMESTAMP_MAX_VALUE)) {
        // TODO: block on all futures and then fire finish bundle
        // for batch mode, the max watermark should force the bundle to close
        tryFinishBundle(emitter);
        // wait on all futures
      }
    }
  }

  void processTimer(KeyedTimerData<Void> keyedTimerData, OpEmitter<OutT> emitter) {
    // this is internal timer in processing time to check whether a bundle should be closed
    if (bundleCheckTimerId.equals(keyedTimerData.getTimerData().getTimerId())) {
      tryFinishBundle(emitter);
      scheduleNextBundleCheck();
    }
  }

  void tryFinishBundle(OpEmitter<OutT> emitter) {

    // we need to seal the output for each element within a bundle irrespective of the whether we
    // decide to finish the
    // bundle or not
    CompletionStage<Collection<WindowedValue<OutT>>> outputFuture = futureCollector.finish();

    if (shouldFinishBundle() && isBundleStarted.compareAndSet(true, false)) {
      // reset the bundle count
      // seal the bundle and emit the result future (collection of results)
      // chain the finish bundle invocation on the finish bundle
      currentBundleElementCount.set(0L);
      bundleStartTime.set(Long.MAX_VALUE);
      Instant watermarkHold = bundleWatermarkHold;
      bundleWatermarkHold = null;

      outputFuture =
          FutureUtils.flattenFutures(currentBundleResultFutures)
              .thenCombine(
                  outputFuture,
                  (ignored, res) -> {
                    bundleProgressListener.onBundleFinished(emitter);

                    if (watermarkHold != null) {
                      bundleProgressListener.onWatermark(watermarkHold, emitter);
                    }

                    pendingBundleCount.decrementAndGet();
                    return res;
                  });
      currentBundleResultFutures.clear();
    } else {
      currentBundleResultFutures.add(outputFuture);
    }

    // emit the future to the propagate it to rest of the DAG
    emitter.emitFuture(outputFuture);
  }

  @VisibleForTesting
  long getCurrentBundleElementCount() {
    return currentBundleElementCount.longValue();
  }

  @VisibleForTesting
  long getPendingBundleCount() {
    return pendingBundleCount.longValue();
  }

  @VisibleForTesting
  boolean isBundleStarted() {
    return isBundleStarted.get();
  }

  private boolean shouldFinishBundle() {
    return isBundleStarted.get()
        && (currentBundleElementCount.get() >= maxBundleSize
            || System.currentTimeMillis() - bundleStartTime.get() >= maxBundleTimeMs);
  }

  /**
   * A listener used to track the lifecycle of a bundle. Typically, the lifecycle of a bundle
   * consists of 1. Start bundle - Invoked when the bundle is started 2. Finish bundle - Invoked
   * when the bundle is complete. Refer to the docs under {@link BundleManager} for definition on
   * when a bundle is considered complete. 3. onWatermark - Invoked when watermark is ready to be
   * propagated to downstream DAG. Refer to the docs under {@link BundleManager} on when watermark
   * is held vs propagated.
   *
   * @param <OutT>
   */
  public interface BundleProgressListener<OutT> {
    void onBundleStarted();

    void onBundleFinished(OpEmitter<OutT> emitter);

    void onWatermark(Instant watermark, OpEmitter<OutT> emitter);
  }
}
