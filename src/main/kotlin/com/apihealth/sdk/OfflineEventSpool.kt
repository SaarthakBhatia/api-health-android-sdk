package com.apihealth.sdk

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class QueuedTelemetryEvent(
    val eventId: String,
    val eventType: String,
    private val payloadProvider: () -> String,
) {
    fun toJson(): String = payloadProvider()

    val isCritical: Boolean
        get() = eventType != "HTTP_SUCCESS"

    companion object {
        fun live(event: TelemetryEvent) = QueuedTelemetryEvent(
            eventId = event.eventId,
            eventType = event.eventType,
            payloadProvider = event::toJson,
        )

        fun restored(json: String): QueuedTelemetryEvent? = runCatching {
            val value = Json.parseToJsonElement(json).jsonObject
            val eventId = value.getValue("eventId").jsonPrimitive.content
            val eventType = value.getValue("eventType").jsonPrimitive.content
            QueuedTelemetryEvent(eventId, eventType) { json }
        }.getOrNull()
    }
}

/** Best-effort, bounded process-restart storage. All I/O is performed on the reporter executor. */
internal class OfflineEventSpool(
    directory: File,
    identity: String,
    private val maxBytes: Long,
) {
    private val target = File(directory, "api-health-${identity.sha256Prefix()}.jsonl")
    private val temporary = File(directory, "${target.name}.tmp")
    private val backup = File(directory, "${target.name}.bak")

    fun restore(): List<QueuedTelemetryEvent> {
        val source = when {
            target.isFile -> target
            backup.isFile -> backup
            else -> return emptyList()
        }
        if (source.length() > maxBytes) return emptyList()
        return runCatching {
            source.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.filter(String::isNotBlank)
                    .mapNotNull(QueuedTelemetryEvent::restored)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun replace(events: List<QueuedTelemetryEvent>) {
        runCatching {
            target.parentFile?.mkdirs()
            val selected = selectNewestWithinLimit(events)
            FileOutputStream(temporary, false).use { output ->
                selected.forEach { payload ->
                    output.write(payload)
                    output.write('\n'.code)
                }
                output.fd.sync()
            }
            if (backup.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                temporary.delete()
                return@runCatching
            }
            if (temporary.renameTo(target)) {
                backup.delete()
            } else {
                if (!target.exists()) backup.renameTo(target)
                temporary.delete()
            }
        }
    }

    private fun selectNewestWithinLimit(events: List<QueuedTelemetryEvent>): List<ByteArray> {
        val selected = mutableListOf<Pair<Int, ByteArray>>()
        var bytes = 0L
        val byDurabilityPriority = events.withIndex().sortedWith(
            compareByDescending<IndexedValue<QueuedTelemetryEvent>> { it.value.isCritical }
                .thenByDescending { it.index },
        )
        byDurabilityPriority.forEach { indexed ->
            val event = indexed.value
            val payload = event.toJson().toByteArray(StandardCharsets.UTF_8)
            val storedBytes = payload.size.toLong() + 1
            if (storedBytes <= maxBytes - bytes) {
                selected += indexed.index to payload
                bytes += storedBytes
            }
        }
        return selected.sortedBy { it.first }.map { it.second }
    }

    private fun String.sha256Prefix(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .take(10)
        .joinToString("") { byte -> "%02x".format(byte) }
}
