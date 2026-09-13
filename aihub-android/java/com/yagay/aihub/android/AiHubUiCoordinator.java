package com.yagay.aihub.android;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.SessionManager;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI-first Android shell drawn over the real Chrome tabbed activity.
 *
 * It intentionally contains no Chromium imports. Chrome remains responsible for the actual page,
 * login, cookies, permissions, downloads, popups, media and browser lifecycle.
 */
public final class AiHubUiCoordinator {
    private static final String ROOT_TAG = "AIHUB_OVERLAY_ROOT";

    private final AiHubBrowserHost host;
    private final ProviderRegistry providers;
    private final BrowserSessionRuntime runtime;
    private final SessionManager sessions;
    private final Map<String, Button> providerButtons = new LinkedHashMap<>();

    private FrameLayout overlay;
    private TextView currentProvider;
    private EditText composer;
    private boolean chromeControlsVisible;

    private AiHubUiCoordinator(AiHubBrowserHost host, ProviderRegistry providers) {
        this.host = host;
        this.providers = providers;
        this.runtime = new BrowserSessionRuntime(host);
        this.sessions = new SessionManager(providers, runtime);
    }

    public static AiHubUiCoordinator attachDefault(AiHubBrowserHost host) {
        return attach(host, BuiltinProviders.createDefaultRegistry());
    }

    public static AiHubUiCoordinator attach(AiHubBrowserHost host, ProviderRegistry providers) {
        AiHubUiCoordinator coordinator = new AiHubUiCoordinator(host, providers);
        coordinator.attach();
        return coordinator;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public BrowserSessionRuntime runtime() {
        return runtime;
    }

    public void destroy() {
        runtime.shutdown();
        if (overlay != null && overlay.getParent() instanceof ViewGroup parent) {
            parent.removeView(overlay);
        }
        showChromeControls(true);
    }

    private void attach() {
        ViewGroup chromeRoot = host.overlayRoot();
        View old = chromeRoot.findViewWithTag(ROOT_TAG);
        if (old != null) chromeRoot.removeView(old);

        overlay = new FrameLayout(host.activity());
        overlay.setTag(ROOT_TAG);
        overlay.setClickable(false);
        chromeRoot.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildTopBar();
        buildComposer();

        // AIHub replaces the normal toolbar by default, but Chrome's controls remain alive and can
        // be restored instantly from the AIHub top bar.
        showChromeControls(false);

        if (!providers.all().isEmpty()) {
            activate(providers.all().get(0).id());
        }
    }

    private void buildTopBar() {
        LinearLayout top = new LinearLayout(host.activity());
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(6), dp(4), dp(6), dp(4));
        top.setClickable(true);

        LinearLayout nav = new LinearLayout(host.activity());
        nav.setGravity(Gravity.CENTER_VERTICAL);

        nav.addView(button("←", ignored -> sessions.back()));
        nav.addView(button("→", ignored -> sessions.forward()));
        nav.addView(button("↻", ignored -> sessions.reload()));

        currentProvider = new TextView(host.activity());
        currentProvider.setGravity(Gravity.CENTER);
        currentProvider.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        currentProvider.setTextSize(16);
        nav.addView(currentProvider, new LinearLayout.LayoutParams(0, dp(42), 1f));

        nav.addView(button("＋", ignored -> sessions.newChat()));
        nav.addView(button("Chrome", ignored -> showChromeControls(!chromeControlsVisible)));
        top.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        HorizontalScrollView scroll = new HorizontalScrollView(host.activity());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout rail = new LinearLayout(host.activity());
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(rail, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        for (ProviderConfig provider : providers.all()) {
            Button item = button(provider.displayName(), ignored -> activate(provider.id()));
            item.setAllCaps(false);
            providerButtons.put(provider.id(), item);
            rail.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        top.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(94), Gravity.TOP);
        overlay.addView(top, params);
    }

    private void buildComposer() {
        LinearLayout bar = new LinearLayout(host.activity());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(5), dp(6), dp(5));
        bar.setClickable(true);

        Button attach = button("＋", ignored -> sessions.attach(java.util.List.of()));
        bar.addView(attach, new LinearLayout.LayoutParams(dp(46), dp(46)));

        Button stop = button("■", ignored -> sessions.stop());
        bar.addView(stop, new LinearLayout.LayoutParams(dp(46), dp(46)));

        composer = new EditText(host.activity());
        composer.setSingleLine(false);
        composer.setMaxLines(5);
        composer.setHint("Message current AI…");
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComposer();
                return true;
            }
            return false;
        });
        bar.addView(composer, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button send = button("➤", ignored -> sendComposer());
        bar.addView(send, new LinearLayout.LayoutParams(dp(50), dp(46)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM);
        overlay.addView(bar, params);
    }

    private void activate(String providerId) {
        ProviderConfig provider = providers.require(providerId);
        sessions.switchProvider(providerId);
        currentProvider.setText(provider.displayName());
        for (Map.Entry<String, Button> entry : providerButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey().equals(providerId));
            entry.getValue().setTypeface(
                    Typeface.DEFAULT,
                    entry.getKey().equals(providerId) ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void sendComposer() {
        String text = composer.getText().toString();
        if (text.isBlank()) return;
        sessions.sendText(text);
        composer.setText("");
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(host.activity());
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private void showChromeControls(boolean show) {
        chromeControlsVisible = show;
        View controls = host.chromeControlContainer();
        if (controls != null) {
            controls.setVisibility(show ? View.VISIBLE : View.GONE);
            controls.requestLayout();
        }
    }

    private int dp(int value) {
        float density = host.activity().getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
