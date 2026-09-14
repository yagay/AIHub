package com.yagay.aihub.data

import android.content.Context
import com.yagay.aihub.model.AccountProfile
import com.yagay.aihub.provider.ProviderCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("aihub_accounts", Context.MODE_PRIVATE)

    fun loadAll(): List<AccountProfile> {
        val parsed = runCatching {
            val array = JSONArray(prefs.getString("accounts", "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(AccountProfile(o.getString("id"), o.getString("providerId"), o.getString("label")))
                }
            }
        }.getOrDefault(emptyList()).toMutableList()

        var changed = false
        ProviderCatalog.all.forEach { provider ->
            if (parsed.none { it.providerId == provider.id }) {
                parsed += AccountProfile("default_${provider.id}", provider.id, "Default")
                changed = true
            }
        }
        if (changed) save(parsed)
        return parsed
    }

    fun add(providerId: String, label: String): AccountProfile {
        val current = loadAll().toMutableList()
        val item = AccountProfile(
            UUID.randomUUID().toString(),
            providerId,
            label.trim().ifBlank { "Account ${current.count { it.providerId == providerId } + 1}" }
        )
        current += item
        save(current)
        return item
    }

    private fun save(accounts: List<AccountProfile>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(JSONObject().put("id", account.id).put("providerId", account.providerId).put("label", account.label))
        }
        prefs.edit().putString("accounts", array.toString()).apply()
    }
}
