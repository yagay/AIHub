package com.yagay.aihub.android;

import android.app.AlertDialog;
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
    private final AiHubStateStore stateStore;
    private final BrowserSessionRuntime runtime;
    private final SessionManager sessions;
    private final Map<String, Button> providerButtons = new LinkedHashMap<>();

    private FrameLayout overlay;
    private LinearLayout providerRail;
    private TextView currentProvider;
    private EditText composer;
    private boolean chromeControlsVisible;

    private AiHubUiCoordinator(
            AiHubBrowserHost host,
            ProviderRegistry providers,
            AiHubStateStore stateStore) {
        this.host = host;
        this.providers = providers;
        this.stateStore = stateStore;
        this.runtime = new BrowserSessionRuntime(host);
        this.sessions = new SessionManager(providers, runtime);
    }

    /** Normal Chromium entry: generated JSON rules + custom providers + signed overrides. */
    public static AiHubUiCoordinator attachConfigured(AiHubBrowserHost host) {
        AiHubStateStore stateStore = new AiHubStateStore(host.activity());
        ProviderRegistry providers = new ProviderRuleLoader(stateStore).load();
        return attach(host, providers, stateStore);
    }

    /** Fallback/testing entry that needs no generated build rule class. */
    public static AiHubUiCoordinator attachDefault(AiHubBrowserHost host) {
        return attach(
                host,
                BuiltinProviders.createDefaultRegistry(),
                new AiHubStateStore(host.activity()));
    }

    public static AiHubUiCoordinator attach(AiHubBrowserHost host, ProviderRegistry providers) {
        return attach(host, providers, new AiHubStateStore(host.activity()));
    }

    private static AiHubUiCoordinator attach(
            AiHubBrowserHost host,
            ProviderRegistry providers,
            AiHubStateStore stateStore) {
        AiHubUiCoordinator coordinator = new AiHubUiCoordinator(host, providers, stateStore);
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
            String saved = stateStore.savedProviderId();
            activate(saved != null && providers.contains(saved) ? saved : providers.all().get(0).id());
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
        providerRail = new LinearLayout(host.activity());
        providerRail.setOrientation(LinearLayout.HORIZONTAL);
        providerRail.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(providerRail, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        for (ProviderConfig provider : providers.all()) addProviderButton(provider);
        Button addAi = button("＋ AI", ignored -> showAddAiDialog());
        addAi.setAllCaps(false);
        providerRail.addView(addAi, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

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

    private void addProviderButton(ProviderConfig provider) {
        if (providerButtons.containsKey(provider.id())) return;
        Button item = button(provider.displayName(), ignored -> activate(provider.id()));
        item.setAllCaps(false);
        providerButtons.put(provider.id(), item);
        // Keep the Add AI button last when adding a custom provider after initial construction.
        int index = providerRail.getChildCount();
        if (index > 0 && "＋ AI".contentEquals(((Button) providerRail.getChildAt(index - 1)).getText())) {
            index--;
        }
        providerRail.addView(item, index, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
    }

    private void showAddAiDialog() {
        LinearLayout form = new LinearLayout(host.activity());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText name = new EditText(host.activity());
        name.setHint("AI name");
        form.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText url = new EditText(host.activity());
        url.setHint("https://example.com/");
        url.setSingleLine(true);
        form.addView(url, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(host.activity())
                .setTitle("Add AI website")
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Add", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    try {
                        ProviderConfig provider = CustomProviderFactory.create(
                                providers,
                                stateStore,
                                name.getText().toString(),
                                url.getText().toString());
                        addProviderButton(provider);
                        activate(provider.id());
                        dialog.dismiss();
                    } catch (RuntimeException error) {
                        url.setError(error.getMessage() == null ? "Invalid AI website" : error.getMessage());
                    }
                }));
        dialog.show();
    }

    private void activate(String providerId) {
        ProviderConfig provider = providers.require(providerId);
        sessions.switchProvider(providerId);
        stateStore.saveCurrent(sessions.currentKey());
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
