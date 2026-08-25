package com.apihealth.sdk

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okio.Buffer
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

internal object EventFactory {

    fun createHttpResponse(
        request: Request,
        response: Response,
        durationMs: Long,
        occurredAt: String,
        config: ApiHealthConfig,
        context: ApiHealthEventContext = ApiHealth.contextFor(request, config),
    ): TelemetryEvent {
        val captureBodies = response.code >= 400 || config.captureSuccessfulBodies
        val requestBody = if (captureBodies) captureRequestBody(request, config) else CapturedBody(null, false)
        val responseBody = if (captureBodies) captureResponseBody(response, config) else CapturedBody(null, false)

        return TelemetryEvent(
            appId = config.appId,
            environment = config.environment,
            occurredAt = occurredAt,
            eventType = if (response.code >= 400) "HTTP_ERROR" else "HTTP_SUCCESS",
            httpMethod = request.method,
            url = sanitizeUrl(request, config.captureQueryValues),
            statusCode = response.code,
            durationMs = durationMs,
            requestHeaders = sanitizeHeaders(request.headers, config.normalizedRedactedHeaders),
            requestBody = requestBody.value,
            responseHeaders = sanitizeHeaders(response.headers, config.normalizedRedactedHeaders),
            responseBody = responseBody.value,
            appVersion = config.appVersion,
            deviceModel = config.deviceModel,
            osVersion = config.osVersion,
            sdkVersion = ApiHealth.SDK_VERSION,
            anonymousUserId = context.anonymousUserId,
            sessionId = context.sessionId,
            networkType = context.networkType,
            carrier = context.carrier,
            countryCode = context.countryCode,
            browser = context.browser,
            journeyName = context.journeyName,
            journeyStep = context.journeyStep,
            journeySequence = context.journeySequence,
            correlationId = context.correlationId,
            businessValue = context.businessValue,
            businessCurrency = context.businessCurrency,
            requestBodyTruncated = requestBody.truncated,
            responseBodyTruncated = responseBody.truncated,
        )
    }

    fun createNetworkFailure(
        request: Request,
        exception: IOException,
        durationMs: Long,
        occurredAt: String,
        config: ApiHealthConfig,
        context: ApiHealthEventContext = ApiHealth.contextFor(request, config),
    ): TelemetryEvent {
        val requestBody = captureRequestBody(request, config)
        return TelemetryEvent(
            appId = config.appId,
            environment = config.environment,
            occurredAt = occurredAt,
            eventType = "NETWORK_FAILURE",
            httpMethod = request.method,
            url = sanitizeUrl(request, config.captureQueryValues),
            failureType = classifyFailure(exception),
            errorMessage = exception.message ?: exception.javaClass.simpleName,
            durationMs = durationMs,
            requestHeaders = sanitizeHeaders(request.headers, config.normalizedRedactedHeaders),
            requestBody = requestBody.value,
            responseHeaders = emptyMap(),
            responseBody = null,
            appVersion = config.appVersion,
            deviceModel = config.deviceModel,
            osVersion = config.osVersion,
            sdkVersion = ApiHealth.SDK_VERSION,
            anonymousUserId = context.anonymousUserId,
            sessionId = context.sessionId,
            networkType = context.networkType,
            carrier = context.carrier,
            countryCode = context.countryCode,
            browser = context.browser,
            journeyName = context.journeyName,
            journeyStep = context.journeyStep,
            journeySequence = context.journeySequence,
            correlationId = context.correlationId,
            businessValue = context.businessValue,
            businessCurrency = context.businessCurrency,
            requestBodyTruncated = requestBody.truncated,
            responseBodyTruncated = false,
        )
    }

    internal fun classifyFailure(exception: IOException): String = when (exception) {
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "DNS"
        is SSLException -> "SSL"
        is ConnectException, is NoRouteToHostException, is SocketException -> "CONNECTION"
        is InterruptedIOException -> if (exception.message.orEmpty().contains("cancel", ignoreCase = true)) {
            "CANCELLED"
        } else {
            "IO"
        }
        else -> if (exception.message.orEmpty().contains("cancel", ignoreCase = true)) "CANCELLED" else "IO"
    }

    internal fun sanitizeHeaders(headers: Headers, redactedHeaders: Set<String>): Map<String, List<String>> =
        headers.names().associateWith { name ->
            if (name.lowercase() in redactedHeaders) listOf("[REDACTED]") else headers.values(name)
        }

    internal fun sanitizeUrl(request: Request, captureQueryValues: Boolean): String {
        if (captureQueryValues || request.url.querySize == 0) return request.url.toString()

        val sanitized = request.url.newBuilder().query(null)
        repeat(request.url.querySize) { index ->
            sanitized.addQueryParameter(request.url.queryParameterName(index), "[REDACTED]")
        }
        return sanitized.build().toString()
    }

    private fun captureRequestBody(request: Request, config: ApiHealthConfig): CapturedBody {
        val body = request.body ?: return CapturedBody(null, false)
        if (!config.captureRequestBody || body.isDuplex() || body.isOneShot()) return CapturedBody(null, false)
        if (!isTextual(body.contentType()?.toString())) return CapturedBody(null, false)

        val length = runCatching { body.contentLength() }.getOrDefault(-1)
        if (length < 0) return CapturedBody(null, false)
        if (length > config.maxBodyBytes) return CapturedBody(null, true)

        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            CapturedBody(buffer.readUtf8(), false)
        }.getOrDefault(CapturedBody(null, false))
    }

    private fun captureResponseBody(response: Response, config: ApiHealthConfig): CapturedBody {
        val body = response.body
        if (!config.captureResponseBody || !isTextual(body.contentType()?.toString())) {
            return CapturedBody(null, false)
        }

        return runCatching {
            val contentLength = body.contentLength()
            CapturedBody(
                value = response.peekBody(config.maxBodyBytes).string(),
                truncated = contentLength > config.maxBodyBytes,
            )
        }.getOrDefault(CapturedBody(null, false))
    }

    private fun isTextual(contentType: String?): Boolean {
        if (contentType == null) return false
        val value = contentType.lowercase()
        return value.startsWith("text/") ||
            "json" in value ||
            "xml" in value ||
            "form-urlencoded" in value
    }

    private data class CapturedBody(val value: String?, val truncated: Boolean)
}
