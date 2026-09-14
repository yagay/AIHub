package com.yagay.aihub.web

import android.content.Context

class ScriptLoader(private val context: Context) {
    private val cache = mutableMapOf<String, String>()

    fun providerScript(asset: String): String = buildString {
        append(load(asset))
        append('\n')
        append(load("providers/common.js"))
        append('\n')
        append(load("providers/attachment.js"))
    }

    private fun load(path: String): String = cache.getOrPut(path) {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
