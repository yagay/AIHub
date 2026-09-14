package com.yagay.aihub

import android.app.Application
import com.yagay.aihub.diagnostics.DiagnosticLogger

class AIHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.init(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagnosticLogger.e(
                "CRASH",
                "uncaught_exception thread=${thread.name} type=${throwable.javaClass.name} message=${throwable.message.orEmpty()}",
                throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
