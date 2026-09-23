package com.apihealth.sdk

enum class ApiHealthDeliveryStatus {
    QUEUED,
    DELIVERED,
    RETRYING,
    DROPPED,
}

data class ApiHealthDeliveryReport(
    val status: ApiHealthDeliveryStatus,
    val eventCount: Int,
    val pendingEventCount: Int,
    val message: String? = null,
    val payloadBytes: Long = 0,
    val uncompressedPayloadBytes: Long = 0,
)
