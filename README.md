# API Health Android SDK

`com.apihealth:api-health-android` is a small Kotlin/JVM library that attaches one interceptor to the OkHttp client used by Retrofit. It reports HTTP errors, transport failures, and optionally sampled successful responses asynchronously while preserving the original response or exception.

## Requirements

- Android API 21 or newer
- Java 11-compatible Android build
- Retrofit with OkHttp
- A monitored application and environment created in the API Health dashboard

## Install with JitPack

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the SDK to the application module:

```kotlin
dependencies {
    implementation("com.github.SaarthakBhatia:api-health-android-sdk:0.7.1")
}
```

## Install into Maven Local for development

From this repository:

```powershell
.\mvnw.cmd install
```

Add `mavenLocal()` to the Android repositories and use `com.apihealth:api-health-android:0.7.1` while testing unpublished changes.

## Retrofit integration

Keep the ingestion key outside source control and inject it into `BuildConfig` from a CI secret or the user-level Gradle properties.

```kotlin
import android.os.Build
import com.apihealth.sdk.ApiHealth
import com.apihealth.sdk.ApiHealthConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit

val okHttpBuilder = OkHttpClient.Builder()

ApiHealth.install(
    okHttpBuilder = okHttpBuilder,
    config = ApiHealthConfig(
        endpointUrl = "https://pulsegrid-api-59567290741.us-central1.run.app/api/v1/events",
        apiKey = BuildConfig.API_HEALTH_KEY,
        appId = "consumer-android",
        environment = "production",
        appVersion = BuildConfig.VERSION_NAME,
        deviceModel = Build.MODEL,
        deviceManufacturer = Build.MANUFACTURER,
        osVersion = Build.VERSION.RELEASE,
        captureNetworkFailures = true,
        captureSuccessfulResponses = true,
        successSampleRate = 1.0, // Record every 2xx/3xx response.
        adaptiveSampling = false, // Keep every configured healthy sample in this integration.
        captureSuccessfulBodies = false, // Keep healthy traffic lightweight.
        captureRequestBody = false,
        captureResponseBody = false,
        gzipBatches = true,
        deliveryListener = { report ->
            Log.d("ApiHealth", "${report.status}: ${report.eventCount}; pending=${report.pendingEventCount}")
        },
    ),
)

val retrofit = Retrofit.Builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .client(okHttpBuilder.build())
    .build()
```

HTTP `4xx`/`5xx` responses and timeout, DNS, SSL, connection, cancellation, and other I/O failures are reported by default. Successful `2xx`/`3xx` reporting is opt-in. Use `successSampleRate = 1.0` when every call must appear, or lower it deliberately to control high-volume storage.

Version 0.7.1 captures the configured device manufacturer alongside its model and automatically gives every captured event a random opaque session ID. The ID rotates after 30 minutes without captured activity, or immediately when `ApiHealth.startNewSession()` is called. This enables affected-session counts and captured request timelines without a login, user ID, manual journey calls, or another network request. Automatically inferred request sequences are not presented as user funnels; named chronological step-reach and drop-off metrics require explicit journey instrumentation. Set `automaticSessionTracking = false` only if your application supplies its own random, non-personal session ID.

The SDK batches up to 20 events into one request, gzip-compresses worthwhile payloads, retries transient `408`, `429`, and `5xx` failures with bounded exponential backoff, and assigns every event an idempotency ID so a retry cannot create a duplicate. A shared reporter services multiple monitored OkHttp clients without creating a delivery thread per client. The default queue holds 2,000 events and flushes every 1.5 seconds. Errors are prioritized over sampled successes if that queue fills. `maxBatchBytes` prevents a group of large captured bodies from creating an oversized upload (a single large event is still sent alone).

Adaptive sampling is opt-in so `successSampleRate = 1.0` continues to mean every successful API call. For a cost-saving profile, use `successSampleRate = 0.10` and `adaptiveSampling = true`; healthy sampling then decreases further only when the queue is under pressure or delivery is offline. HTTP errors, network failures, and responses slower than `slowResponseThresholdMs` remain at 100% capture. Each success carries its effective `sampleRate`, allowing backend rates to be weighted honestly.

`deliveryListener`, `ApiHealth.pendingEventCount()`, `ApiHealth.flush()`, and `ApiHealth.telemetryStats()` make delivery state and overhead observable. Stats include captured, sampled-out, dropped, and delivered events plus attempted batch count, transmitted body bytes, and bytes saved by gzip. `ApiHealthDeliveryReport` also exposes compressed and uncompressed batch sizes for successful deliveries.

### Optional process-restart offline spool

The default queue is memory-only. To preserve queued telemetry when the app process is restarted, opt into a bounded disk spool in an **app-private, no-backup directory**:

