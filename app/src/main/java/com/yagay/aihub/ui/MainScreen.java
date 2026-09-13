package com.yagay.aihub.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yagay.aihub.model.ProviderSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure native UI. It knows nothing about WebView, DOM selectors, cookies or website internals. */
public final class MainScreen {
    public interface Callback {
        void onProviderSelected(String providerId);
        void onAccountRequested();
        void onAppModeToggled();
        void onNewChat();
        void onReload();
        void onStop();
        void onSend(String text);
    }

    private final Activity activity;
    private final Callback callback;
    private final LinearLayout root;
    private final FrameLayout webContainer;
    private final LinearLayout providerRail;
    private final TextView providerTitle;
    private final Button accountButton;
    private final Button modeButton;
    private final EditText composer;
    private final Map<String, Button> providerButtons = new LinkedHashMap<>();

    public MainScreen(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(4), dp(6), dp(4));

        providerTitle = new TextView(activity);
        providerTitle.setTextSize(18);
        providerTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(providerTitle, new LinearLayout.LayoutParams(0, dp(48), 1f));

        accountButton = button("Default", ignored -> callback.onAccountRequested());
        toolbar.addView(accountButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        modeButton = button("APP", ignored -> callback.onAppModeToggled());
        toolbar.addView(modeButton, new LinearLayout.LayoutParams(dp(64), dp(44)));

        toolbar.addView(button("↻", ignored -> callback.onReload()),
                new LinearLayout.LayoutParams(dp(52), dp(44)));
        toolbar.addView(button("＋", ignored -> callback.onNewChat()),
                new LinearLayout.LayoutParams(dp(52), dp(44)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        HorizontalScrollView providerScroll = new HorizontalScrollView(activity);
        providerScroll.setHorizontalScrollBarEnabled(false);
        providerRail = new LinearLayout(activity);
        providerRail.setGravity(Gravity.CENTER_VERTICAL);
        providerRail.setPadding(dp(4), 0, dp(4), 0);
        providerScroll.addView(providerRail, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(providerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        webContainer = new FrameLayout(activity);
        root.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composerBar = new LinearLayout(activity);
        composerBar.setGravity(Gravity.CENTER_VERTICAL);
        composerBar.setPadding(dp(6), dp(5), dp(6), dp(5));

        Button stop = button("■", ignored -> callback.onStop());
        composerBar.addView(stop, new LinearLayout.LayoutParams(dp(48), dp(48)));

        composer = new EditText(activity);
        composer.setHint("Message current AI…");
        composer.setSingleLine(false);
        composer.setMaxLines(5);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        composerBar.addView(composer, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button send = button("➤", ignored -> send());
        composerBar.addView(send, new LinearLayout.LayoutParams(dp(54), dp(48)));
        root.addView(composerBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
    }

    public LinearLayout root() { return root; }
    public FrameLayout webContainer() { return webContainer; }

    public void setProviders(List<ProviderSpec> providers) {
        providerRail.removeAllViews();
        providerButtons.clear();
        for (ProviderSpec provider : providers) {
            Button button = button(provider.name(), ignored -> callback.onProviderSelected(provider.id()));
            button.setAllCaps(false);
            providerRail.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
            providerButtons.put(provider.id(), button);
        }
    }

    public void showProvider(ProviderSpec provider) {
        providerTitle.setText(provider.name());
        for (Map.Entry<String, Button> entry : providerButtons.entrySet()) {
            boolean selected = entry.getKey().equals(provider.id());
            entry.getValue().setSelected(selected);
            entry.getValue().setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    public void showAccount(String name) {
        accountButton.setText(name);
        accountButton.setAllCaps(false);
    }

    public void showAppMode(boolean enabled) {
        modeButton.setText(enabled ? "APP" : "WEB");
        modeButton.setTypeface(Typeface.DEFAULT, enabled ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void send() {
        String text = composer.getText().toString();
        if (text.isBlank()) return;
        callback.onSend(text);
        composer.setText("");
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
