package com.apihealth.sdk

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private val events = LinkedBlockingDeque<TelemetryEvent>(config.maxQueuedEvents)
    private val flushScheduled = AtomicBoolean(false)
    private val executor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable -> Thread(runnable, "api-health-reporter").apply { isDaemon = true } },
    ).apply {
        removeOnCancelPolicy = true
        scheduleWithFixedDelay(
            { flushAvailable() },
            config.flushIntervalMs,
            config.flushIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun enqueue(event: TelemetryEvent) {
        if (!events.offerLast(event)) {
            events.pollFirst()
            events.offerLast(event)
            notify(ApiHealthDeliveryStatus.DROPPED, 1, "The in-memory telemetry buffer was full")
        }
        notify(ApiHealthDeliveryStatus.QUEUED, 1)
        if (events.size >= config.batchSize) flushAsync()
    }

    fun pendingEventCount(): Int = events.size

    fun flushAsync() {
        if (!flushScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                flushAvailable()
            } finally {
                flushScheduled.set(false)
                if (events.size >= config.batchSize) flushAsync()
            }
        }
    }

    private fun flushAvailable() {
        while (events.isNotEmpty()) {
            val batch = ArrayList<TelemetryEvent>(config.batchSize)
            events.drainTo(batch, config.batchSize)
            if (batch.isEmpty()) return

            when (val result = deliver(batch)) {
                is DeliveryResult.Delivered -> notify(ApiHealthDeliveryStatus.DELIVERED, batch.size)
                is DeliveryResult.PermanentFailure -> {
                    notify(ApiHealthDeliveryStatus.DROPPED, batch.size, result.message)
                }
                is DeliveryResult.RetryLater -> {
                    requeueFirst(batch)
                    notify(ApiHealthDeliveryStatus.RETRYING, batch.size, result.message)
                    return
                }
            }
        }
    }

    private fun deliver(batch: List<TelemetryEvent>): DeliveryResult {
        val payload = batch.joinToString(
            separator = ",",
            prefix = "{\"events\":[",
            postfix = "]}",
            transform = TelemetryEvent::toJson,
        )
        val request = Request.Builder()
            .url(config.endpointUrl.trimEnd('/') + "/batch")
            .header("X-Api-Key", config.apiKey)
            .header("User-Agent", "api-health-android/${ApiHealth.SDK_VERSION}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        repeat(config.maxDeliveryAttempts) { attempt ->
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
            runCatching { Thread.sleep(config.initialRetryDelayMs * (1L shl attempt)) }
                .onFailure { return DeliveryResult.RetryLater("Delivery retry was interrupted") }
        }
        return DeliveryResult.RetryLater("Delivery did not complete")
    }

    private fun requeueFirst(batch: List<TelemetryEvent>) {
        batch.asReversed().forEach { event ->
            if (!events.offerFirst(event)) {
                events.pollLast()
                events.offerFirst(event)
                notify(ApiHealthDeliveryStatus.DROPPED, 1, "A newer event was dropped while preserving a retry")
            }
        }
    }

    private fun notify(status: ApiHealthDeliveryStatus, count: Int, message: String? = null) {
        val report = ApiHealthDeliveryReport(status, count, events.size, message)
        runCatching { config.deliveryListener?.invoke(report) }
    }

    private sealed interface DeliveryResult {
        data object Delivered : DeliveryResult
        data class PermanentFailure(val message: String) : DeliveryResult
        data class RetryLater(val message: String) : DeliveryResult
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
