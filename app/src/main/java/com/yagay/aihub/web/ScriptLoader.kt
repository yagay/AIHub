package com.yagay.aihub.web

import android.content.Context

class ScriptLoader(private val context: Context) {
    private val cache = mutableMapOf<String, String>()

    fun providerScript(asset: String): String = load(asset) + "\n" + load("providers/common.js")

    private fun load(path: String): String = cache.getOrPut(path) {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
