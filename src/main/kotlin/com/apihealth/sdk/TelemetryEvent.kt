package com.apihealth.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.math.BigDecimal

internal data class TelemetryEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val appId: String,
    val environment: String,
    val occurredAt: String,
    val eventType: String,
    val httpMethod: String,
    val url: String,
    val statusCode: Int? = null,
    val failureType: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: String?,
    val responseHeaders: Map<String, List<String>>,
    val responseBody: String?,
    val appVersion: String?,
    val deviceModel: String?,
    val deviceManufacturer: String?,
    val osVersion: String?,
    val sdkVersion: String,
    val anonymousUserId: String? = null,
    val sessionId: String? = null,
    val networkType: String? = null,
    val carrier: String? = null,
    val countryCode: String? = null,
    val browser: String? = null,
    val journeyName: String? = null,
    val journeyStep: String? = null,
    val journeySequence: Int? = null,
    val correlationId: String? = null,
    val callId: String? = null,
    val attemptNumber: Int? = null,
    val businessValue: BigDecimal? = null,
    val businessCurrency: String? = null,
    val requestBodyTruncated: Boolean,
    val responseBodyTruncated: Boolean,
    val sampleRate: Double = 1.0,
    val timingState: CallTimingState? = null,
) {
    fun toJson(): String = buildJsonObject {
        val timing = timingState?.snapshot()
        put("eventId", eventId)
        put("appId", appId)
        put("environment", environment)
        put("occurredAt", occurredAt)
        put("eventType", eventType)
        put("httpMethod", httpMethod)
        put("url", url)
        statusCode?.let { put("statusCode", it) }
        failureType?.let { put("failureType", it) }
        errorMessage?.let { put("errorMessage", it) }
        put("durationMs", durationMs)
        put("requestHeaders", requestHeaders.toJsonObject())
        requestBody?.let { put("requestBody", it) }
        put("responseHeaders", responseHeaders.toJsonObject())
        responseBody?.let { put("responseBody", it) }
        appVersion?.let { put("appVersion", it) }
        deviceModel?.let { put("deviceModel", it) }
        deviceManufacturer?.let { put("deviceManufacturer", it) }
        osVersion?.let { put("osVersion", it) }
        put("sdkVersion", sdkVersion)
        anonymousUserId?.let { put("anonymousUserId", it) }
        sessionId?.let { put("sessionId", it) }
        networkType?.let { put("networkType", it) }
        carrier?.let { put("carrier", it) }
        countryCode?.let { put("countryCode", it) }
        browser?.let { put("browser", it) }
        journeyName?.let { put("journeyName", it) }
        journeyStep?.let { put("journeyStep", it) }
        journeySequence?.let { put("journeySequence", it) }
        correlationId?.let { put("correlationId", it) }
        businessValue?.let { put("businessValue", JsonPrimitive(it)) }
        businessCurrency?.let { put("businessCurrency", it) }
        put("requestBodyTruncated", requestBodyTruncated)
        put("responseBodyTruncated", responseBodyTruncated)
        put("sampleRate", sampleRate)
        (callId ?: timing?.callId)?.let { put("callId", it) }
        (attemptNumber ?: timing?.attemptNumber)?.let { put("attemptNumber", it) }
        timing?.let {
            it.dnsMs?.let { value -> put("dnsMs", value) }
            it.connectMs?.let { value -> put("connectMs", value) }
            it.tlsMs?.let { value -> put("tlsMs", value) }
            it.requestWriteMs?.let { value -> put("requestWriteMs", value) }
            it.ttfbMs?.let { value -> put("ttfbMs", value) }
            it.responseReadMs?.let { value -> put("responseReadMs", value) }
            it.reusedConnection?.let { value -> put("reusedConnection", value) }
        }
    }.toString()

    private fun Map<String, List<String>>.toJsonObject() = JsonObject(
        mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) },
    )
}
