package com.apihealth.sdk

import java.text.SimpleDateFormat
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom
import okhttp3.Interceptor
import okhttp3.Response

internal class ApiHealthInterceptor(
    private val config: ApiHealthConfig,
    private val reporter: EventReporter,
    private val timingCollector: NetworkTimingCollector,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val startedAt = System.nanoTime()
        val request = chain.request()
        val timingState = timingCollector.timingFor(chain.call())
        val context = ApiHealth.contextFor(request, config)
        val response = try {
            chain.proceed(request)
        } catch (exception: IOException) {
            if (config.captureNetworkFailures) {
                runCatching {
                    EventFactory.createNetworkFailure(
                        request = request,
                        exception = exception,
                        durationMs = elapsedMillis(startedAt),
                        occurredAt = isoTimestamp(),
                        config = config,
                        context = context,
                        timingState = timingState,
                    )
                }.onSuccess { event -> reporter.enqueueWhenCallCompletes(event, timingState) }
            }
            throw exception
        }

        val durationMs = elapsedMillis(startedAt)
        val shouldCaptureError = config.captureHttpErrors && response.code in 400..599
        val isSuccessfulResponse = config.captureSuccessfulResponses && response.code in 200..399
        val isSlowResponse = config.alwaysCaptureSlowResponses && durationMs >= config.slowResponseThresholdMs
        val effectiveSampleRate = if (isSlowResponse) 1.0 else reporter.effectiveSuccessSampleRate()
        val shouldCaptureSuccess = isSuccessfulResponse &&
            ThreadLocalRandom.current().nextDouble() < effectiveSampleRate

        if (isSuccessfulResponse && !shouldCaptureSuccess) reporter.recordSampledOut()

        if (shouldCaptureError || shouldCaptureSuccess) {
            runCatching {
                EventFactory.createHttpResponse(
                    request = request,
                    response = response,
                    durationMs = durationMs,
                    occurredAt = isoTimestamp(),
                    config = config,
                    context = context,
                    sampleRate = if (shouldCaptureError) 1.0 else effectiveSampleRate,
                    timingState = timingState,
                )
            }.onSuccess { event -> reporter.enqueueWhenCallCompletes(event, timingState) }
        }

        return response
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun isoTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
