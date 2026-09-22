package com.yagay.aihub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.ui.AIHubRoot
import com.yagay.aihub.ui.AIHubViewModel
import com.yagay.aihub.ui.theme.AIHubTheme
import com.yagay.aihub.web.WebRuntime

class MainActivity : ComponentActivity() {
    private val webRuntime by lazy { WebRuntime(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.i("ACTIVITY", "MainActivity.onCreate restored=${savedInstanceState != null}")
        enableEdgeToEdge()
        setContent {
            AIHubTheme {
                val vm: AIHubViewModel = viewModel(factory = AIHubViewModel.Factory(application))
                AIHubRoot(viewModel = vm, runtimeFactory = { webRuntime })
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
        if (!webRuntime.handleAndroidPermissionResult(requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}
