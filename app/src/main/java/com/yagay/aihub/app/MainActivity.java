package com.yagay.aihub.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.yagay.aihub.ui.MainController;

/** Thin Android lifecycle entry point. All app behavior lives behind MainController. */
public final class MainActivity extends ComponentActivity {
    private MainController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        controller = new MainController(this);
        View root = controller.screen().root();
        root.setFitsSystemWindows(true);
        setContentView(root);
        controller.start();
        controller.handleIntent(getIntent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (controller != null && controller.handleBack()) return;
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
        if (controller != null) controller.handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.destroy();
        controller = null;
        super.onDestroy();
    }
}
