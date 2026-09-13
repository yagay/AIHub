package com.yagay.aihub.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.yagay.aihub.browser.ChromeCdpEngine;
import com.yagay.aihub.ui.UiHost;

/** Android lifecycle shell. The chat UI is NextChat; native code owns browser automation only. */
public final class MainActivity extends ComponentActivity {
    private ChromeCdpEngine browserEngine;
    private UiHost uiHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        browserEngine = new ChromeCdpEngine(this);
        uiHost = new UiHost(this, browserEngine);
        View root = uiHost.view();
        root.setFitsSystemWindows(true);
        setContentView(root);
        uiHost.start();
        uiHost.handleIntent(getIntent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (uiHost != null && uiHost.handleBack()) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (uiHost != null) uiHost.handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (uiHost != null) uiHost.destroy();
        if (browserEngine != null) browserEngine.shutdown();
        uiHost = null;
        browserEngine = null;
        super.onDestroy();
    }
}
