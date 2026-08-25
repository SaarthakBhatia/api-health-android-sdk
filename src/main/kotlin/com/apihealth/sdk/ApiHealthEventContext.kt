package com.apihealth.sdk

import java.math.BigDecimal

/** Optional product context attached to telemetry. Never use raw email addresses or phone numbers as IDs. */
data class ApiHealthEventContext(
    val anonymousUserId: String? = null,
    val sessionId: String? = null,
    val networkType: String? = null,
    val carrier: String? = null,
    val countryCode: String? = null,
    val browser: String? = null,
    val journeyName: String? = null,
    val journeyStep: String? = null,
    val journeySequence: Int? = null,
    val correlationId: String? = null,
    val businessValue: BigDecimal? = null,
    val businessCurrency: String? = null,
) {
    init {
        require(journeySequence == null || journeySequence >= 0) { "journeySequence must not be negative" }
        require(businessValue == null || businessValue.signum() >= 0) { "businessValue must not be negative" }
        require(businessCurrency == null || businessCurrency.matches(Regex("[A-Z]{3}"))) {
            "businessCurrency must be an uppercase ISO 4217 code"
        }
    }

    internal fun mergedWith(override: ApiHealthEventContext?) = if (override == null) this else copy(
        anonymousUserId = override.anonymousUserId ?: anonymousUserId,
        sessionId = override.sessionId ?: sessionId,
        networkType = override.networkType ?: networkType,
        carrier = override.carrier ?: carrier,
        countryCode = override.countryCode ?: countryCode,
        browser = override.browser ?: browser,
        journeyName = override.journeyName ?: journeyName,
        journeyStep = override.journeyStep ?: journeyStep,
        journeySequence = override.journeySequence ?: journeySequence,
        correlationId = override.correlationId ?: correlationId,
        businessValue = override.businessValue ?: businessValue,
        businessCurrency = override.businessCurrency ?: businessCurrency,
    )
}
