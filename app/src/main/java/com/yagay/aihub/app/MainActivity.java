package com.yagay.aihub.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.yagay.aihub.runtime.LocalGatewayManager;
import com.yagay.aihub.ui.UiHost;

/** Android lifecycle shell: NextChat UI + an app-private browser gateway backed by real Chrome. */
public final class MainActivity extends ComponentActivity {
    private LocalGatewayManager gatewayManager;
    private UiHost uiHost;
    private boolean uiStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        uiHost = new UiHost(this);
        View root = uiHost.view();
        root.setFitsSystemWindows(true);
        setContentView(root);
        uiHost.handleIntent(getIntent());

        gatewayManager = new LocalGatewayManager(this);
        gatewayManager.start(new LocalGatewayManager.Listener() {
            @Override
            public void onReady() {
                startUiOnce();
            }

            @Override
            public void onError(Throwable error) {
                Toast.makeText(
                        MainActivity.this,
                        "AIHub browser gateway: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG
                ).show();
                // Keep the UI reachable for settings/diagnostics even if Root/CDP is not ready yet.
                startUiOnce();
            }
        });

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

    private void startUiOnce() {
        if (uiStarted || uiHost == null) return;
        uiStarted = true;
        uiHost.start();
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
        uiHost = null;
        gatewayManager = null;
        super.onDestroy();
    }
}
