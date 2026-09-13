package com.yagay.aihub.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import com.yagay.aihub.data.AccountRepository;
import com.yagay.aihub.data.AppPreferences;
import com.yagay.aihub.model.AccountProfile;
import com.yagay.aihub.model.ProviderSpec;
import com.yagay.aihub.provider.AiProviderAdapter;
import com.yagay.aihub.provider.ProviderRegistry;
import com.yagay.aihub.session.WebSessionManager;

import java.util.ArrayList;
import java.util.List;

/** Coordinates UI, provider registry, accounts and retained WebView sessions. */
public final class MainController implements MainScreen.Callback, WebSessionManager.Events {
    private final Activity activity;
    private final ProviderRegistry providers;
    private final AccountRepository accounts;
    private final AppPreferences preferences;
    private final MainScreen screen;
    private final WebSessionManager sessions;

    private AiProviderAdapter currentProvider;
    private AccountProfile currentAccount;

    public MainController(Activity activity) {
        this.activity = activity;
        providers = new ProviderRegistry(activity);
        accounts = new AccountRepository(activity);
        preferences = new AppPreferences(activity);
        screen = new MainScreen(activity, this);
        sessions = new WebSessionManager(activity, screen.webContainer(), this);
    }

    public MainScreen screen() { return screen; }

    public void start() {
        List<ProviderSpec> specs = new ArrayList<>();
        for (AiProviderAdapter adapter : providers.all()) specs.add(adapter.spec());
        screen.setProviders(specs);
        sessions.setAppModeEnabled(preferences.appModeEnabled());
        screen.showAppMode(preferences.appModeEnabled());

        String saved = preferences.providerId();
        if (saved == null || !providers.contains(saved)) saved = providers.all().get(0).spec().id();
        activateProvider(saved);
    }

    public boolean handleBack() {
        return sessions.goBack();
    }

    public void destroy() {
        sessions.destroy();
    }

    @Override
    public void onProviderSelected(String providerId) {
        activateProvider(providerId);
    }

    @Override
    public void onAccountRequested() {
        if (currentProvider == null) return;
        String providerId = currentProvider.spec().id();
        List<AccountProfile> all = accounts.list(providerId);
        String[] labels = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) labels[i] = all.get(i).name();
        labels[all.size()] = "＋ Add account";

        new AlertDialog.Builder(activity)
                .setTitle(currentProvider.spec().name() + " accounts")
                .setItems(labels, (dialog, which) -> {
                    if (which == all.size()) showAddAccountDialog();
                    else {
                        AccountProfile selected = all.get(which);
                        accounts.select(providerId, selected.id());
                        activateProvider(providerId);
                    }
                })
                .show();
    }

    @Override
    public void onAppModeToggled() {
        boolean enabled = !sessions.isAppModeEnabled();
        sessions.setAppModeEnabled(enabled);
        preferences.setAppModeEnabled(enabled);
        screen.showAppMode(enabled);
    }

    @Override public void onNewChat() { sessions.newChat(); }
    @Override public void onReload() { sessions.reload(); }
    @Override public void onStop() { sessions.stopGeneration(); }
    @Override public void onSend(String text) { sessions.send(text); }

    @Override
    public void onMessage(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPageReady() {
        // Reserved for future capability probing/status updates without coupling UI to WebView.
    }

    private void activateProvider(String providerId) {
        AiProviderAdapter adapter = providers.require(providerId);
        AccountProfile account = accounts.selected(providerId);
        currentProvider = adapter;
        currentAccount = account;
        preferences.setProviderId(providerId);

        screen.showProvider(adapter.spec());
        screen.showAccount(account.name());
        sessions.switchTo(adapter, account, accounts.webProfileName(providerId, account));
    }

    private void showAddAccountDialog() {
        if (currentProvider == null) return;
        EditText input = new EditText(activity);
        input.setHint("Account name");
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        input.setPadding(padding, 0, padding, 0);

        new AlertDialog.Builder(activity)
                .setTitle("Add " + currentProvider.spec().name() + " account")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Add", (dialog, which) -> {
                    String providerId = currentProvider.spec().id();
                    AccountProfile created = accounts.add(providerId, input.getText().toString());
                    currentAccount = created;
                    activateProvider(providerId);
                })
                .show();
    }
}
