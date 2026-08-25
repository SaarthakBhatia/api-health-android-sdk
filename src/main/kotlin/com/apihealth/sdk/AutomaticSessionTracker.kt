package com.apihealth.sdk

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Process-local anonymous sessions with inactivity-based rotation. */
internal class AutomaticSessionTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Session(val id: String, val lastActivityAt: Long)

    private val current = AtomicReference(Session(idFactory(), clock()))

    fun currentId(timeoutMs: Long): String {
        val now = clock()
        return current.updateAndGet { session ->
            if (now - session.lastActivityAt >= timeoutMs || now < session.lastActivityAt) {
                Session(idFactory(), now)
            } else {
                session.copy(lastActivityAt = now)
            }
        }.id
    }

    fun rotate(): String {
        val next = Session(idFactory(), clock())
        current.set(next)
        return next.id
    }
}
