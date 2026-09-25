package com.apihealth.sdk

import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request

object ApiHealth {
    const val SDK_VERSION = "0.8.0"

    private val reporters = ConcurrentHashMap<ReporterKey, EventReporter>()
    private val reporterLock = Any()
    private val sharedContext = AtomicReference(ApiHealthEventContext())
    private val automaticSession = AutomaticSessionTracker()

    /**
     * Adds API Health monitoring to the same OkHttp builder used by Retrofit.
     * The returned builder is the supplied instance, so existing factory code can stay fluent.
     */
    @JvmStatic
    fun install(
        okHttpBuilder: OkHttpClient.Builder,
        config: ApiHealthConfig,
    ): OkHttpClient.Builder {
        if (okHttpBuilder.interceptors().any { it is ApiHealthInterceptor }) return okHttpBuilder
        val key = ReporterKey(config.endpointUrl, config.apiKey, config.appId, config.environment)
        val reporter = reporters[key] ?: synchronized(reporterLock) {
            reporters[key] ?: EventReporter(config).also { reporters[key] = it }
        }
        // OkHttp exposes the configured factory on the built client, but not on Builder. Building a
        // snapshot does not start threads or calls and lets us preserve the application's listener.
        val applicationListenerFactory = okHttpBuilder.build().eventListenerFactory
        val timingCollector = NetworkTimingCollector(applicationListenerFactory)
        okHttpBuilder.eventListenerFactory(timingCollector)
        return okHttpBuilder.addInterceptor(ApiHealthInterceptor(config, reporter, timingCollector))
    }

    /** Requests an immediate asynchronous upload of all currently buffered telemetry. */
    @JvmStatic
    fun flush() {
        reporters.values.forEach(EventReporter::flushAsync)
    }

    /** Number of events waiting in memory across installed clients. */
    @JvmStatic
    fun pendingEventCount(): Int = reporters.values.sumOf(EventReporter::pendingEventCount)

    /** Process-local counters that quantify capture volume, sampling, drops, and upload bytes. */
    @JvmStatic
    fun telemetryStats(): ApiHealthTelemetryStats = reporters.values.fold(ApiHealthTelemetryStats()) {
        total, reporter -> total + reporter.telemetryStats()
    }

    /** Replaces shared context used by all subsequently completed requests. */
    @JvmStatic
    fun setContext(context: ApiHealthEventContext) {
        sharedContext.set(context)
    }

    /** Updates shared context atomically, useful when connectivity, screen, or journey state changes. */
    @JvmStatic
    fun updateContext(transform: (ApiHealthEventContext) -> ApiHealthEventContext) {
        while (true) {
            val current = sharedContext.get()
            if (sharedContext.compareAndSet(current, transform(current))) return
        }
    }

    @JvmStatic
    fun clearContext() {
        sharedContext.set(ApiHealthEventContext())
    }

    /**
     * Starts a fresh anonymous session immediately. This is optional because sessions also rotate
     * automatically after the configured inactivity timeout.
     */
    @JvmStatic
    fun startNewSession(): String = automaticSession.rotate()

    @JvmStatic
    fun startJourney(name: String, firstStep: String, sequence: Int = 0) {
        updateContext { it.copy(journeyName = name, journeyStep = firstStep, journeySequence = sequence) }
    }

    @JvmStatic
    fun updateJourneyStep(step: String, sequence: Int) {
        updateContext { it.copy(journeyStep = step, journeySequence = sequence) }
    }

    @JvmStatic
    fun endJourney() {
        updateContext { it.copy(journeyName = null, journeyStep = null, journeySequence = null) }
    }

    /** Adds request-specific context such as correlation ID or checkout value without HTTP headers. */
    @JvmStatic
    fun tag(requestBuilder: Request.Builder, context: ApiHealthEventContext): Request.Builder =
        requestBuilder.tag(ApiHealthEventContext::class.java, context)

    internal fun contextFor(request: Request, config: ApiHealthConfig): ApiHealthEventContext {
        val provided = runCatching { config.contextProvider?.invoke() }.getOrNull()
        val tagged = request.tag(ApiHealthEventContext::class.java)
        val automatic = if (config.automaticSessionTracking) {
            ApiHealthEventContext(sessionId = automaticSession.currentId(config.sessionTimeoutMs))
        } else {
            ApiHealthEventContext()
        }
        return automatic
            .mergedWith(config.initialContext)
            .mergedWith(sharedContext.get())
            .mergedWith(provided)
            .mergedWith(tagged)
    }

    private data class ReporterKey(
        val endpointUrl: String,
        val apiKey: String,
        val appId: String,
        val environment: String,
    )
}
