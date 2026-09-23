package com.yagay.aihub

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.yagay.aihub.diagnostics.DiagnosticLogger

/**
 * Compatibility launcher for the merged AI workspace.
 *
 * The real AI UI/runtime now lives in YBrowser's ai-workspace module. Keeping
 * this tiny activity preserves the existing AIHub launcher icon and YagaYHub
 * intents without maintaining a second chat/runtime implementation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.i(
            "ACTIVITY",
            "forward_to_ybrowser restored=" +
                (savedInstanceState != null),
        )
        forwardToYBrowser(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardToYBrowser(intent)
    }

    private fun forwardToYBrowser(source: Intent?) {
        val forwarded = Intent().apply {
            component = ComponentName(
                YBROWSER_PACKAGE,
                YBROWSER_AI_ACTIVITY,
            )
            action = when (source?.action) {
                ACTION_OPEN_AI_WEB ->
                    ACTION_YBROWSER_OPEN_AI_WEB
                else ->
                    ACTION_YBROWSER_OPEN_AI
            }

            source?.extras?.let(::putExtras)
            source?.data?.let { data = it }
            source?.clipData?.let { clipData = it }
            flags =
                (source?.flags ?: 0) or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        try {
            startActivity(forwarded)
            finish()
        } catch (error: ActivityNotFoundException) {
            DiagnosticLogger.e(
                "ACTIVITY",
                "ybrowser_ai_activity_missing",
                error,
            )
            Toast.makeText(
                this,
                "请先安装或更新 YBrowser。",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    companion object {
        private const val YBROWSER_PACKAGE =
            "com.yagay.YBrowser"
        private const val YBROWSER_AI_ACTIVITY =
            "com.yagay.ybrowser.ai.AiWorkspaceActivity"

        private const val ACTION_OPEN_AI_WEB =
            "com.yagay.AIHub.action.OPEN_AI_WEB"
        private const val ACTION_YBROWSER_OPEN_AI =
            "com.yagay.YBrowser.action.OPEN_AI"
        private const val ACTION_YBROWSER_OPEN_AI_WEB =
            "com.yagay.YBrowser.action.OPEN_AI_WEB"
    }
}
