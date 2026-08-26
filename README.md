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
    implementation("com.github.SaarthakBhatia:api-health-android-sdk:0.6.0")
}
```

## Install into Maven Local for development

From this repository:

```powershell
.\mvnw.cmd install
```

Add `mavenLocal()` to the Android repositories and use `com.apihealth:api-health-android:0.6.0` while testing unpublished changes.

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
        endpointUrl = "https://saarthak-api-health-backend.onrender.com/api/v1/events",
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
        captureSuccessfulBodies = false, // Keep healthy traffic lightweight.
        captureRequestBody = false,
        captureResponseBody = false,
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

Version 0.6.0 captures the configured device manufacturer alongside its model and automatically gives every captured event an anonymous session ID. The ID rotates after 30 minutes without captured activity, or immediately when `ApiHealth.startNewSession()` is called. This enables affected-session counts and auto-discovered API flows without a login, user ID, or manual journey calls. Set `automaticSessionTracking = false` only if your application supplies its own session ID.

The SDK batches up to 20 events into one request, retries transient `408`, `429`, and `5xx` failures, and assigns every event an idempotency ID so a retry cannot create a duplicate. A shared reporter services multiple monitored OkHttp clients without creating a delivery thread per client. The default queue holds 2,000 events and flushes every 1.5 seconds. `deliveryListener`, `ApiHealth.pendingEventCount()`, and `ApiHealth.flush()` make delivery state observable instead of silently hiding pressure or drops.

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
4. Push a matching tag such as `0.6.0`.
5. Open `https://jitpack.io/#SaarthakBhatia/api-health-android-sdk/0.6.0` to trigger and verify the build.

GitHub Actions tests every push and pull request. JitPack publishes the tagged build on demand.
