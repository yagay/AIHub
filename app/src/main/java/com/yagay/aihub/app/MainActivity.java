package com.yagay.aihub.app;

import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.yagay.aihub.bridge.LocalBroker;
import com.yagay.aihub.diagnostics.DiagnosticsExporter;
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

        ui.setDiagnosticAction(() -> exportDiagnostics());

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
                            + "如果扩展没有自动安装，请点击右上角“诊断”导出完整日志。\n"
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

    private void exportDiagnostics() {
        if (ui == null) return;
        ui.showTransientStatus("正在导出完整诊断日志…");
        DiagnosticsExporter.exportAsync(this, broker, ui, new DiagnosticsExporter.Callback() {
            @Override
            public void onSuccess(String displayName, String location) {
                runOnUiThread(() -> {
                    if (ui != null) ui.showTransientStatus("诊断已保存到 Download\n" + displayName);
                    Toast.makeText(MainActivity.this,
                            "诊断已保存: " + location, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    String message = error == null || error.getMessage() == null
                            ? String.valueOf(error)
                            : error.getMessage();
                    if (ui != null) ui.showDismissibleWarning("诊断导出失败\n\n" + message);
                    Toast.makeText(MainActivity.this,
                            "诊断导出失败: " + message, Toast.LENGTH_LONG).show();
                });
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
