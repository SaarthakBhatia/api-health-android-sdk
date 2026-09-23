package com.apihealth.sdk

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Collects OkHttp lifecycle timings without changing request or response streams. A state object is
 * retained by its telemetry event, so response-body timing can continue to update until delivery.
 */
internal class NetworkTimingCollector(
    private val delegateFactory: EventListener.Factory,
    private val clock: () -> Long = System::nanoTime,
) : EventListener.Factory {

    private val states = ConcurrentHashMap<Call, CallTimingState>()

    override fun create(call: Call): EventListener {
        val state = CallTimingState(clock = clock)
        states[call] = state
        val timingListener = TimingEventListener(
            state = state,
            onFinished = { states.remove(call) },
        )
        val delegate = try {
            delegateFactory.create(call)
        } catch (exception: Exception) {
            states.remove(call)
            throw exception
        }
        // Record and complete our state even if an application listener later throws.
        return timingListener.plus(delegate)
    }

    fun timingFor(call: Call): CallTimingState = states[call] ?: CallTimingState(clock = clock).let {
        states.putIfAbsent(call, it) ?: it
    }

    private class TimingEventListener(
        private val state: CallTimingState,
        private val onFinished: () -> Unit,
    ) : EventListener() {

        override fun dnsStart(call: Call, domainName: String) = state.dnsStart()

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
            state.dnsEnd()

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
            state.connectStart(inetSocketAddress)

        override fun secureConnectStart(call: Call) = state.tlsStart()

        override fun secureConnectEnd(call: Call, handshake: Handshake?) = state.tlsEnd()

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            state.connectEnd(inetSocketAddress)
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            state.connectEnd(inetSocketAddress)
        }

        override fun connectionAcquired(call: Call, connection: Connection) = state.connectionAcquired()

        override fun requestHeadersStart(call: Call) = state.requestStart()

        override fun requestHeadersEnd(call: Call, request: Request) = state.requestHeadersEnd(request.body != null)

        override fun requestBodyEnd(call: Call, byteCount: Long) = state.requestEnd()

        override fun requestFailed(call: Call, ioe: IOException) = state.requestEnd()

        override fun responseHeadersStart(call: Call) = state.responseHeadersStart()

        override fun responseBodyStart(call: Call) = state.responseBodyStart()

        override fun responseBodyEnd(call: Call, byteCount: Long) = state.responseBodyEnd()

        override fun responseFailed(call: Call, ioe: IOException) = state.responseBodyEnd()

        override fun callEnd(call: Call) {
            state.callEnd()
            onFinished()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            state.callEnd()
            onFinished()
        }
    }
}

internal data class NetworkTimingSnapshot(
    val callId: String,
    val attemptNumber: Int,
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val requestWriteMs: Long?,
    val ttfbMs: Long?,
    val responseReadMs: Long?,
    val reusedConnection: Boolean?,
)

