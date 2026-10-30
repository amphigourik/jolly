// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

/** A Wi-Fi network seen in a scan. */
data class WifiNet(val ssid: String, val bssid: String, val level: Int) {
    val parrot get() = ParrotDetect.isParrot(ssid, bssid)
}

/** Heuristic recognition of Parrot Jumping drones from a scan result. */
object ParrotDetect {
    /** Parrot SA MAC address prefixes (OUI). */
    private val OUIS = setOf("A0:14:3D", "90:03:B7", "00:26:7E", "00:12:1C", "90:3A:E6")
    private val SSID_PREFIXES = listOf("JumpingSumo", "JumpingRace", "JumpingNight", "JS_")

    fun isParrot(ssid: String, bssid: String): Boolean {
        val oui = bssid.uppercase().replace('-', ':').take(8)
        if (oui in OUIS) return true
        return SSID_PREFIXES.any { ssid.startsWith(it, ignoreCase = true) }
    }
}

object DroneList {
    /** Networks to show: blank SSIDs dropped, strongest per SSID, Parrot drones first, then by signal. */
    fun sortNets(nets: List<WifiNet>): List<WifiNet> =
        nets.filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }
            .map { (_, l) -> l.maxByOrNull { it.level }!! }
            .sortedWith(compareByDescending<WifiNet> { it.parrot }.thenByDescending { it.level }.thenBy { it.ssid })
}

/** Saved drones (SSIDs), most recently used first; the head is the active drone. */
object SavedDrones {
    const val MAX = 20

    fun active(list: List<String>) = list.firstOrNull()

    /** Marks [ssid] as used: moves it to the front (adding it if new). */
    fun use(list: List<String>, ssid: String): List<String> {
        val s = ssid.trim()
        if (s.isEmpty()) return list
        return (listOf(s) + list.filter { it != s }).take(MAX)
    }

    fun remove(list: List<String>, ssid: String) = list.filter { it != ssid }

    /** Renames an entry in place (keeps its position); no-op if [old] is unknown. */
    fun rename(list: List<String>, old: String, new: String): List<String> {
        if (old !in list) return list
        val n = new.trim()
        if (n.isEmpty()) return list
        return list.map { if (it == old) n else it }.distinct()
    }

    // One SSID per line; backslash and newline are escaped.
    fun serialize(list: List<String>) = list.joinToString("\n") { it.replace("\\", "\\\\").replace("\n", "\\n") }

    fun parse(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split('\n').map { line ->
            val sb = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    sb.append(if (line[i + 1] == 'n') '\n' else line[i + 1]); i += 2
                } else { sb.append(c); i++ }
            }
            sb.toString()
        }.filter { it.isNotBlank() }.distinct().take(MAX)
    }
}
