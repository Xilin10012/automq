/*
 * Copyright 2025, AutoMQ HK Limited.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.automq.stream.s3.compact;

import com.automq.stream.s3.ByteBufAlloc;
import com.automq.stream.s3.Config;
import com.automq.stream.s3.S3ObjectLogger;
import com.automq.stream.s3.StreamDataBlock;
import com.automq.stream.s3.compact.objects.CompactedObject;
import com.automq.stream.s3.compact.objects.CompactionType;
import com.automq.stream.s3.compact.operator.DataBlockReader;
import com.automq.stream.s3.compact.operator.DataBlockWriter;
import com.automq.stream.s3.compact.utils.CompactionUtils;
import com.automq.stream.s3.compact.utils.GroupByOffsetPredicate;
import com.automq.stream.s3.metadata.S3ObjectMetadata;
import com.automq.stream.s3.metadata.StreamMetadata;
import com.automq.stream.s3.metadata.StreamOffsetRange;
import com.automq.stream.s3.metrics.S3StreamMetricsManager;
import com.automq.stream.s3.metrics.TimerUtil;
import com.automq.stream.s3.objects.CommitStreamSetObjectRequest;
import com.automq.stream.s3.objects.ObjectAttributes;
import com.automq.stream.s3.objects.ObjectManager;
import com.automq.stream.s3.objects.ObjectStreamRange;
import com.automq.stream.s3.objects.StreamObject;
import com.automq.stream.s3.operator.LocalFileObjectStorage;
import com.automq.stream.s3.operator.ObjectStorage;
import com.automq.stream.s3.streams.StreamManager;
import com.automq.stream.utils.FutureUtil;
import com.automq.stream.utils.LogContext;
import com.automq.stream.utils.ThreadUtils;
import com.automq.stream.utils.Threads;
import com.automq.stream.utils.biniarysearch.StreamOffsetRangeSearchList;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.github.bucket4j.Bucket;

import static com.automq.stream.s3.metadata.ObjectUtils.NOOP_OBJECT_ID;

public class CompactionManager {
    private static final int MIN_COMPACTION_DELAY_MS = 10000;
    // Max refill rate for Bucket: 1 token per nanosecond
    private static final int MAX_THROTTLE_BYTES_PER_SEC = 1000000000;
    private final Logger logger;
    private final Logger s3ObjectLogger;
    private final ObjectManager objectManager;
    private final StreamManager streamManager;
    private final ObjectStorage objectStorage;
    private final CompactionAnalyzer compactionAnalyzer;
    private final ScheduledExecutorService compactionScheduledExecutor;
    private final ScheduledExecutorService bucketCallbackScheduledExecutor;
    private final ScheduledExecutorService utilityScheduledExecutor;
    private final ExecutorService compactThreadPool;
    private final ExecutorService forceSplitThreadPool;
    private final CompactionUploader uploader;
    private final Config config;
    private final int maxObjectNumToCompact;
    private final int compactionInterval;
    private final int forceSplitObjectPeriod;
    private final int maxStreamNumPerStreamSetObject;
    private final int maxStreamObjectNumPerCommit;
    private final long networkBandwidth;
    private final long compactionCacheSize;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile CompletableFuture<Void> forceSplitCf = null;
    private volatile CompletableFuture<Void> compactionCf = null;
    private Bucket compactionBucket = null;
    private boolean hasRemainingObjects = false;
    private Map<Long, List<StreamDataBlock>> streamDataBlockMap = new HashMap<>();
    private long compactionDelayTime = 0L;

    public CompactionManager(Config config, ObjectManager objectManager, StreamManager streamManager,
        ObjectStorage objectStorage) {
        String logPrefix = String.format("[CompactionManager id=%d] ", config.nodeId());
        this.logger = new LogContext(logPrefix).logger(CompactionManager.class);
        this.s3ObjectLogger = S3ObjectLogger.logger(logPrefix);
        this.config = config;
        this.objectManager = objectManager;
        this.streamManager = streamManager;
        this.objectStorage = objectStorage;
        this.compactionInterval = config.streamSetObjectCompactionInterval();
        this.forceSplitObjectPeriod = config.streamSetObjectCompactionForceSplitPeriod();
        this.maxObjectNumToCompact = config.streamSetObjectCompactionMaxObjectNum();
        this.networkBandwidth = config.networkBaselineBandwidth();
        this.uploader = new CompactionUploader(objectManager, objectStorage, config);
        this.compactionCacheSize = config.streamSetObjectCompactionCacheSize();
        long streamSplitSize = config.streamSetObjectCompactionStreamSplitSize();
        maxStreamNumPerStreamSetObject = config.maxStreamNumPerStreamSetObject();
        maxStreamObjectNumPerCommit = config.maxStreamObjectNumPerCommit();
        this.compactionAnalyzer = new CompactionAnalyzer(compactionCacheSize, streamSplitSize, maxStreamNumPerStreamSetObject,
            maxStreamObjectNumPerCommit, new LogContext(String.format("[CompactionAnalyzer id=%d] ", config.nodeId())));
        this.compactionScheduledExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("schedule-compact-executor-%d", true), logger, true, false);
        this.bucketCallbackScheduledExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("s3-data-block-reader-bucket-cb-%d", true), logger, true, false);
        this.utilityScheduledExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("compaction-utility-executor-%d", true), logger, true, false);
        this.compactThreadPool = Threads.newFixedThreadPoolWithMonitor(1, "object-compaction-manager", true, logger);
        this.forceSplitThreadPool = Threads.newFixedFastThreadLocalThreadPoolWithMonitor(1, "force-split-executor", true, logger);
        this.running.set(true);
        S3StreamMetricsManager.registerCompactionDelayTimeSuppler(() -> compactionDelayTime);
        this.logger.info("Compaction manager initialized with config: compactionInterval: {} min, compactionCacheSize: {} bytes, " +
                "streamSplitSize: {} bytes, forceSplitObjectPeriod: {} min, maxObjectNumToCompact: {}, maxStreamNumInStreamSet: {}, maxStreamObjectNum: {}",
            compactionInterval, compactionCacheSize, streamSplitSize, forceSplitObjectPeriod, maxObjectNumToCompact, maxStreamNumPerStreamSetObject, maxStreamObjectNumPerCommit);
    }

    public void start() {
        scheduleNextCompaction((long) this.compactionInterval * 60 * 1000);
        this.utilityScheduledExecutor.scheduleAtFixedRate(() ->
            this.objectManager.getServerObjects().whenComplete((data, ex) -> {
                if (ex != null) {
                    logger.error("Error while getting server objects ", ex);
                    return;
                }
                if (data == null || data.isEmpty()) {
                    this.compactionDelayTime = 0;
                    return;
                }
                data.sort(Comparator.comparingLong(S3ObjectMetadata::committedTimestamp));
                this.compactionDelayTime = System.currentTimeMillis() - data.get(0).committedTimestamp();
            }).join(), (long) this.compactionInterval * 2, 1, TimeUnit.MINUTES);
    }

    void scheduleNextCompaction(long delayMillis) {
        if (!running.get()) {
            logger.info("Compaction manager is shutdown, skip scheduling next compaction");
            return;
        }
        logger.info("Next Compaction started in {} ms", delayMillis);
        this.compactionScheduledExecutor.schedule(() -> {
            TimerUtil timerUtil = new TimerUtil();
            try {
                logger.info("Compaction started");
                this.compact()
                    .thenAccept(result -> logger.info("Compaction complete, total cost {} ms", timerUtil.elapsedAs(TimeUnit.MILLISECONDS)))
                    .exceptionally(ex -> {
                        logger.error("Compaction failed, cost {} ms, ", timerUtil.elapsedAs(TimeUnit.MILLISECONDS), ex);
                        return null;
                    }).join();
            } catch (Exception ex) {
                logger.error("Error while compacting objects ", ex);
            }
            cleanUp();
            long nextDelay = Math.max(MIN_COMPACTION_DELAY_MS, (long) this.compactionInterval * 60 * 1000 - timerUtil.elapsedAs(TimeUnit.MILLISECONDS));
            if (hasRemainingObjects) {
                nextDelay = MIN_COMPACTION_DELAY_MS;
                hasRemainingObjects = false;
            }
            scheduleNextCompaction(nextDelay);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            logger.warn("Compaction manager is already shutdown");
            return;
        }
        logger.info("Shutting down compaction manager");
        synchronized (this) {
            if (forceSplitCf != null) {
                // prevent block-waiting for force splitting objects
                forceSplitCf.cancel(true);
            }
            if (compactionCf != null) {
                // prevent block-waiting for uploading compacted objects
                compactionCf.cancel(true);
            }
        }
        shutdownAndAwaitTermination(this.compactionScheduledExecutor, 10, TimeUnit.SECONDS);
        shutdownAndAwaitTermination(this.bucketCallbackScheduledExecutor, 10, TimeUnit.SECONDS);
        shutdownAndAwaitTermination(this.utilityScheduledExecutor, 10, TimeUnit.SECONDS);
        this.uploader.shutdown();
        cleanUp();
        logger.info("Compaction manager shutdown complete");
    }

    private void shutdownAndAwaitTermination(ExecutorService executor, int timeout, TimeUnit timeUnit) {
        ThreadUtils.shutdownExecutor(executor, timeout, timeUnit, logger);
    }

    public CompletableFuture<Void> compact() {
        return this.objectManager.getServerObjects().thenComposeAsync(objectMetadataList -> {
            logger.info("Get {} stream set objects from metadata", objectMetadataList.size());
            if (objectMetadataList.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            if (objectMetadataList.stream().anyMatch(o -> o.bucket() == LocalFileObjectStorage.BUCKET_ID)) {
                logger.info("Skip the compaction, because main storage circuit breaker isn't in closed status");
                return CompletableFuture.completedFuture(null);
            }
            updateStreamDataBlockMap(objectMetadataList);
            List<Long> streamIds;
            try {
                streamIds = collectStreamIdsAndCheckBlockSize();
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            return this.streamManager.getStreams(new ArrayList<>(streamIds)).thenAcceptAsync(streamMetadataList -> {
                filterInvalidStreamDataBlocks(streamMetadataList);
                this.compact(streamMetadataList, objectMetadataList);
            }, compactThreadPool);
        }, compactThreadPool);
    }

    List<Long> collectStreamIdsAndCheckBlockSize() throws IllegalStateException {
        Set<Long> streamIds = new HashSet<>();
        for (List<StreamDataBlock> blocks : streamDataBlockMap.values()) {
            for (StreamDataBlock block : blocks) {
                if (block.getBlockSize() > compactionCacheSize) {
                    logger.error("Block {} size exceeds compaction cache size {}, skip compaction", block, compactionCacheSize);
                    throw new IllegalStateException("Block size exceeds compaction cache size");
                }
                streamIds.add(block.getStreamId());
            }
        }
        return new ArrayList<>(streamIds);
    }

    void updateStreamDataBlockMap(List<S3ObjectMetadata> objectMetadataList) {
        this.streamDataBlockMap = CompactionUtils.blockWaitObjectIndices(objectMetadataList, objectStorage, logger);
    }

    void filterInvalidStreamDataBlocks(List<StreamMetadata> streamMetadataList) {
        CompactionUtils.filterInvalidStreamDataBlocks(streamMetadataList, streamDataBlockMap);
    }

    void cleanUp() {
        this.streamDataBlockMap.clear();
    }

    private void compact(List<StreamMetadata> streamMetadataList,
        List<S3ObjectMetadata> objectMetadataList) throws CompletionException {
        if (!running.get()) {
            logger.info("Compaction manager is shutdown, skip compaction");
            return;
        }
        Map<Boolean, List<S3ObjectMetadata>> objectMetadataFilterMap = convertS3Objects(objectMetadataList);
        List<S3ObjectMetadata> objectsToForceSplit = objectMetadataFilterMap.get(true);
        List<S3ObjectMetadata> objectsToCompact = objectMetadataFilterMap.get(false);

        long totalSize = objectsToForceSplit.stream().mapToLong(S3ObjectMetadata::objectSize).sum();
        totalSize += objectsToCompact.stream().mapToLong(S3ObjectMetadata::objectSize).sum();
        // throttle compaction read to half of compaction interval because of write overhead
        int expectCompleteTime = Math.max(compactionInterval - 1, 1) /* ahead 1min*/;
        long expectReadBytesPerSec = Math.max(expectCompleteTime * 60L, totalSize / expectCompleteTime / 60);
        if (expectReadBytesPerSec < MAX_THROTTLE_BYTES_PER_SEC) {
            compactionBucket = Bucket.builder().addLimit(limit -> limit
                .capacity(expectReadBytesPerSec)
                .refillIntervally(expectReadBytesPerSec, Duration.ofSeconds(1))).build();
            logger.info("Throttle compaction read to {} bytes/s, expect to complete in no less than {}min",
                expectReadBytesPerSec, expectCompleteTime);
        } else {
            logger.warn("Compaction throttle rate {} bytes/s exceeds bucket refill limit, there will be no throttle for compaction this time", expectReadBytesPerSec);
            compactionBucket = null;
        }

        if (!objectsToForceSplit.isEmpty()) {
            // split stream set objects to separated stream objects
            forceSplitObjects(streamMetadataList, objectsToForceSplit);
        }
        // compact stream set objects
        compactObjects(streamMetadataList, objectsToCompact);
    }

    void forceSplitObjects(List<StreamMetadata> streamMetadataList, List<S3ObjectMetadata> objectsToForceSplit) {
        logger.info("Force split {} stream set objects", objectsToForceSplit.size());
        TimerUtil timerUtil = new TimerUtil();
        for (int i = 0; i < objectsToForceSplit.size(); i++) {
            if (!running.get()) {
                logger.info("Compaction manager is shutdown, abort force split progress");
                return;
            }
            timerUtil.reset();
            S3ObjectMetadata objectToForceSplit = objectsToForceSplit.get(i);
            logger.info("Force split progress {}/{}, splitting object {}, object size {}", i + 1, objectsToForceSplit.size(),
                objectToForceSplit.objectId(), objectToForceSplit.objectSize());
            CommitStreamSetObjectRequest request;
            try {
                request = buildSplitRequest(streamMetadataList, objectToForceSplit);
            } catch (Exception ex) {
                logger.error("Build force split request for object {} failed, ex: ", objectToForceSplit.objectId(), ex);
                continue;
            }
            if (request == null) {
                continue;
            }
            logger.info("Build force split request for object {} complete, generated {} stream objects, time cost: {} ms, start committing objects",
                objectToForceSplit.objectId(), request.getStreamObjects().size(), timerUtil.elapsedAs(TimeUnit.MILLISECONDS));
            timerUtil.reset();
            objectManager.commitStreamSetObject(request)
                .thenAccept(resp -> {
                    logger.info("Commit force split request succeed, time cost: {} ms", timerUtil.elapsedAs(TimeUnit.MILLISECONDS));
                    s3ObjectLogger.info("[COMPACT] {}", request);
                })
                .exceptionally(ex -> {
                    logger.error("Commit force split request failed, ex: ", ex);
                    return null;
                })
                .join();
        }
    }

    private void compactObjects(List<StreamMetadata> streamMetadataList, List<S3ObjectMetadata> objectsToCompact)
        throws CompletionException {
        if (!running.get()) {
            logger.info("Compaction manager is shutdown, skip compacting objects");
            return;
        }
        if (objectsToCompact.isEmpty()) {
            return;
        }
        // sort by S3 object data time in descending order
        objectsToCompact.sort((o1, o2) -> Long.compare(o2.dataTimeInMs(), o1.dataTimeInMs()));
        int totalSize = objectsToCompact.size();
        if (maxObjectNumToCompact < totalSize) {
            // compact latest S3 objects first when number of objects to compact exceeds maxObjectNumToCompact
            objectsToCompact = objectsToCompact.subList(0, maxObjectNumToCompact);
            hasRemainingObjects = true;
        }
        logger.info("Compact {} stream set objects, total {} objects to compact", objectsToCompact.size(), totalSize);
        TimerUtil timerUtil = new TimerUtil();
        CommitStreamSetObjectRequest request = buildCompactRequest(streamMetadataList, objectsToCompact);
        if (!running.get()) {
            logger.info("Compaction manager is shutdown, skip committing compaction request");
            return;
        }
        if (request == null) {
            return;
        }
        if (request.getCompactedObjectIds().isEmpty()) {
            logger.info("No stream set objects to compact");
            return;
        }
        logger.info("Build compact request for {} stream set objects complete, stream set object id: {}, stream set object size: {}, stream object num: {}, time cost: {}, start committing objects",
            request.getCompactedObjectIds().size(), request.getObjectId(), request.getObjectSize(), request.getStreamObjects().size(), timerUtil.elapsedAs(TimeUnit.MILLISECONDS));
        timerUtil.reset();
        objectManager.commitStreamSetObject(request)
            .thenAccept(resp -> {
                logger.info("Commit compact request succeed, time cost: {} ms", timerUtil.elapsedAs(TimeUnit.MILLISECONDS));
                s3ObjectLogger.info("[COMPACT] {}", request);
            })
            .exceptionally(ex -> {
                logger.error("Commit compact request failed, ex: ", ex);
                return null;
            })
            .join();
    }

    private void logCompactionPlans(List<CompactionPlan> compactionPlans, Set<Long> excludedObjectIds) {
        if (compactionPlans.isEmpty()) {
            logger.info("No compaction plans to execute");
            return;
        }
        long streamObjectNum = compactionPlans.stream()
            .mapToLong(p -> p.compactedObjects().stream()
                .filter(o -> o.type() == CompactionType.SPLIT)
                .count())
            .sum();
        long streamSetObjectSize = compactionPlans.stream()
            .mapToLong(p -> p.compactedObjects().stream()
                .filter(o -> o.type() == CompactionType.COMPACT)
                .mapToLong(CompactedObject::size)
                .sum())
            .sum();
        int streamSetObjectNum = streamSetObjectSize > 0 ? 1 : 0;
        logger.info("Compaction plans: expect to generate {} Stream Object, {} stream set object with size {} in {} iterations, objects excluded: {}",
            streamObjectNum, streamSetObjectNum, streamSetObjectSize, compactionPlans.size(), excludedObjectIds);
    }

    public CompletableFuture<Void> forceSplitAll() {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        //TODO: deal with metadata delay
        this.compactionScheduledExecutor.execute(() -> this.objectManager.getServerObjects().thenAcceptAsync(objectMetadataList -> {
            if (objectMetadataList.isEmpty()) {
                logger.info("No stream set objects to force split");
                cf.complete(null);
                return;
            }
            updateStreamDataBlockMap(objectMetadataList);
            List<Long> streamIds = streamDataBlockMap.values().stream().flatMap(Collection::stream)
                .map(StreamDataBlock::getStreamId).distinct().collect(Collectors.toList());
            this.streamManager.getStreams(streamIds).thenAcceptAsync(streamMetadataList -> {
                filterInvalidStreamDataBlocks(streamMetadataList);
                forceSplitObjects(streamMetadataList, objectMetadataList);
                cf.complete(null);
            }, forceSplitThreadPool);
        }, forceSplitThreadPool).exceptionally(ex -> {
            logger.error("Error while force split all stream set objects ", ex);
            cf.completeExceptionally(ex);
            return null;
        }));

        return cf;
    }

    /**
     * Split specified stream set object into stream objects.
     *
     * @param objectMetadata     stream set object to split
     * @param cfs                List of CompletableFuture of StreamObject
     * @return true if split succeed, false otherwise
     */
    private boolean splitStreamSetObject(S3ObjectMetadata objectMetadata, Collection<CompletableFuture<StreamObject>> cfs) {
        if (objectMetadata == null) {
            return false;
        }

        List<StreamDataBlock> streamDataBlocks = streamDataBlockMap.get(objectMetadata.objectId());
        if (streamDataBlocks.isEmpty()) {
            // object is empty, metadata is out of date
            logger.info("Object {} is out of date, will be deleted after compaction", objectMetadata.objectId());
            return true;
        }

        cfs.addAll(groupAndSplitStreamDataBlocks(objectMetadata, streamDataBlocks));
        return true;
    }

    Collection<CompletableFuture<StreamObject>> groupAndSplitStreamDataBlocks(S3ObjectMetadata objectMetadata,
        List<StreamDataBlock> streamDataBlocks) {
        List<Pair<List<StreamDataBlock>, CompletableFuture<StreamObject>>> groupedDataBlocks = new ArrayList<>();
        List<List<StreamDataBlock>> groupedStreamDataBlocks = CompactionUtils.groupStreamDataBlocks(streamDataBlocks, new GroupByOffsetPredicate());
        for (List<StreamDataBlock> group : groupedStreamDataBlocks) {
            groupedDataBlocks.add(new ImmutablePair<>(group, new CompletableFuture<>()));
        }
        logger.info("Force split object {}, expect to generate {} stream objects", objectMetadata.objectId(), groupedDataBlocks.size());

        int index = 0;
        while (index < groupedDataBlocks.size()) {
            List<Pair<List<StreamDataBlock>, CompletableFuture<StreamObject>>> batchGroup = new ArrayList<>();
            long readSize = 0;
            while (index < groupedDataBlocks.size()) {
                Pair<List<StreamDataBlock>, CompletableFuture<StreamObject>> group = groupedDataBlocks.get(index);
                List<StreamDataBlock> groupedStreamDataBlock = group.getLeft();
                long size = groupedStreamDataBlock.get(groupedStreamDataBlock.size() - 1).getBlockEndPosition() -
                    groupedStreamDataBlock.get(0).getBlockStartPosition();
                if (readSize + size > compactionCacheSize) {
                    break;
                }
                readSize += size;
                batchGroup.add(group);
                index++;
            }
            if (batchGroup.isEmpty()) {
                logger.error("Force split object failed, not be able to read any data block, maybe compactionCacheSize is too small");
                return new ArrayList<>();
            }
            // prepare N stream objects at one time
            objectManager.prepareObject(batchGroup.size(), TimeUnit.MINUTES.toMillis(CompactionConstants.S3_OBJECT_TTL_MINUTES))
                .thenComposeAsync(objectId -> {
                    List<StreamDataBlock> blocksToRead = batchGroup.stream().flatMap(p -> p.getLeft().stream()).collect(Collectors.toList());
                    DataBlockReader reader = new DataBlockReader(objectMetadata, objectStorage, compactionBucket, bucketCallbackScheduledExecutor);
                    // batch read
                    reader.readBlocks(blocksToRead, Math.min(CompactionConstants.S3_OBJECT_MAX_READ_BATCH, networkBandwidth));

                    List<CompletableFuture<Void>> cfs = new ArrayList<>();
                    for (Pair<List<StreamDataBlock>, CompletableFuture<StreamObject>> pair : batchGroup) {
                        List<StreamDataBlock> blocks = pair.getLeft();
                        DataBlockWriter writer = new DataBlockWriter(objectId, objectStorage, config.objectPartSize());
                        CompletableFuture<Void> cf = CompactionUtils.chainWriteDataBlock(writer, blocks, forceSplitThreadPool);
                        long finalObjectId = objectId;
                        cfs.add(cf.thenCompose(nil -> writer.close()).whenComplete((ret, ex) -> {
                            if (ex != null) {
                                logger.error("write to stream object {} failed", finalObjectId, ex);
                                writer.release();
                                blocks.forEach(StreamDataBlock::release);
                                return;
                            }
                            StreamObject streamObject = new StreamObject();
                            streamObject.setObjectId(finalObjectId);
                            streamObject.setStreamId(blocks.get(0).getStreamId());
                            streamObject.setStartOffset(blocks.get(0).getStartOffset());
                            streamObject.setEndOffset(blocks.get(blocks.size() - 1).getEndOffset());
                            streamObject.setObjectSize(writer.size());
                            streamObject.setAttributes(ObjectAttributes.builder().bucket(writer.bucketId()).build().attributes());
                            pair.getValue().complete(streamObject);
                        }));
                        objectId++;
                    }
                    return CompletableFuture.allOf(cfs.toArray(new CompletableFuture[0]));
                }, forceSplitThreadPool)
                .exceptionally(ex -> {
                    logger.error("Force split object {} failed", objectMetadata.objectId(), ex);
                    for (Pair<List<StreamDataBlock>, CompletableFuture<StreamObject>> pair : groupedDataBlocks) {
                        pair.getValue().completeExceptionally(ex);
                    }
                    throw new IllegalStateException(String.format("Force split object %d failed", objectMetadata.objectId()), ex);
                }).join();
        }

        return groupedDataBlocks.stream().map(Pair::getValue).collect(Collectors.toList());
    }

    CommitStreamSetObjectRequest buildSplitRequest(List<StreamMetadata> streamMetadataList,
        S3ObjectMetadata objectToSplit) throws CompletionException {
        List<CompletableFuture<StreamObject>> cfs = new ArrayList<>();
        boolean status = splitStreamSetObject(objectToSplit, cfs);
        if (!status) {
            logger.error("Force split object {} failed, no stream object generated", objectToSplit.objectId());
            return null;
        }

        CommitStreamSetObjectRequest request = new CommitStreamSetObjectRequest();
        request.setObjectId(NOOP_OBJECT_ID);

        // wait for all force split objects to complete
        synchronized (this) {
            if (!running.get()) {
                logger.info("Compaction manager is shutdown, skip waiting for force splitting objects");
                return null;
            }
            forceSplitCf = CompletableFuture.allOf(cfs.toArray(new CompletableFuture[0]));
        }
        try {
            forceSplitCf.join();
        } catch (CancellationException exception) {
            logger.info("Force split objects cancelled");
            return null;
        }
        forceSplitCf = null;
        cfs.stream().map(e -> {
            try {
                return e.join();
            } catch (Exception ignored) {
                return null;
            }
        }).filter(Objects::nonNull).forEach(request::addStreamObject);

        request.setCompactedObjectIds(Collections.singletonList(objectToSplit.objectId()));
        if (isSanityCheckFailed(streamMetadataList, request)) {
            logger.error("Sanity check failed, force split result is illegal");
            return null;
        }

        return request;
    }

    CommitStreamSetObjectRequest buildCompactRequest(List<StreamMetadata> streamMetadataList,
        List<S3ObjectMetadata> objectsToCompact)
        throws CompletionException {
        CommitStreamSetObjectRequest request = new CommitStreamSetObjectRequest();
        request.setObjectId(-1L);

        Set<Long> compactedObjectIds = new HashSet<>();
        logger.info("{} stream set objects as compact candidates, total compaction size: {}",
            objectsToCompact.size(), objectsToCompact.stream().mapToLong(S3ObjectMetadata::objectSize).sum());
        Map<Long, List<StreamDataBlock>> streamDataBlockMapToCompact = new HashMap<>();
        for (S3ObjectMetadata objectMetadata : objectsToCompact) {
            List<StreamDataBlock> streamDataBlocks = streamDataBlockMap.get(objectMetadata.objectId());
            if (streamDataBlocks == null) {
                continue;
            }
            streamDataBlockMapToCompact.put(objectMetadata.objectId(), streamDataBlocks);
        }

        long now = System.currentTimeMillis();
        Set<Long> excludedObjectIds = new HashSet<>();
        List<CompactionPlan> compactionPlans = this.compactionAnalyzer.analyze(streamDataBlockMapToCompact, excludedObjectIds);
        logger.info("Analyze compaction plans complete, cost {}ms", System.currentTimeMillis() - now);
        logCompactionPlans(compactionPlans, excludedObjectIds);
        objectsToCompact = objectsToCompact.stream().filter(e -> !excludedObjectIds.contains(e.objectId())).collect(Collectors.toList());
        executeCompactionPlans(request, compactionPlans, objectsToCompact);

        if (!running.get()) {
            logger.info("Compaction manager is shutdown, skip constructing compaction request");
            return null;
        }

        compactionPlans.forEach(c -> c.streamDataBlocksMap().values().forEach(v -> v.forEach(b -> compactedObjectIds.add(b.getObjectId()))));

        // compact out-dated objects directly
        streamDataBlockMapToCompact.entrySet().stream().filter(e -> e.getValue().isEmpty()).forEach(e -> {
            logger.info("Object {} is out of date, will be deleted after compaction", e.getKey());
            compactedObjectIds.add(e.getKey());
        });

        request.setCompactedObjectIds(new ArrayList<>(compactedObjectIds));
        if (isSanityCheckFailed(streamMetadataList, request)) {
            logger.error("Sanity check failed, compaction result is illegal");
            return null;
        }

        return request;
    }

    boolean isSanityCheckFailed(List<StreamMetadata> streamMetadataList, CommitStreamSetObjectRequest request) {
        Map<Long, StreamMetadata> streamMetadataMap = streamMetadataList.stream()
            .collect(Collectors.toMap(StreamMetadata::streamId, e -> e));

        List<StreamOffsetRange> compactedStreamOffsetRanges = new ArrayList<>();
        request.getStreamRanges().forEach(o -> compactedStreamOffsetRanges.add(new StreamOffsetRange(o.getStreamId(), o.getStartOffset(), o.getEndOffset())));
        request.getStreamObjects().forEach(o -> compactedStreamOffsetRanges.add(new StreamOffsetRange(o.getStreamId(), o.getStartOffset(), o.getEndOffset())));
        Map<Long, StreamOffsetRangeSearchList> searchListMap = compactedStreamOffsetRanges
            .stream()
            .collect(Collectors.groupingBy(StreamOffsetRange::streamId))
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> sortAndMerge(e.getValue())));
        for (long objectId : request.getCompactedObjectIds()) {
            for (StreamDataBlock originalBlock : streamDataBlockMap.get(objectId)) {
                long streamId = originalBlock.getStreamId();
                if (!streamMetadataMap.containsKey(streamId)) {
                    // skip non-exist stream
                    continue;
                }
                long streamStartOffset = streamMetadataMap.get(streamId).startOffset();
                if (originalBlock.getEndOffset() <= streamStartOffset) {
                    // skip block that has been trimmed
                    continue;
                }
                StreamOffsetRangeSearchList searchList = searchListMap.get(streamId);
                if (searchList == null) {
                    logger.error("Sanity check failed, stream {} is missing after compact", streamId);
                    return true;
                }
                if (searchList.search(originalBlock) < 0) {
                    logger.error("Sanity check failed, object {} block {} is missing after compact", objectId, originalBlock);
                    return true;
                }
            }
        }

        return false;
    }

    private StreamOffsetRangeSearchList sortAndMerge(List<StreamOffsetRange> streamOffsetRangeList) {
        if (streamOffsetRangeList.size() < 2) {
            return new StreamOffsetRangeSearchList(streamOffsetRangeList);
        }
        long streamId = streamOffsetRangeList.get(0).streamId();
        Collections.sort(streamOffsetRangeList);
        List<StreamOffsetRange> mergedList = new ArrayList<>();
        long start = -1L;
        long end = -1L;
        for (int i = 0; i < streamOffsetRangeList.size() - 1; i++) {
            StreamOffsetRange curr = streamOffsetRangeList.get(i);
            StreamOffsetRange next = streamOffsetRangeList.get(i + 1);
            if (start == -1) {
                start = curr.startOffset();
                end = curr.endOffset();
            }
            if (curr.endOffset() < next.startOffset()) {
                mergedList.add(new StreamOffsetRange(curr.streamId(), start, end));
                start = next.startOffset();
            }
            end = next.endOffset();
        }
        mergedList.add(new StreamOffsetRange(streamId, start, end));

        return new StreamOffsetRangeSearchList(mergedList);
    }

    Map<Boolean, List<S3ObjectMetadata>> convertS3Objects(List<S3ObjectMetadata> streamSetObjectMetadata) {
        return new HashMap<>(streamSetObjectMetadata.stream()
            .collect(Collectors.partitioningBy(e -> (System.currentTimeMillis() - e.dataTimeInMs())
                >= TimeUnit.MINUTES.toMillis(this.forceSplitObjectPeriod))));
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    void executeCompactionPlans(CommitStreamSetObjectRequest request, List<CompactionPlan> compactionPlans,
        List<S3ObjectMetadata> s3ObjectMetadata)
        throws CompletionException {
        if (compactionPlans.isEmpty()) {
            return;
        }
        Map<Long, S3ObjectMetadata> s3ObjectMetadataMap = s3ObjectMetadata.stream()
            .collect(Collectors.toMap(S3ObjectMetadata::objectId, e -> e));
        List<StreamDataBlock> sortedStreamDataBlocks = new ArrayList<>();
        for (int i = 0; i < compactionPlans.size(); i++) {
            if (!running.get()) {
                logger.info("Compaction manager is shutdown, abort compaction progress");
                return;
            }
            // iterate over each compaction plan
            CompactionPlan compactionPlan = compactionPlans.get(i);
            long totalSize = compactionPlan.streamDataBlocksMap().values().stream().flatMap(List::stream)
                .mapToLong(StreamDataBlock::getBlockSize).sum();
            logger.info("Compaction progress {}/{}, read from {} stream set objects, total size: {}", i + 1, compactionPlans.size(),
                compactionPlan.streamDataBlocksMap().size(), totalSize);
            for (Map.Entry<Long, List<StreamDataBlock>> streamDataBlocEntry : compactionPlan.streamDataBlocksMap().entrySet()) {
                S3ObjectMetadata metadata = s3ObjectMetadataMap.get(streamDataBlocEntry.getKey());
                List<StreamDataBlock> streamDataBlocks = streamDataBlocEntry.getValue();
                DataBlockReader reader = new DataBlockReader(metadata, objectStorage, compactionBucket, bucketCallbackScheduledExecutor);
                reader.readBlocks(streamDataBlocks, Math.min(CompactionConstants.S3_OBJECT_MAX_READ_BATCH, networkBandwidth));
            }
            List<CompletableFuture<StreamObject>> streamObjectCfList = new ArrayList<>();
            CompletableFuture<Void> streamSetObjectChainWriteCf = CompletableFuture.completedFuture(null);
            for (CompactedObject compactedObject : compactionPlan.compactedObjects()) {
                if (compactedObject.type() == CompactionType.COMPACT) {
                    sortedStreamDataBlocks.addAll(compactedObject.streamDataBlocks());
                    streamSetObjectChainWriteCf = uploader.chainWriteStreamSetObject(streamSetObjectChainWriteCf, compactedObject);
                } else {
                    streamObjectCfList.add(uploader.writeStreamObject(compactedObject));
                }
            }

            List<CompletableFuture<?>> cfList = new ArrayList<>();
            cfList.add(streamSetObjectChainWriteCf);
            cfList.addAll(streamObjectCfList);
            synchronized (this) {
                if (!running.get()) {
                    logger.info("Compaction manager is shutdown, skip waiting for uploading objects");
                    return;
                }
                // wait for all stream objects and stream set object part to be uploaded
                compactionCf = new CompletableFuture<>();
                CompletableFuture.allOf(cfList.toArray(new CompletableFuture[0]))
                    .whenComplete((v, uploadException) -> {
                        if (uploadException != null) {
                            logger.error("Error while uploading compaction objects", uploadException);
                        }
                        FutureUtil.exec(uploader::forceUploadStreamSetObject, logger, "force upload sso")
                            .whenComplete((vv, forceUploadException) -> {
                                if (forceUploadException != null) {
                                    logger.error("Error while force uploading stream set object", uploadException);
                                }
                                if (uploadException != null || forceUploadException != null) {
                                    FutureUtil.exec(uploader::release, logger, "release uploader").whenComplete((vvv, releaseException) -> {
                                        if (releaseException != null) {
                                            logger.error("Unexpected exception while release uploader");
                                        }
                                        for (CompactedObject compactedObject : compactionPlan.compactedObjects()) {
                                            compactedObject.streamDataBlocks().forEach(StreamDataBlock::release);
                                        }
                                        if (uploadException != null) {
                                            compactionCf.completeExceptionally(new CompletionException("Uploading failed", uploadException));
                                        } else {
                                            compactionCf.completeExceptionally(new CompletionException("Force uploading sso failed", forceUploadException));
                                        }
                                    });
                                } else {
                                    compactionCf.complete(null);
                                }
                            });
                    });
            }
            try {
                compactionCf.join();
            } catch (CancellationException ex) {
                // release all data blocks on shutdown
                for (CompactedObject compactedObject : compactionPlan.compactedObjects()) {
                    compactedObject.streamDataBlocks().forEach(StreamDataBlock::release);
                }
                logger.warn("Compaction progress {}/{} is cancelled", i + 1, compactionPlans.size());
                return;
            }
            compactionCf = null;

            streamObjectCfList.stream().map(CompletableFuture::join).forEach(request::addStreamObject);

            if (ByteBufAlloc.getPolicy().isPooled()) {
                // Check if all blocks are released after each iteration
                List<CompactedObject> compactedObjects = compactionPlan.compactedObjects();
                for (CompactedObject compactedObject : compactedObjects) {
                    for (StreamDataBlock block : compactedObject.streamDataBlocks()) {
                        if (block.getDataCf().join().refCnt() > 0) {
                            logger.error("Block {} is not released after compaction, compact type: {}", block, compactedObject.type());
                        }
                    }
                }
            }
        }
        List<ObjectStreamRange> objectStreamRanges = CompactionUtils.buildObjectStreamRangeFromGroup(
            CompactionUtils.groupStreamDataBlocks(sortedStreamDataBlocks, new GroupByOffsetPredicate()));
        objectStreamRanges.forEach(request::addStreamRange);
        request.setObjectId(uploader.getStreamSetObjectId());
        // set stream set object id to be the first object id of compacted objects
        request.setOrderId(s3ObjectMetadata.get(0).objectId());
        request.setObjectSize(uploader.complete());
        request.setAttributes(ObjectAttributes.builder().bucket(uploader.bucketId()).build().attributes());
    }
}
