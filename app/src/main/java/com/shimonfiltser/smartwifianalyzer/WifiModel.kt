package com.shimonfiltser.smartwifianalyzer

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
}

data class AccessPoint(
    val bssid: String,
    val ssid: String,
    val level: Int,
    val frequency: Int,
    val centerFrequency: Int,
    val widthMhz: Int,
    val channel: Int,
    val band: Band,
    val security: String,
    val standard: String,
    val connected: Boolean,
    val color: Int,
) {
    val displayName: String get() = ssid.ifEmpty { "<hidden network>" }

    /** Center of the occupied spectrum, in channel-number units of [band]. */
    val centerPos: Float get() = WifiMath.channelPos(centerFrequency, band)

    /** Half of the occupied width, in channel-number units (one channel step is 5 MHz). */
    val halfWidth: Float get() = widthMhz / 10f
}

data class Sample(val t: Long, val level: Int)

data class ChannelScore(val channel: Int, val count: Int, val interferenceDbm: Int?, val stars: Int)

object WifiMath {
    const val MIN_DBM = -100
    const val MAX_DBM = -20

    fun bandOf(freq: Int): Band? = when (freq) {
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5899 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> null
    }

    fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2400..2483 -> (freq - 2407) / 5
        freq in 4900..5899 -> (freq - 5000) / 5
        freq in 5925..7125 -> (freq - 5950) / 5
        else -> 0
    }

    fun channelPos(freq: Int, band: Band): Float = when (band) {
        Band.GHZ_2_4 -> if (freq == 2484) 14f else (freq - 2407) / 5f
        Band.GHZ_5 -> (freq - 5000) / 5f
        Band.GHZ_6 -> (freq - 5950) / 5f
    }

    /** ScanResult.CHANNEL_WIDTH_* -> MHz. */
    fun widthOf(channelWidth: Int): Int = when (channelWidth) {
        1 -> 40
        2 -> 80
        3, 4 -> 160
        5 -> 320
        else -> 20
    }

    /** ScanResult.WIFI_STANDARD_* -> marketing name. */
    fun standardOf(standard: Int, band: Band): String = when (standard) {
        1 -> "802.11a/b/g"
        4 -> "Wi-Fi 4"
        5 -> "Wi-Fi 5"
        6 -> if (band == Band.GHZ_6) "Wi-Fi 6E" else "Wi-Fi 6"
        7 -> "WiGig"
        8 -> "Wi-Fi 7"
        else -> ""
    }

    fun securityOf(caps: String): String {
        val sae = caps.contains("SAE")
        val psk = caps.contains("PSK")
        return when {
            caps.contains("EAP") -> if (caps.contains("RSN") || caps.contains("WPA2")) "WPA2/3-Enterprise" else "WPA-Enterprise"
            sae && psk -> "WPA2/WPA3"
            sae -> "WPA3"
            caps.contains("OWE") -> "OWE"
            caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            else -> "Open"
        }
    }

    /** 0..100 for -100..-30 dBm. */
    fun quality(level: Int): Int = ((level + 100) * 100 / 70).coerceIn(0, 100)

    fun levelColor(level: Int): Int = when {
        level >= -60 -> 0xFF66BB6A.toInt()
        level >= -70 -> 0xFFD4E157.toInt()
        level >= -80 -> 0xFFFFA726.toInt()
        else -> 0xFFEF5350.toInt()
    }

    /** 20 MHz channels worth rating in each band (6 GHz: preferred scanning channels only). */
    fun ratingChannels(band: Band): List<Int> = when (band) {
        Band.GHZ_2_4 -> (1..13).toList()
        Band.GHZ_5 -> (36..64 step 4) + (100..144 step 4) + (149..177 step 4)
        Band.GHZ_6 -> (5..229 step 16).toList()
    }

    /**
     * Rates a 20 MHz channel by every network whose occupied spectrum overlaps it:
     * the more networks and the stronger their combined power, the fewer stars.
     */
    fun score(channel: Int, aps: List<AccessPoint>): ChannelScore {
        val overlapping = aps.filter { abs(channel - it.centerPos) < it.halfWidth + 2f }
        if (overlapping.isEmpty()) return ChannelScore(channel, 0, null, 5)
        val milliwatts = overlapping.sumOf { 10.0.pow(it.level / 10.0) }
        val dbm = (10 * log10(milliwatts)).toInt()
        val stars = when {
            dbm < -85 -> 4
            dbm < -75 -> 3
            dbm < -65 -> 2
            else -> 1
        }
        return ChannelScore(channel, overlapping.size, dbm, stars)
    }

    private fun ChannelScore.rank(): Int = stars * 1000 - (interferenceDbm ?: -200) - count

    fun best(scores: List<ChannelScore>): ChannelScore? = scores.maxByOrNull { it.rank() }
}
