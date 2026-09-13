package com.yagay.aihub.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.yagay.aihub.model.ChatMessage;
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
        void onAttach();
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
    private final LinearLayout composerBar;
    private final ScrollView conversationScroll;
    private final LinearLayout conversationList;
    private final Map<String, Button> providerButtons = new LinkedHashMap<>();
    private String currentProviderName = "AI";

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
        toolbar.addView(modeButton, new LinearLayout.LayoutParams(dp(66), dp(44)));

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

        FrameLayout contentStack = new FrameLayout(activity);
        webContainer = new FrameLayout(activity);
        contentStack.addView(webContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        conversationScroll = new ScrollView(activity);
        conversationScroll.setFillViewport(true);
        conversationScroll.setBackgroundColor(resolveBackgroundColor());
        conversationList = new LinearLayout(activity);
        conversationList.setOrientation(LinearLayout.VERTICAL);
        conversationList.setPadding(dp(12), dp(12), dp(12), dp(18));
        conversationScroll.addView(conversationList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentStack.addView(conversationScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentStack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        composerBar = new LinearLayout(activity);
        composerBar.setGravity(Gravity.CENTER_VERTICAL);
        composerBar.setPadding(dp(6), dp(5), dp(6), dp(5));

        Button attach = button("File", ignored -> callback.onAttach());
        attach.setAllCaps(false);
        composerBar.addView(attach, new LinearLayout.LayoutParams(dp(58), dp(48)));

        Button stop = button("■", ignored -> callback.onStop());
        composerBar.addView(stop, new LinearLayout.LayoutParams(dp(46), dp(48)));

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

        showMessages(List.of());
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
        currentProviderName = provider.name();
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
        conversationScroll.setVisibility(enabled ? View.VISIBLE : View.GONE);
        composerBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    public void showMessages(List<ChatMessage> messages) {
        conversationList.removeAllViews();
        if (messages == null || messages.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText("No mirrored messages yet.\n\nIf you need to sign in or use a website feature that is not exposed here, switch to WEB mode.");
            hint.setTextSize(16);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(22), dp(40), dp(22), dp(40));
            conversationList.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (ChatMessage message : messages) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));

            TextView role = new TextView(activity);
            role.setText(message.role().equalsIgnoreCase("user") ? "You" : currentProviderName);
            role.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            role.setTextSize(13);
            card.addView(role, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView body = new TextView(activity);
            body.setText(message.text());
            body.setTextSize(16);
            body.setTextIsSelectable(true);
            body.setPadding(0, dp(5), 0, 0);
            card.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(10);
            conversationList.addView(card, cardParams);
        }
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    public void setComposerText(String text) {
        if (text == null || text.isBlank()) return;
        composer.setText(text);
        composer.setSelection(composer.getText().length());
        composer.requestFocus();
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

    private int resolveBackgroundColor() {
        TypedValue value = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
            if (value.resourceId != 0) return activity.getColor(value.resourceId);
            return value.data;
        }
        return 0xFFFFFFFF;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