internal class CallTimingState(
    val callId: String = UUID.randomUUID().toString(),
    private val clock: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var dnsStartedAt: Long? = null
    private var dnsNanos = 0L
    private var dnsObserved = false
    private val connectStartedAt = mutableMapOf<InetSocketAddress, ArrayDeque<Long>>()
    private var connectNanos = 0L
    private var connectObserved = false
    private var connectAttempts = 0
    private var tlsStartedAt: Long? = null
    private var tlsNanos = 0L
    private var tlsObserved = false
    private var requestStartedAt: Long? = null
    private var requestNanos = 0L
    private var requestObserved = false
    private var requestAttempts = 0
    private var requestEndedAt: Long? = null
    private var ttfbNanos = 0L
    private var ttfbObserved = false
    private var responseStartedAt: Long? = null
    private var responseNanos = 0L
    private var responseObserved = false
    private var connectionReused: Boolean? = null
    private var completed = false
    private val completionListeners = mutableListOf<() -> Unit>()

    fun dnsStart() = synchronized(lock) {
        dnsStartedAt = clock()
    }

    fun dnsEnd() = synchronized(lock) {
        dnsStartedAt?.let { started ->
            dnsNanos += elapsedSince(started)
            dnsObserved = true
        }
        dnsStartedAt = null
    }

    fun connectStart(address: InetSocketAddress) = synchronized(lock) {
        connectAttempts += 1
        connectStartedAt.getOrPut(address, ::ArrayDeque).addLast(clock())
    }

    fun connectEnd(address: InetSocketAddress) = synchronized(lock) {
        val starts = connectStartedAt[address]
        val started = starts?.pollFirst()
        if (starts?.isEmpty() == true) connectStartedAt.remove(address)
        started?.let {
            connectNanos += elapsedSince(it)
            connectObserved = true
        }
    }

    fun tlsStart() = synchronized(lock) {
        tlsStartedAt = clock()
    }

    fun tlsEnd() = synchronized(lock) {
        tlsStartedAt?.let { started ->
            tlsNanos += elapsedSince(started)
            tlsObserved = true
        }
        tlsStartedAt = null
    }

    fun connectionAcquired() = synchronized(lock) {
        if (connectionReused == null) connectionReused = connectAttempts == 0
    }

    fun requestStart() = synchronized(lock) {
        requestAttempts += 1
        if (requestStartedAt == null) requestStartedAt = clock()
    }

    fun requestHeadersEnd(hasBody: Boolean) = synchronized(lock) {
        if (!hasBody) finishRequest()
    }

    fun requestEnd() = synchronized(lock) {
        finishRequest()
    }

    fun responseHeadersStart() = synchronized(lock) {
        val now = clock()
        requestEndedAt?.let { ended ->
            ttfbNanos += (now - ended).coerceAtLeast(0)
            ttfbObserved = true
        }
        requestEndedAt = null
    }

    fun responseBodyStart() = synchronized(lock) {
        if (responseStartedAt == null) responseStartedAt = clock()
    }

    fun responseBodyEnd() = synchronized(lock) {
        finishResponse()
    }

    fun callEnd() {
        val listeners = synchronized(lock) {
            finishRequest()
            finishResponse()
            if (completed) {
                emptyList()
            } else {
                completed = true
                val pending = completionListeners.toList()
                completionListeners.clear()
                pending
            }
        }
        listeners.forEach { listener -> runCatching(listener) }
    }

    fun invokeOnCompletion(listener: () -> Unit) {
        val invokeNow = synchronized(lock) {
            if (completed) {
                true
            } else {
                completionListeners += listener
                false
            }
        }
        if (invokeNow) runCatching(listener)
    }

    fun snapshot(): NetworkTimingSnapshot = synchronized(lock) {
        val connectWithoutTls = if (connectObserved) {
            (connectNanos - tlsNanos).coerceAtLeast(0)
        } else {
            0
        }
        NetworkTimingSnapshot(
            callId = callId,
            attemptNumber = maxOf(connectAttempts, requestAttempts).coerceIn(1, 1_000),
            dnsMs = dnsNanos.toMillisIf(dnsObserved),
            connectMs = connectWithoutTls.toMillisIf(connectObserved),
            tlsMs = tlsNanos.toMillisIf(tlsObserved),
            requestWriteMs = requestNanos.toMillisIf(requestObserved),
            ttfbMs = ttfbNanos.toMillisIf(ttfbObserved),
            responseReadMs = responseNanos.toMillisIf(responseObserved),
            reusedConnection = connectionReused,
        )
    }

    private fun finishRequest() {
        requestStartedAt?.let { started ->
            val finishedAt = clock()
            requestNanos += (finishedAt - started).coerceAtLeast(0)
            requestObserved = true
            requestEndedAt = finishedAt
        }
        requestStartedAt = null
    }

    private fun finishResponse() {
        responseStartedAt?.let { started ->
            responseNanos += (clock() - started).coerceAtLeast(0)
            responseObserved = true
        }
        responseStartedAt = null
    }

    private fun elapsedSince(startedAt: Long): Long = (clock() - startedAt).coerceAtLeast(0)

    private fun Long.toMillisIf(observed: Boolean): Long? =
        if (observed) TimeUnit.NANOSECONDS.toMillis(this) else null
}
