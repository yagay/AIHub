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

        titanium = new TitaniumManager(this);
        broker = new LocalBroker(titanium::launchProvider);
        broker.start();

        ui = new UiHost(this);
        setContentView(ui.view());
        ui.start();

        titanium.prepareAsync(new TitaniumManager.Callback() {
            @Override public void onReady() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Titanium bridge ready", Toast.LENGTH_SHORT).show());
            }

            @Override public void onError(Throwable error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Titanium bridge: " + error.getMessage(), Toast.LENGTH_LONG).show());
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
