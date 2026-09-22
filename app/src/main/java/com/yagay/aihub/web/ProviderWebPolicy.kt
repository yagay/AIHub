package com.yagay.aihub.web

import android.net.Uri
import com.yagay.aihub.model.ProviderSpec

enum class UserAgentMode {
    DEFAULT,
    COMPAT_MOBILE
}

data class ProviderWebPolicy(
    val primaryHosts: Set<String>,
    val authHosts: Set<String>,
    val messageOriginRules: Set<String>,
    val userAgentMode: UserAgentMode = UserAgentMode.COMPAT_MOBILE,
    val acceptThirdPartyCookies: Boolean = true
) {
    fun allowsTopLevel(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        return matchesAny(host, primaryHosts) || matchesAny(host, authHosts)
    }

    fun allowsMessageOrigin(origin: Uri): Boolean {
        if (origin.scheme?.lowercase() != "https") return false
        val host = origin.host?.lowercase() ?: return false
        return primaryHosts.any { allowed ->
            host == allowed.lowercase()
        }
    }

    private fun matchesAny(host: String, allowedHosts: Set<String>): Boolean =
        allowedHosts.any { allowed ->
            val normalized = allowed.lowercase()
            host == normalized || host.endsWith(".$normalized")
        }
}

object ProviderWebPolicies {
    private val commonAuthHosts = setOf(
        "accounts.google.com",
        "login.microsoftonline.com",
        "login.live.com",
        "appleid.apple.com",
        "auth.openai.com",
        "auth0.com"
    )

    fun forProvider(provider: ProviderSpec): ProviderWebPolicy {
        val homeHost = Uri.parse(provider.homeUrl).host.orEmpty().lowercase()
        val primary = when (provider.id) {
            "chatgpt" -> setOf("chatgpt.com", "openai.com")
            "claude" -> setOf("claude.ai", "anthropic.com")
            "gemini" -> setOf("gemini.google.com")
            "grok" -> setOf("grok.com", "x.ai", "x.com", "twitter.com")
            "deepseek" -> setOf("chat.deepseek.com", "deepseek.com")
            "qwen" -> setOf("chat.qwen.ai", "qwen.ai")
            else -> setOf(homeHost).filter { it.isNotBlank() }.toSet()
        }

        val providerAuth = when (provider.id) {
            "chatgpt" -> setOf("auth.openai.com")
            "claude" -> setOf("console.anthropic.com")
            "gemini" -> setOf("accounts.google.com", "myaccount.google.com")
            "grok" -> setOf("x.com", "twitter.com")
            "qwen" -> setOf("account.aliyun.com", "passport.alibaba.com")
            else -> emptySet()
        }

        val mainHost = homeHost.takeIf { it.isNotBlank() }
        val messageRules = buildSet {
            if (mainHost != null) add("https://$mainHost")
        }

        return ProviderWebPolicy(
            primaryHosts = primary,
            authHosts = commonAuthHosts + providerAuth,
            messageOriginRules = messageRules,
            userAgentMode = UserAgentMode.COMPAT_MOBILE,
            acceptThirdPartyCookies = true
        )
    }
}
