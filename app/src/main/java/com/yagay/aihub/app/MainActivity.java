package com.yagay.aihub.app;

import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.yagay.aihub.bridge.LocalBroker;
import com.yagay.aihub.titanium.TitaniumManager;
import com.yagay.aihub.ui.UiHost;

/** Fresh AIHub shell: NextChat UI + localhost IPC + Titanium extension. */
public final class MainActivity extends ComponentActivity {
    private LocalBroker broker;
    private TitaniumManager titanium;
    private UiHost ui;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ui = new UiHost(this);
        setContentView(ui.view());
        ui.start();

        titanium = new TitaniumManager(this);
        broker = new LocalBroker(titanium::launchProvider);
        broker.start();

        ui.showStatus("AIHub UI 正在加载…\n正在申请 Root 并准备 Titanium 扩展…", false);
        titanium.prepareAsync(new TitaniumManager.Callback() {
            @Override
            public void onReady() {
                runOnUiThread(() -> {
                    ui.showTransientStatus("Root 已授权 · Titanium 扩展已准备");
                    Toast.makeText(MainActivity.this,
                            "Root/Titanium ready", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    String message = error == null || error.getMessage() == null
                            ? String.valueOf(error)
                            : error.getMessage();
                    ui.showFatal("Root / Titanium 初始化失败\n\n" + message
                            + "\n\n请确认 KernelSU/Root 已授权 AIHub，并已安装 Titanium Browser。\n"
                            + "修复后重新打开 AIHub。");
                    Toast.makeText(MainActivity.this,
                            "Root/Titanium failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (ui != null && ui.handleBack()) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (ui != null) ui.destroy();
        if (broker != null) broker.close();
        ui = null;
        broker = null;
        titanium = null;
        super.onDestroy();
    }
}
