package app.clearmind.companion

import java.net.URI

/** Accept a backup code, or only QR links from the configured ClearMind origin. */
object PairingInput {
    fun parse(raw: String): String? {
        val value = raw.trim()
        if (Regex("^(CLM-?)?\\d{6}$", RegexOption.IGNORE_CASE).matches(value)) return value.uppercase()
        if (Regex("^[a-zA-Z0-9]{32,64}$").matches(value)) return value
        return runCatching {
            val uri = URI(value)
            val origin = URI(BuildConfig.API_BASE)
            if (uri.scheme != "https" || uri.host != origin.host || uri.port != origin.port || uri.userInfo != null) return null
            if (uri.path.startsWith("/pair/")) return parse(uri.path.removePrefix("/pair/"))
            if (uri.path == "/setup-child") {
                val params = uri.rawQuery.orEmpty().split("&").associate { part -> part.substringBefore("=") to java.net.URLDecoder.decode(part.substringAfter("=", ""), "UTF-8") }
                return parse(params["token"] ?: params["code"] ?: return null)
            }
            null
        }.getOrNull()
    }
}