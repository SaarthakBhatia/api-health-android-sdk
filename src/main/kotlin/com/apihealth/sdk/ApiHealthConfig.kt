package com.apihealth.sdk

import java.net.URI

data class ApiHealthConfig(
    val endpointUrl: String,
    val apiKey: String,
    val appId: String,
    val environment: String,
    val appVersion: String? = null,
    val deviceModel: String? = null,
    val deviceManufacturer: String? = null,
    val osVersion: String? = null,
    val initialContext: ApiHealthEventContext = ApiHealthEventContext(),
    val contextProvider: (() -> ApiHealthEventContext?)? = null,
    val automaticSessionTracking: Boolean = true,
    val sessionTimeoutMs: Long = 30 * 60 * 1_000,
    val captureHttpErrors: Boolean = true,
    val captureNetworkFailures: Boolean = true,
    val captureSuccessfulResponses: Boolean = false,
    val successSampleRate: Double = 1.0,
    val captureSuccessfulBodies: Boolean = false,
    val captureRequestBody: Boolean = false,
    val captureResponseBody: Boolean = false,
    val captureQueryValues: Boolean = false,
    val maxBodyBytes: Long = 64 * 1024,
    val maxQueuedEvents: Int = 2_000,
    val batchSize: Int = 20,
    val flushIntervalMs: Long = 1_500,
    val maxDeliveryAttempts: Int = 3,
    val initialRetryDelayMs: Long = 500,
    val redactedHeaders: Set<String> = DEFAULT_REDACTED_HEADERS,
    val deliveryListener: ((ApiHealthDeliveryReport) -> Unit)? = null,
) {
    init {
        val endpoint = URI(endpointUrl)
        val localDevelopmentHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(endpoint.scheme == "https" || (endpoint.scheme == "http" && endpoint.host in localDevelopmentHosts)) {
            "endpointUrl must use HTTPS (HTTP is accepted only for local development hosts)"
        }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(appId.isNotBlank()) { "appId must not be blank" }
        require(environment.isNotBlank()) { "environment must not be blank" }
        require(sessionTimeoutMs in 60_000..86_400_000) {
            "sessionTimeoutMs must be between 1 minute and 24 hours"
        }
        require(successSampleRate in 0.0..1.0) { "successSampleRate must be between 0.0 and 1.0" }
        require(maxBodyBytes in 1..(1024 * 1024)) { "maxBodyBytes must be between 1 byte and 1 MiB" }
        require(maxQueuedEvents in 10..10_000) { "maxQueuedEvents must be between 10 and 10000" }
        require(batchSize in 1..100) { "batchSize must be between 1 and 100" }
        require(batchSize <= maxQueuedEvents) { "batchSize must not exceed maxQueuedEvents" }
        require(flushIntervalMs in 250..30_000) { "flushIntervalMs must be between 250 and 30000" }
        require(maxDeliveryAttempts in 1..5) { "maxDeliveryAttempts must be between 1 and 5" }
        require(initialRetryDelayMs in 100..10_000) { "initialRetryDelayMs must be between 100 and 10000" }
    }

    internal val normalizedRedactedHeaders = redactedHeaders.mapTo(mutableSetOf()) { it.lowercase() }

    companion object {
        val DEFAULT_REDACTED_HEADERS = setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-api-key",
        )
    }
}
