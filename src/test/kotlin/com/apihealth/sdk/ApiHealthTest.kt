package com.apihealth.sdk

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.math.BigDecimal
import javax.net.ssl.SSLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test

class ApiHealthTest {

    @Test
    fun `public SDK version matches the Maven release`() {
        assertEquals("0.5.0", ApiHealth.SDK_VERSION)
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
            assertTrue(body.startsWith("{\"events\":["))
            assertEquals(2, "\"eventId\"".toRegex().findAll(body).count())
            assertEquals(0, reporter.pendingEventCount())
        }
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
