package com.adshield.pro.engine.filter
class FilterRuleEngine {
    companion object {
        val DEFAULT_SAFE_ALLOWLIST = setOf(
            "connectivitycheck.gstatic.com", "clients3.google.com", "captive.apple.com",
            "play.googleapis.com", "android.clients.google.com"
        )
    }
}
