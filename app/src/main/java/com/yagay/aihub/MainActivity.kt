package com.yagay.aihub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.ui.WorkspaceRoot
import com.yagay.aihub.ui.theme.AIHubTheme
import com.yagay.aihub.web.WindowWebRuntime

class MainActivity : ComponentActivity() {
    private val webRuntime by lazy { WindowWebRuntime(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.i("ACTIVITY", "MainActivity.onCreate restored=${savedInstanceState != null}")
        enableEdgeToEdge()
        setContent {
            AIHubTheme {
                WorkspaceRoot(runtime = webRuntime)
            }
        }
    }

    override fun onPause() {
        webRuntime.flushCookies()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (!webRuntime.handleAndroidPermissionResult(
                requestCode,
                permissions,
                grantResults
            )
        ) {
            super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
            )
        }
    }
}
