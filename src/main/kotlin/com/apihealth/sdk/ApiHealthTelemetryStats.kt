package com.apihealth.sdk

/** Process-local delivery and cost counters for the current SDK installation. */
data class ApiHealthTelemetryStats(
    val capturedEvents: Long = 0,
    val sampledOutEvents: Long = 0,
    val droppedEvents: Long = 0,
    val deliveredEvents: Long = 0,
    val deliveryBatches: Long = 0,
    val payloadBytesSent: Long = 0,
    val payloadBytesSavedByCompression: Long = 0,
    val pendingEvents: Int = 0,
) {
    internal operator fun plus(other: ApiHealthTelemetryStats) = ApiHealthTelemetryStats(
        capturedEvents = capturedEvents + other.capturedEvents,
        sampledOutEvents = sampledOutEvents + other.sampledOutEvents,
        droppedEvents = droppedEvents + other.droppedEvents,
        deliveredEvents = deliveredEvents + other.deliveredEvents,
        deliveryBatches = deliveryBatches + other.deliveryBatches,
        payloadBytesSent = payloadBytesSent + other.payloadBytesSent,
        payloadBytesSavedByCompression =
            payloadBytesSavedByCompression + other.payloadBytesSavedByCompression,
        pendingEvents = pendingEvents + other.pendingEvents,
    )
}
