package com.yagay.aihub.app;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.yagay.aihub.android.AiHubUiCoordinator;

public final class MainActivity extends Activity {
    private WebViewBrowserHost browserHost;
    private AiHubUiCoordinator coordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);

        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        browserHost = new WebViewBrowserHost(this, root);
        coordinator = AiHubUiCoordinator.attachConfigured(browserHost);
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        if (browserHost != null && browserHost.canGoBack()) {
            browserHost.back();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (browserHost != null) browserHost.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (browserHost != null) {
            browserHost.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onDestroy() {
        if (coordinator != null) coordinator.destroy();
        if (browserHost != null) browserHost.destroy();
        super.onDestroy();
    }
}
