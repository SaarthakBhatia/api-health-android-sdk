package com.apihealth.sdk

import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.math.BigDecimal
import java.nio.file.Files
import java.util.UUID
import javax.net.ssl.SSLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test

class ApiHealthTest {

    @Test
    fun `public SDK version matches the Maven release`() {
        assertEquals("0.7.1", ApiHealth.SDK_VERSION)
    }

    @Test
    fun `install adds one interceptor to the Retrofit OkHttp builder`() {
        val builder = OkHttpClient.Builder()

        ApiHealth.install(builder, testConfig())

        assertEquals(1, builder.interceptors().size)
    }

    @Test
    fun `install is idempotent for the same OkHttp builder`() {
        val builder = OkHttpClient.Builder()
        ApiHealth.install(builder, testConfig())
        ApiHealth.install(builder, testConfig())
        assertEquals(1, builder.interceptors().size)
    }

    @Test
    fun `install preserves the application event listener`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = "response"))
            server.enqueue(MockResponse(code = 200, body = "{\"accepted\":1}"))
            val starts = AtomicInteger()
            val delivered = CountDownLatch(1)
            val builder = OkHttpClient.Builder().eventListenerFactory {
                object : EventListener() {
                    override fun callStart(call: Call) {
                        starts.incrementAndGet()
                    }
                }
            }
            ApiHealth.install(
                builder,
                testConfig().copy(
                    endpointUrl = server.url("/api/v1/events").toString(),
                    appId = "listener-${UUID.randomUUID()}",
                    captureSuccessfulResponses = true,
                    batchSize = 1,
                    gzipBatches = false,
                    deliveryListener = { report ->
                        if (report.status == ApiHealthDeliveryStatus.DELIVERED) delivered.countDown()
                    },
                ),
            )

            builder.build().newCall(Request.Builder().url(server.url("/health")).build()).execute().use {
                assertEquals("response", it.body.string())
            }

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
            assertEquals("/health", server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
            val telemetry = server.takeRequest(5, TimeUnit.SECONDS)?.body?.utf8().orEmpty()
            assertTrue("\"callId\":" in telemetry)
            assertTrue("\"attemptNumber\":" in telemetry)
            assertTrue("\"requestWriteMs\":" in telemetry)
            assertTrue("\"ttfbMs\":" in telemetry)
            assertTrue("\"responseReadMs\":" in telemetry)
            assertTrue("\"reusedConnection\":false" in telemetry)
        }
    }

    @Test
    fun `sensitive headers and query values are redacted by default`() {
        val request = Request.Builder()
            .url("https://service.example/profile?token=secret&view=full")
            .header("Authorization", "Bearer secret")
            .header("Accept", "application/json")
            .build()

        val headers = EventFactory.sanitizeHeaders(
            request.headers,
            ApiHealthConfig.DEFAULT_REDACTED_HEADERS,
        )
        val url = EventFactory.sanitizeUrl(request, captureQueryValues = false)

        assertEquals(listOf("[REDACTED]"), headers["Authorization"])
        assertEquals(listOf("application/json"), headers["Accept"])
        assertFalse("secret" in url)
        assertFalse("full" in url)
    }

    @Test
    fun `successful HTTP responses produce healthy telemetry`() {
        val request = Request.Builder().url("https://service.example/profile").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(204)
            .message("No Content")
            .build()

        val event = EventFactory.createHttpResponse(
            request = request,
            response = response,
            durationMs = 81,
            occurredAt = "2026-08-19T12:00:00.000Z",
            config = testConfig(),
        )

        assertEquals("HTTP_SUCCESS", event.eventType)
        assertEquals(204, event.statusCode)
        assertNull(event.failureType)
        assertTrue("\"eventType\":\"HTTP_SUCCESS\"" in event.toJson())
    }

    @Test
    fun `device manufacturer is included with the model`() {
        val request = Request.Builder().url("https://service.example/profile").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

        val event = EventFactory.createHttpResponse(
            request = request,
            response = response,
            durationMs = 25,
            occurredAt = "2026-08-26T12:00:00.000Z",
            config = testConfig().copy(deviceManufacturer = "OPPO", deviceModel = "CPH2599"),
        )

        assertTrue("\"deviceManufacturer\":\"OPPO\"" in event.toJson())
        assertTrue("\"deviceModel\":\"CPH2599\"" in event.toJson())
    }

    @Test
    fun `shared provider and request context are merged into telemetry`() {
        ApiHealth.setContext(
            ApiHealthEventContext(
                anonymousUserId = "user-hash-42",
                sessionId = "session-7",
                networkType = "MOBILE",
                journeyName = "Checkout",
                journeyStep = "Payment",
                journeySequence = 3,
            ),
        )
        try {
            val requestBuilder = Request.Builder().url("https://service.example/checkout")
            ApiHealth.tag(
                requestBuilder,
                ApiHealthEventContext(
                    correlationId = "order-123",
                    callId = "checkout-attempts-123",
                    attemptNumber = 2,
                    businessValue = BigDecimal("499.00"),
                    businessCurrency = "INR",
                ),
            )
            val request = requestBuilder.build()
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .build()

            val event = EventFactory.createHttpResponse(
                request = request,
                response = response,
                durationMs = 901,
                occurredAt = "2026-08-21T12:00:00.000Z",
                config = testConfig().copy(
                    initialContext = ApiHealthEventContext(countryCode = "IN"),
                    contextProvider = { ApiHealthEventContext(carrier = "Airtel") },
                ),
            )

            val json = event.toJson()
            assertTrue("\"anonymousUserId\":\"user-hash-42\"" in json)
            assertTrue("\"sessionId\":\"session-7\"" in json)
            assertTrue("\"countryCode\":\"IN\"" in json)
            assertTrue("\"carrier\":\"Airtel\"" in json)
            assertTrue("\"journeyName\":\"Checkout\"" in json)
            assertTrue("\"correlationId\":\"order-123\"" in json)
            assertTrue("\"callId\":\"checkout-attempts-123\"" in json)
            assertTrue("\"attemptNumber\":2" in json)
            assertTrue("\"businessValue\":499.00" in json)
            assertTrue("\"businessCurrency\":\"INR\"" in json)
        } finally {
            ApiHealth.clearContext()
        }
    }

    @Test
    fun `automatic anonymous session is attached without application context`() {
        val request = Request.Builder().url("https://service.example/bootstrap").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Unavailable")
            .build()

        val event = EventFactory.createHttpResponse(
            request = request,
            response = response,
            durationMs = 51,
            occurredAt = "2026-08-25T12:00:00.000Z",
            config = testConfig(),
        )

        assertTrue(event.sessionId?.isNotBlank() == true)
        assertTrue("\"sessionId\":\"${event.sessionId}\"" in event.toJson())
    }

    @Test
    fun `explicit session overrides automatic session`() {
        val request = Request.Builder().url("https://service.example/profile").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("Failure")
            .build()

        val event = EventFactory.createHttpResponse(
            request = request,
            response = response,
            durationMs = 51,
            occurredAt = "2026-08-25T12:00:00.000Z",
            config = testConfig().copy(initialContext = ApiHealthEventContext(sessionId = "analytics-session")),
        )

        assertEquals("analytics-session", event.sessionId)
    }

    @Test
    fun `automatic sessions rotate after inactivity`() {
        var now = 1_000L
        var nextId = 0
        val tracker = AutomaticSessionTracker(clock = { now }, idFactory = { "session-${++nextId}" })

        assertEquals("session-1", tracker.currentId(60_000))
        now += 59_999
        assertEquals("session-1", tracker.currentId(60_000))
        now += 60_000
        assertEquals("session-2", tracker.currentId(60_000))
        assertEquals("session-3", tracker.rotate())
    }

    @Test
    fun `network exceptions are classified for dashboard sections`() {
        assertEquals("TIMEOUT", EventFactory.classifyFailure(SocketTimeoutException("timeout")))
        assertEquals("DNS", EventFactory.classifyFailure(UnknownHostException("host")))
        assertEquals("SSL", EventFactory.classifyFailure(SSLException("certificate")))
        assertEquals("CONNECTION", EventFactory.classifyFailure(ConnectException("refused")))
        assertEquals("CONNECTION", EventFactory.classifyFailure(SocketException("connection reset")))
        assertEquals("CANCELLED", EventFactory.classifyFailure(java.io.IOException("Canceled")))
        assertEquals("IO", EventFactory.classifyFailure(java.io.IOException("stream reset")))
    }

    @Test
    fun `success sampling rate must be a percentage`() {
        assertFailsWith<IllegalArgumentException> {
            testConfig().copy(successSampleRate = 1.1)
        }
    }

    @Test
    fun `adaptive sampling is opt in so full capture remains exact`() {
        assertFalse(testConfig().adaptiveSampling)
    }

    @Test
    fun `reporter batches events and exposes delivery diagnostics`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = "{\"accepted\":2}"))
            val delivered = CountDownLatch(1)
            val reporter = EventReporter(
                testConfig().copy(
                    endpointUrl = server.url("/api/v1/events").toString(),
                    batchSize = 2,
                    flushIntervalMs = 30_000,
                    gzipBatches = false,
                    deliveryListener = { report ->
                        if (report.status == ApiHealthDeliveryStatus.DELIVERED) delivered.countDown()
                    },
                ),
            )
            reporter.enqueue(testEvent(200))
            reporter.enqueue(testEvent(201))

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/v1/events/batch", request?.url?.encodedPath)
            val body = request?.body?.utf8().orEmpty()
            assertTrue(body.startsWith("{\"batchId\":"))
            assertTrue("\"sentAt\":" in body)
            assertTrue("\"droppedSinceLastBatch\":0" in body)
            assertTrue("\"events\":[" in body)
            assertEquals(2, "\"eventId\"".toRegex().findAll(body).count())
            assertEquals(0, reporter.pendingEventCount())
        }
    }

    @Test
    fun `reporter gzips batches and reports wire cost`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = "{\"accepted\":2}"))
            val delivered = CountDownLatch(1)
            var deliveryReport: ApiHealthDeliveryReport? = null
            val reporter = EventReporter(
                testConfig().copy(
                    endpointUrl = server.url("/api/v1/events").toString(),
                    batchSize = 2,
                    flushIntervalMs = 30_000,
                    gzipBatches = true,
                    gzipMinimumBytes = 0,
                    deliveryListener = { report ->
                        if (report.status == ApiHealthDeliveryStatus.DELIVERED) {
                            deliveryReport = report
                            delivered.countDown()
                        }
                    },
                ),
            )
            reporter.enqueue(testEvent(200))
            reporter.enqueue(testEvent(201))

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("gzip", request?.headers?.get("Content-Encoding"))
            val body = GZIPInputStream(ByteArrayInputStream(request!!.body!!.toByteArray()))
                .bufferedReader()
                .use { it.readText() }
            assertEquals(2, "\"eventId\"".toRegex().findAll(body).count())
            val report = checkNotNull(deliveryReport)
            assertTrue(report.payloadBytes < report.uncompressedPayloadBytes)
            assertEquals(1, reporter.telemetryStats().deliveryBatches)
            assertTrue(reporter.telemetryStats().payloadBytesSavedByCompression > 0)
        }
    }

    @Test
    fun `deep network timing phases serialize with a stable call id`() {
        var now = 0L
        val state = CallTimingState(callId = "logical-call", clock = { now })
        state.dnsStart()
        now += TimeUnit.MILLISECONDS.toNanos(5)
        state.dnsEnd()
        val address = InetSocketAddress("127.0.0.1", 443)
        state.connectStart(address)
        now += TimeUnit.MILLISECONDS.toNanos(10)
        state.tlsStart()
        now += TimeUnit.MILLISECONDS.toNanos(3)
        state.tlsEnd()
        now += TimeUnit.MILLISECONDS.toNanos(7)
        state.connectEnd(address)
        state.connectionAcquired()
        state.requestStart()
        now += TimeUnit.MILLISECONDS.toNanos(2)
        state.requestHeadersEnd(hasBody = false)
        now += TimeUnit.MILLISECONDS.toNanos(11)
        state.responseHeadersStart()
        state.responseBodyStart()
        now += TimeUnit.MILLISECONDS.toNanos(9)
        state.responseBodyEnd()

        val json = testEvent(200).copy(sampleRate = 0.25, timingState = state).toJson()

        assertTrue("\"callId\":\"logical-call\"" in json)
        assertTrue("\"attemptNumber\":1" in json)
        assertTrue("\"sampleRate\":0.25" in json)
        assertTrue("\"dnsMs\":5" in json)
        assertTrue("\"connectMs\":17" in json)
        assertTrue("\"tlsMs\":3" in json)
        assertTrue("\"requestWriteMs\":2" in json)
        assertTrue("\"ttfbMs\":11" in json)
        assertTrue("\"responseReadMs\":9" in json)
        assertTrue("\"reusedConnection\":false" in json)
    }

    @Test
    fun `event waits for call completion and is enqueued exactly once`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = "{\"accepted\":1}"))
            val delivered = CountDownLatch(1)
            val reporter = EventReporter(
                testConfig().copy(
                    endpointUrl = server.url("/api/v1/events").toString(),
                    batchSize = 1,
                    flushIntervalMs = 30_000,
                    responseTimingTimeoutMs = 30_000,
                    gzipBatches = false,
                    deliveryListener = { report ->
                        if (report.status == ApiHealthDeliveryStatus.DELIVERED) delivered.countDown()
                    },
                ),
            )
            var now = 0L
            val state = CallTimingState(callId = "completed-call", clock = { now })
            state.responseBodyStart()
            reporter.enqueueWhenCallCompletes(testEvent(200).copy(timingState = state), state)
            assertEquals(0, reporter.telemetryStats().capturedEvents)

            now += TimeUnit.MILLISECONDS.toNanos(14)
            state.callEnd()
            state.callEnd()

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            val body = server.takeRequest(5, TimeUnit.SECONDS)?.body?.utf8().orEmpty()
            assertTrue("\"responseReadMs\":14" in body)
            assertEquals(1, reporter.telemetryStats().capturedEvents)
        }
    }

    @Test
    fun `response timing timeout still delivers an unclosed call once`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = "{\"accepted\":1}"))
            val delivered = CountDownLatch(1)
            val reporter = EventReporter(
                testConfig().copy(
                    endpointUrl = server.url("/api/v1/events").toString(),
                    batchSize = 1,
                    flushIntervalMs = 30_000,
                    responseTimingTimeoutMs = 250,
                    gzipBatches = false,
                    deliveryListener = { report ->
                        if (report.status == ApiHealthDeliveryStatus.DELIVERED) delivered.countDown()
                    },
                ),
            )
            val state = CallTimingState(callId = "unclosed-call")
            reporter.enqueueWhenCallCompletes(testEvent(200).copy(timingState = state), state)

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            state.callEnd()
            Thread.sleep(100)

            assertEquals(1, reporter.telemetryStats().capturedEvents)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `offline spool restores newest events within its byte bound`() {
        val directory = Files.createTempDirectory("api-health-spool").toFile()
        try {
            val queued = (200..204).map { status -> QueuedTelemetryEvent.live(testEvent(status)) }
            val eventBytes = queued.first().toJson().toByteArray().size.toLong() + 1
            val maxBytes = eventBytes * 2
            val spool = OfflineEventSpool(directory, "spool-test", maxBytes)

            spool.replace(queued)
            val restored = spool.restore()

            assertTrue(directory.listFiles().orEmpty().single().length() <= maxBytes)
            assertEquals(2, restored.size)
            assertEquals(queued.takeLast(2).map { it.eventId }, restored.map { it.eventId })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `adaptive sampling protects the bounded queue while preserving a floor`() {
        val reporter = EventReporter(
            testConfig().copy(
                maxQueuedEvents = 100,
                batchSize = 100,
                flushIntervalMs = 30_000,
                successSampleRate = 1.0,
                minimumSuccessSampleRate = 0.05,
                adaptiveSampling = true,
            ),
        )
        repeat(60) { reporter.enqueue(testEvent(200)) }

        assertEquals(0.5, reporter.effectiveSuccessSampleRate())
        assertEquals(60, reporter.telemetryStats().capturedEvents)
    }

    private fun testEvent(statusCode: Int) = TelemetryEvent(
        appId = "test-app",
        environment = "test",
        occurredAt = "2026-08-20T00:00:00.000Z",
        eventType = "HTTP_SUCCESS",
        httpMethod = "GET",
        url = "https://service.example/health",
        statusCode = statusCode,
        durationMs = 25,
        requestHeaders = emptyMap(),
        requestBody = null,
        responseHeaders = emptyMap(),
        responseBody = null,
        appVersion = "1",
        deviceModel = "test",
        deviceManufacturer = "Test Devices",
        osVersion = "test",
        sdkVersion = ApiHealth.SDK_VERSION,
        requestBodyTruncated = false,
        responseBodyTruncated = false,
    )

    private fun testConfig() = ApiHealthConfig(
        endpointUrl = "http://localhost:8080/api/v1/events",
        apiKey = "test-key",
        appId = "test-app",
        environment = "test",
    )
}