```kotlin
ApiHealthConfig(
    // ...required fields...
    offlineStorageDirectory = File(context.noBackupFilesDir, "api-health"),
    maxOfflineStorageBytes = 5L * 1024 * 1024,
)
```

Spool reads and writes run on the SDK delivery thread and never block API calls. Snapshots use a temporary-file rename strategy compatible with Android API 21. The spool is disabled by default because captured headers or bodies may contain sensitive content.

### Deep network timings

Version 0.7.1 composes with an OkHttp `EventListener` configured before `ApiHealth.install(...)` and records DNS, TCP connection, TLS, request-write, time-to-first-byte, and response-read durations. It also reports whether OkHttp reused a pooled connection, a stable call ID, and the number of connection attempts. Missing means a phase did not run; zero is a valid sub-millisecond measurement.

`durationMs` remains request start through response headers for HTTP responses. The SDK waits for OkHttp call completion so `responseReadMs` reflects body consumption. `responseTimingTimeoutMs` defaults to five seconds and provides an exactly-once fallback for streaming or unclosed bodies; fallback events omit an unfinished response-read value rather than blocking delivery.

Configure the application's own `eventListener`/`eventListenerFactory` before calling `ApiHealth.install`. Assigning a different factory afterward replaces all previously configured listeners, including monitoring.

Install the interceptor on **every OkHttp builder that performs production API traffic**, including separate authentication, bootstrap, upload, or download clients. A Retrofit service using an uninstrumented client cannot be observed.

## Product intelligence context

Release, incident, affected-session, and observed-flow intelligence work automatically. User identity is optional: add a hashed internal ID only when available to correlate the same user across sessions. Client context improves device and network diagnostics:

```kotlin
ApiHealth.setContext(
    ApiHealthEventContext(
        anonymousUserId = sha256(internalUserId),
        networkType = "CELLULAR", // or WIFI
        carrier = "Airtel",
        countryCode = "IN",
    ),
)
```

If your application already owns an analytics session ID, you can supply it as `sessionId`; it overrides the automatic SDK session. Call `ApiHealth.startNewSession()` on an explicit logout/account switch if you want to end the current anonymous session immediately.

Named business journeys are optional. Add these calls only for flows where product semantics matter:

```kotlin
ApiHealth.startJourney("Subscription", firstStep = "Login")
ApiHealth.updateJourneyStep("Plan selection", sequence = 1)
ApiHealth.updateJourneyStep("Payment", sequence = 2)
ApiHealth.endJourney()
```

Attach business value and a stable transaction/correlation ID only to the relevant request. The tag stays in OkHttp memory and is not sent to your API server:

```kotlin
val request = ApiHealth.tag(
    requestBuilder,
    ApiHealthEventContext(
        correlationId = orderId,
        businessValue = BigDecimal("499.00"),
        businessCurrency = "INR",
    ),
).build()
```

Repeated failures sharing a `correlationId` are counted as one affected business transaction, preventing retry attempts from multiplying the revenue estimate. `contextProvider` on `ApiHealthConfig` can supply fresh values for every completed call when network or session state changes frequently.

For application-layer retries, preserve a logical call ID and increment the attempt number on each new OkHttp request. Without this tag, the SDK still creates one automatic `callId` per OkHttp `Call` and counts its internal connection attempts:

```kotlin
val logicalCallId = UUID.randomUUID().toString()

fun requestForAttempt(attempt: Int): Request = ApiHealth.tag(
    originalRequest.newBuilder(),
    ApiHealthEventContext(callId = logicalCallId, attemptNumber = attempt),
).build()
```

## Privacy defaults

- `Authorization`, cookies, proxy authorization, and `X-Api-Key` headers are redacted.
- URL query values are redacted while parameter names are retained.
- Request and response body capture is disabled by default.
- Body capture is textual-only, size-limited, and must be explicitly enabled.
- The ingestion key is environment-scoped, write-only, and revocable. Like every secret embedded in an APK, it can ultimately be extracted, so it grants no dashboard access.

Review payloads for application-specific personal or regulated data before enabling body capture.

## Release process

The Maven version in `pom.xml` and `ApiHealth.SDK_VERSION` must match. To publish a new JitPack release:

1. Update both versions and the SCM tag in `pom.xml`.
2. Run `.\mvnw.cmd verify` from the repository root.
3. Commit and push the changes.
4. Push a matching tag such as `0.7.1`.
5. Open `https://jitpack.io/#SaarthakBhatia/api-health-android-sdk/0.7.1` to trigger and verify the build.

GitHub Actions tests every push and pull request. JitPack publishes the tagged build on demand.
