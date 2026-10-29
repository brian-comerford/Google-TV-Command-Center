package com.sportscc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The newest published build, from GitHub's "latest release". */
data class ReleaseInfo(
    /** e.g. "1.0.27" (the tag without its "v"). */
    val version: String,
    /** One line about what changed, if the release says. */
    val whatsNew: String?,
    /** Direct link to the APK in that release. */
    val apkUrl: String?,
)

object Updates {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads https://api.github.com/repos/<owner>/<repo>/releases/latest. */
    fun parseLatest(text: String, apkName: String = "SportsCommandCenter.apk"): ReleaseInfo? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val tag = o.str("tag_name") ?: return null
        val version = tag.removePrefix("v")
        if (parts(version).isEmpty()) return null // a saved version like "non-browser", not a numbered build
        val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("name") == apkName }?.str("browser_download_url")
        return ReleaseInfo(version, whatsNew(o.str("body")), apk)
    }

    /** True if [latest] is a higher version than [current], comparing number by number (1.0.10 > 1.0.9). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest.removePrefix("v"))
        val b = parts(current.removePrefix("v"))
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The release notes' "What's new:" line (see .github/workflows/build-apk.yml). */
    fun whatsNew(body: String?): String? = body?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("What's new:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    private fun parts(v: String): List<Int> {
        if (!v.matches(Regex("""\d+(\.\d+)*"""))) return emptyList()
        return v.split('.').map { it.toInt() }
    }
}
