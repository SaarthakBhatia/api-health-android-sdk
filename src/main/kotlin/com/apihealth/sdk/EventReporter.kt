package com.apihealth.sdk

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class EventReporter(private val config: ApiHealthConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val events = LinkedBlockingDeque<QueuedTelemetryEvent>(config.maxQueuedEvents)
    private val knownEventIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val flushScheduled = AtomicBoolean(false)
    private val spoolDirty = AtomicBoolean(false)
    private val spoolTaskScheduled = AtomicBoolean(false)
    private val capturedEvents = AtomicLong()
    private val sampledOutEvents = AtomicLong()
    private val droppedEvents = AtomicLong()
    private val deliveredEvents = AtomicLong()
    private val deliveryBatches = AtomicLong()
    private val payloadBytesSent = AtomicLong()
    private val payloadBytesSavedByCompression = AtomicLong()
    private val queueOverflowDrops = AtomicLong()
    private val consecutiveDeliveryFailures = AtomicInteger()
    private val spool = config.offlineStorageDirectory?.let { directory ->
        OfflineEventSpool(
            directory = directory,
            identity = "${config.endpointUrl}|${config.appId}|${config.environment}",
            maxBytes = config.maxOfflineStorageBytes,
        )
    }
    private val executor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable -> Thread(runnable, "api-health-reporter").apply { isDaemon = true } },
    ).apply {
        removeOnCancelPolicy = true
    }

    @Volatile
    private var retryBatch: PreparedBatch? = null

    @Volatile
    private var retryNotBeforeMillis: Long = 0

    init {
        executor.execute(::restoreOfflineEvents)
        executor.scheduleWithFixedDelay(
            { flushAvailable() },
            config.flushIntervalMs,
            config.flushIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun enqueue(event: TelemetryEvent) {
        capturedEvents.incrementAndGet()
        val queued = QueuedTelemetryEvent.live(event)
        if (!knownEventIds.add(queued.eventId)) return

        if (!offerBounded(queued)) {
            knownEventIds.remove(queued.eventId)
            markDropped(1, queueOverflow = true, message = "The bounded telemetry buffer was full")
            return
        }

        notify(ApiHealthDeliveryStatus.QUEUED, 1)
        scheduleSpoolSnapshot()
        if (pendingEventCount() >= config.batchSize) flushAsync()
    }

    /**
     * Defers serialization until OkHttp has finished (or failed) the response body. The timeout
     * guarantees that streaming or leaked bodies cannot prevent telemetry delivery indefinitely.
     */
    fun enqueueWhenCallCompletes(event: TelemetryEvent, timingState: CallTimingState) {
        val submitted = AtomicBoolean(false)
        val enqueueOnce = {
            if (submitted.compareAndSet(false, true)) enqueue(event)
        }
        val fallback = executor.schedule(
            { enqueueOnce() },
            config.responseTimingTimeoutMs,
            TimeUnit.MILLISECONDS,
        )
        timingState.invokeOnCompletion {
            fallback.cancel(false)
            enqueueOnce()
        }
    }

    fun recordSampledOut() {
        sampledOutEvents.incrementAndGet()
    }

    fun effectiveSuccessSampleRate(): Double {
        val configured = config.successSampleRate
        if (!config.adaptiveSampling || configured == 0.0) return configured

        val pressure = pendingEventCount().toDouble() / config.maxQueuedEvents
        val pressureMultiplier = when {
            pressure < 0.50 && consecutiveDeliveryFailures.get() == 0 -> 1.0
            pressure < 0.75 -> 0.5
            pressure < 0.90 -> 0.25
            else -> 0.10
        }
        val floor = config.minimumSuccessSampleRate.coerceAtMost(configured)
        return (configured * pressureMultiplier).coerceAtLeast(floor).coerceAtMost(configured)
    }

    fun pendingEventCount(): Int = events.size + (retryBatch?.events?.size ?: 0)

    fun telemetryStats() = ApiHealthTelemetryStats(
        capturedEvents = capturedEvents.get(),
        sampledOutEvents = sampledOutEvents.get(),
        droppedEvents = droppedEvents.get(),
        deliveredEvents = deliveredEvents.get(),
        deliveryBatches = deliveryBatches.get(),
        payloadBytesSent = payloadBytesSent.get(),
        payloadBytesSavedByCompression = payloadBytesSavedByCompression.get(),
        pendingEvents = pendingEventCount(),
    )

    fun flushAsync() {
        if (!flushScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                flushAvailable()
            } finally {
                flushScheduled.set(false)
                if (pendingEventCount() >= config.batchSize && retryNotBeforeMillis <= System.currentTimeMillis()) {
                    flushAsync()
                }
            }
        }
    }

    private fun flushAvailable() {
        if (retryNotBeforeMillis > System.currentTimeMillis()) return

        while (retryBatch != null || events.isNotEmpty()) {
            val batch = retryBatch ?: prepareBatch() ?: return
            retryBatch = batch
            persistSpoolSnapshotNow()

            when (val result = deliver(batch)) {
                is DeliveryResult.Delivered -> {
                    retryBatch = null
                    retryNotBeforeMillis = 0
                    consecutiveDeliveryFailures.set(0)
                    batch.events.forEach { knownEventIds.remove(it.eventId) }
                    deliveredEvents.addAndGet(batch.events.size.toLong())
                    notify(
                        status = ApiHealthDeliveryStatus.DELIVERED,
                        count = batch.events.size,
                        payloadBytes = batch.payload.size.toLong(),
                        uncompressedPayloadBytes = batch.uncompressedBytes.toLong(),
                    )
                    scheduleSpoolSnapshot()
                }

                is DeliveryResult.PermanentFailure -> {
                    retryBatch = null
                    batch.events.forEach { knownEventIds.remove(it.eventId) }
                    markDropped(batch.events.size, message = result.message)
                    scheduleSpoolSnapshot()
                }

                is DeliveryResult.RetryLater -> {
                    val failureCount = consecutiveDeliveryFailures.incrementAndGet()
                    val backoff = offlineBackoffMillis(failureCount)
                    retryNotBeforeMillis = System.currentTimeMillis() + backoff
                    notify(ApiHealthDeliveryStatus.RETRYING, batch.events.size, result.message)
                    scheduleSpoolSnapshot()
                    executor.schedule({ flushAsync() }, backoff, TimeUnit.MILLISECONDS)
                    return
                }
            }
        }
    }

    private fun prepareBatch(): PreparedBatch? {
        val batchEvents = ArrayList<QueuedTelemetryEvent>(config.batchSize)
        val serializedEvents = ArrayList<String>(config.batchSize)
        var estimatedBytes = BATCH_ENVELOPE_ALLOWANCE_BYTES

        while (batchEvents.size < config.batchSize) {
            val event = events.pollFirst() ?: break
            val json = event.toJson()
            val eventBytes = json.toByteArray(Charsets.UTF_8).size + 1
            if (batchEvents.isNotEmpty() && estimatedBytes + eventBytes > config.maxBatchBytes) {
                events.offerFirst(event)
                break
            }
            batchEvents += event
            serializedEvents += json
            estimatedBytes += eventBytes
        }
        if (batchEvents.isEmpty()) return null

        val batchId = UUID.randomUUID().toString()
        val sentAt = isoTimestamp()
        val droppedSinceLastBatch = queueOverflowDrops.getAndSet(0)
        val payloadJson = serializedEvents.joinToString(
            separator = ",",
            prefix = "{\"batchId\":\"$batchId\",\"sentAt\":\"$sentAt\",\"droppedSinceLastBatch\":$droppedSinceLastBatch,\"events\":[",
            postfix = "]}",
        )
        val uncompressed = payloadJson.toByteArray(Charsets.UTF_8)
        val compressed = if (config.gzipBatches && uncompressed.size >= config.gzipMinimumBytes) {
            gzip(uncompressed).takeIf { it.size < uncompressed.size }
        } else {
            null
        }

        return PreparedBatch(
            id = batchId,
            events = batchEvents,
            payload = compressed ?: uncompressed,
            uncompressedBytes = uncompressed.size,
            gzipped = compressed != null,
        )
    }

    private fun deliver(batch: PreparedBatch): DeliveryResult {
        val request = Request.Builder()
            .url(config.endpointUrl.trimEnd('/') + "/batch")
            .header("X-Api-Key", config.apiKey)
            .header("User-Agent", "api-health-android/${ApiHealth.SDK_VERSION}")
            .header("X-Api-Health-Batch-Id", batch.id)
            .apply { if (batch.gzipped) header("Content-Encoding", "gzip") }
            .post(batch.payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        repeat(config.maxDeliveryAttempts) { attempt ->
            deliveryBatches.incrementAndGet()
            payloadBytesSent.addAndGet(batch.payload.size.toLong())
            if (batch.gzipped) {
                payloadBytesSavedByCompression.addAndGet(
                    (batch.uncompressedBytes - batch.payload.size).coerceAtLeast(0).toLong(),
                )
            }
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return DeliveryResult.Delivered
                    val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                    if (!retryable) {
                        return DeliveryResult.PermanentFailure("Ingestion returned HTTP ${response.code}")
                    }
                    if (attempt == config.maxDeliveryAttempts - 1) {
                        return DeliveryResult.RetryLater("Ingestion returned HTTP ${response.code}")
                    }
                }
            } catch (exception: Exception) {
                if (attempt == config.maxDeliveryAttempts - 1) {
                    return DeliveryResult.RetryLater(exception.message ?: exception.javaClass.simpleName)
                }
            }
            val retryDelay = jitteredDelay(config.initialRetryDelayMs * (1L shl attempt))
            runCatching { Thread.sleep(retryDelay) }
                .onFailure { return DeliveryResult.RetryLater("Delivery retry was interrupted") }
        }
        return DeliveryResult.RetryLater("Delivery did not complete")
    }

    private fun offerBounded(event: QueuedTelemetryEvent): Boolean {
        if (pendingEventCount() < config.maxQueuedEvents && events.offerLast(event)) return true
        if (!event.isCritical) return false

        val sampledSuccess = events.firstOrNull { !it.isCritical }
        val evicted = if (sampledSuccess != null && events.remove(sampledSuccess)) {
            sampledSuccess
        } else {
            events.pollFirst()
        }
        if (evicted == null && pendingEventCount() >= config.maxQueuedEvents) return false
        evicted?.let {
            knownEventIds.remove(it.eventId)
            markDropped(
                count = 1,
                queueOverflow = true,
                message = "A lower-priority event was evicted from the full buffer",
            )
        }
        return events.offerLast(event)
    }

    private fun restoreOfflineEvents() {
        val restored = spool?.restore().orEmpty()
        var restoredCount = 0
        restored.asReversed().forEach { event ->
            if (!knownEventIds.add(event.eventId)) return@forEach
            if (events.offerFirst(event)) {
                restoredCount += 1
            } else {
                knownEventIds.remove(event.eventId)
            }
        }
        if (restoredCount > 0) {
            notify(ApiHealthDeliveryStatus.QUEUED, restoredCount, "Restored from the offline spool")
            flushAsync()
        }
    }

    private fun scheduleSpoolSnapshot() {
        if (spool == null) return
        spoolDirty.set(true)
        if (!spoolTaskScheduled.compareAndSet(false, true)) return
        executor.schedule({
            try {
                spoolDirty.set(false)
                persistSpoolSnapshotNow()
            } finally {
                spoolTaskScheduled.set(false)
                if (spoolDirty.get()) scheduleSpoolSnapshot()
            }
        }, SPOOL_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun persistSpoolSnapshotNow() {
        spool?.replace(buildList {
            retryBatch?.events?.let(::addAll)
            addAll(events.toList())
        })
    }

    private fun markDropped(count: Int, queueOverflow: Boolean = false, message: String) {
        droppedEvents.addAndGet(count.toLong())
        if (queueOverflow) queueOverflowDrops.addAndGet(count.toLong())
        notify(ApiHealthDeliveryStatus.DROPPED, count, message)
    }

    private fun offlineBackoffMillis(failureCount: Int): Long {
        val exponent = (failureCount - 1).coerceIn(0, 20)
        val unbounded = config.initialRetryDelayMs * (1L shl exponent)
        return jitteredDelay(unbounded.coerceAtMost(config.maxOfflineRetryDelayMs))
    }

    private fun jitteredDelay(delayMs: Long): Long {
        val jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2)
        return (delayMs * jitter).toLong().coerceIn(100, config.maxOfflineRetryDelayMs)
    }

    private fun notify(
        status: ApiHealthDeliveryStatus,
        count: Int,
        message: String? = null,
        payloadBytes: Long = 0,
        uncompressedPayloadBytes: Long = 0,
    ) {
        val report = ApiHealthDeliveryReport(
            status = status,
            eventCount = count,
            pendingEventCount = pendingEventCount(),
            message = message,
            payloadBytes = payloadBytes,
            uncompressedPayloadBytes = uncompressedPayloadBytes,
        )
        runCatching { config.deliveryListener?.invoke(report) }
    }

    private fun isoTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

    private data class PreparedBatch(
        val id: String,
        val events: List<QueuedTelemetryEvent>,
        val payload: ByteArray,
        val uncompressedBytes: Int,
        val gzipped: Boolean,
    )

    private sealed interface DeliveryResult {
        data object Delivered : DeliveryResult
        data class PermanentFailure(val message: String) : DeliveryResult
        data class RetryLater(val message: String) : DeliveryResult
    }

    private companion object {
        const val BATCH_ENVELOPE_ALLOWANCE_BYTES = 192
        const val SPOOL_DEBOUNCE_MS = 250L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
