package com.sysadmindoc.nimbus.data.api

import com.sysadmindoc.nimbus.data.model.AlertSeverity
import com.sysadmindoc.nimbus.data.model.AlertUrgency
import com.sysadmindoc.nimbus.data.model.WeatherAlert
import com.sysadmindoc.nimbus.data.util.SourceLocaleText
import com.sysadmindoc.nimbus.util.isAlertExpired
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AlertSourceAdapter] for EUMETNET MeteoAlarm — the European weather warning aggregation
 * service covering 30+ countries.
 *
 * The feed is per-country, so we need the caller to resolve the country code first.
 * We fetch all alerts for the detected country and return them; the repository handles
 * proximity filtering if needed.
 */
@Singleton
class MeteoAlarmAdapter @Inject constructor(
    private val meteoAlarmApi: MeteoAlarmApi,
) : AlertSourceAdapter {

    override val sourceId = "meteoalarm"
    override val displayName = "MeteoAlarm (EUMETNET)"

    override val supportedRegions = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
        "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
        "NO", "PL", "PT", "RO", "RS", "SK", "SI", "ES", "SE", "CH",
        "GB", // UK uses GB in ISO 3166-1
    )

    override suspend fun getAlerts(lat: Double, lon: Double): Result<List<WeatherAlert>> {
        // Country detection is handled by AlertRepository before calling us.
        // We receive lat/lon but need the country code — derive it from the repository's
        // country detection and store it in a thread-local or pass via a companion helper.
        // For simplicity, we expose a country-specific overload used by the repository.
        return Result.success(emptyList())
    }

    /**
     * Fetch alerts for a specific country code.
     * Called by [com.sysadmindoc.nimbus.data.repository.AlertRepository] after country detection.
     */
    suspend fun getAlertsForCountry(
        countryCode: String,
        targetAreaNames: Set<String> = emptySet(),
    ): Result<List<WeatherAlert>> {
        return try {
            val feedSlug = countryCode.toMeteoAlarmFeedSlug() ?: return Result.success(emptyList())
            val response = meteoAlarmApi.getWarnings(feedSlug)
            val alerts = response.warnings.flatMap { warning ->
                if (!warning.isActive()) return@flatMap emptyList()
                val alertPayload = warning.alert
                val warningInfo = alertPayload?.info?.takeIf { it.isNotEmpty() } ?: warning.info
                SourceLocaleText.filterByLocale(warningInfo, languageTag = { it.language }).mapNotNull { info ->
                    val event = info.event ?: return@mapNotNull null
                    val areaDescriptions = info.area
                        .mapNotNull { it.areaDesc }
                    if (!info.isActiveWarning(event, areaDescriptions)) return@mapNotNull null
                    if (!areaDescriptions.isRelevantTo(targetAreaNames, countryCode)) return@mapNotNull null
                    val areaDesc = areaDescriptions.joinToString(", ").ifEmpty { "Unknown area" }
                    WeatherAlert(
                        id = alertPayload?.identifier ?: warning.identifier ?: "${sourceId}_${event}_${info.onset}",
                        event = event,
                        headline = info.headline ?: event,
                        description = info.description ?: "",
                        instruction = info.instruction,
                        severity = AlertSeverity.from(info.severity),
                        urgency = AlertUrgency.from(info.urgency),
                        certainty = info.certainty ?: "Unknown",
                        senderName = info.senderName ?: alertPayload?.sender ?: warning.sender ?: displayName,
                        areaDescription = areaDesc,
                        effective = info.onset,
                        expires = info.expires,
                        response = null,
                    )
                }
            }
            Result.success(alerts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun MeteoAlarmWarning.isActive(): Boolean {
        val payload = alert
        val capStatus = payload?.status ?: this.status
        if (capStatus != null && !capStatus.equals("Actual", ignoreCase = true)) return false

        return when ((payload?.msgType ?: msgType)?.trim()?.lowercase(Locale.ROOT)) {
            "cancel", "cancellation" -> false
            else -> true
        }
    }

    private fun MeteoAlarmInfo.isActiveWarning(
        event: String,
        areaDescriptions: List<String>,
    ): Boolean {
        if (isAlertExpired(expires)) return false
        val structuredValues = listOf(event, headline.orEmpty(), description.orEmpty(), instruction.orEmpty()) + areaDescriptions
        return structuredValues.none { value ->
            NO_WARNING_MARKERS.any { marker -> normalizeForMatch(value).contains(marker) }
        }
    }

    private fun List<String>.isRelevantTo(targetAreaNames: Set<String>, countryCode: String): Boolean {
        if (isEmpty() || targetAreaNames.isEmpty()) return true
        val normalizedTargets = targetAreaNames.map(::normalizeForMatch).filter { it.length >= 3 }.toSet()
        if (normalizedTargets.isEmpty()) return true
        val countryNames = setOf(
            normalizeForMatch(countryCode),
            normalizeForMatch(Locale.Builder().setRegion(countryCode).build().getDisplayCountry(Locale.US)),
        )
        return any { area ->
            val normalizedArea = normalizeForMatch(area)
            normalizedArea in countryNames || normalizedTargets.any { target ->
                normalizedArea == target || normalizedArea.contains(target) || target.contains(normalizedArea)
            }
        }
    }

    private fun normalizeForMatch(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun String.toMeteoAlarmFeedSlug(): String? = METEOALARM_FEED_SLUGS[uppercase()]

    private companion object {
        // Defensive fallback only. CAP status/message type and expiry are checked first.
        val NO_WARNING_MARKERS = setOf(
            "no warning", "no warnings", "geen waarschuwing", "geen waarschuwingen",
            "keine warnung", "keine warnungen", "aucune alerte", "aucun avertissement",
            "nessuna allerta", "sin avisos", "sem avisos", "ingen varning", "ingen advarsel",
        )
        val METEOALARM_FEED_SLUGS = mapOf(
            "AT" to "austria",
            "BE" to "belgium",
            "BG" to "bulgaria",
            "HR" to "croatia",
            "CY" to "cyprus",
            "CZ" to "czechia",
            "DK" to "denmark",
            "EE" to "estonia",
            "FI" to "finland",
            "FR" to "france",
            "DE" to "germany",
            "GR" to "greece",
            "HU" to "hungary",
            "IE" to "ireland",
            "IT" to "italy",
            "LV" to "latvia",
            "LT" to "lithuania",
            "LU" to "luxembourg",
            "MT" to "malta",
            "NL" to "netherlands",
            "NO" to "norway",
            "PL" to "poland",
            "PT" to "portugal",
            "RO" to "romania",
            "RS" to "serbia",
            "SK" to "slovakia",
            "SI" to "slovenia",
            "ES" to "spain",
            "SE" to "sweden",
            "CH" to "switzerland",
            "GB" to "united-kingdom",
        )
    }
}
