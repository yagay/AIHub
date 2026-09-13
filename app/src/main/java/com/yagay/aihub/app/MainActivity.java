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

        ui.showStatus("AIHub UI 正在加载…\n正在准备 Titanium Companion 扩展…", false);
        titanium.prepareAsync(new TitaniumManager.Callback() {
            @Override
            public void onReady() {
                runOnUiThread(() -> {
                    ui.showTransientStatus("Titanium Companion 已准备");
                    Toast.makeText(MainActivity.this,
                            "Titanium Companion ready", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    String message = error == null || error.getMessage() == null
                            ? String.valueOf(error)
                            : error.getMessage();
                    ui.showDismissibleWarning("Titanium Companion 自动配置失败\n\n" + message
                            + "\n\n这不代表 Titanium Browser 没有安装。AIHub UI 仍可使用。"
                            + "如果你已手动安装 Companion 扩展，可以忽略自动配置；"
                            + "也可以在 Titanium 的 chrome://extensions 中手动 Load unpacked。\n"
                            + "下一次启动 AIHub 会再次尝试自动配置。");
                    Toast.makeText(MainActivity.this,
                            "Titanium Companion setup failed: " + message, Toast.LENGTH_LONG).show();
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
