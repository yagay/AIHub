package com.yagay.aihub.chromium;

import android.content.Intent;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.yagay.aihub.R;
import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.CommandResult;

import java.util.List;

/**
 * Unified AI shell. Provider switching, account switching and third-party commands all end up in
 * the same SessionManager/AiCommandBus path.
 */
public final class AiHubShellActivity extends AppCompatActivity {
    private AiHubStateStore stateStore;
    private AiHubBootstrap.Graph graph;

    private Button providerButton;
    private Button accountButton;
    private EditText composer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.aihub_activity_main);

        stateStore = new AiHubStateStore(this);
        AiWebEngineHost host = new AiWebEngineHost(
                this,
                getSupportFragmentManager(),
                R.id.web_container);
        graph = AiHubBootstrap.create(stateStore, new WebEngineSessionRuntime(host));

        bindViews();
        activateInitialSession();
        handleExternalIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }

    private void bindViews() {
        providerButton = findViewById(R.id.provider_button);
        accountButton = findViewById(R.id.account_button);
        composer = findViewById(R.id.composer);

        findViewById(R.id.previous_provider).setOnClickListener(v -> {
            graph.sessions.previousProvider();
            onSessionChanged();
        });
        findViewById(R.id.next_provider).setOnClickListener(v -> {
            graph.sessions.nextProvider();
            onSessionChanged();
        });
        providerButton.setOnClickListener(v -> showProviderPicker());
        accountButton.setOnClickListener(v -> showAccountPicker());
        accountButton.setOnLongClickListener(v -> {
            showIntegrationToken();
            return true;
        });
        findViewById(R.id.new_chat_button).setOnClickListener(v -> graph.sessions.newChat());
        findViewById(R.id.stop_button).setOnClickListener(v -> graph.sessions.stop());
        findViewById(R.id.send_button).setOnClickListener(v -> sendComposer());

        composer.setSingleLine(false);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setOnEditorActionListener((v, actionId, event) -> {
            boolean keyboardSend = actionId == EditorInfo.IME_ACTION_SEND;
            boolean hardwareSend = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && !event.isShiftPressed();
            if (!keyboardSend && !hardwareSend) return false;
            sendComposer();
            return true;
        });
    }

    private void activateInitialSession() {
        String provider = stateStore.savedProviderId();
        String account = stateStore.savedAccountId();
        try {
            if (provider != null && account != null
                    && graph.providers.contains(provider)
                    && graph.accounts.contains(account)) {
                graph.sessions.activate(provider, account, null);
            } else {
                ProviderConfig first = graph.providers.all().get(0);
                AiAccount firstAccount = graph.accounts.forProvider(first.id()).get(0);
                graph.sessions.activate(first.id(), firstAccount.id(), null);
            }
        } catch (RuntimeException error) {
            ProviderConfig first = graph.providers.all().get(0);
            AiAccount firstAccount = graph.accounts.forProvider(first.id()).get(0);
            graph.sessions.activate(first.id(), firstAccount.id(), null);
        }
        onSessionChanged();
    }

    private void sendComposer() {
        String text = composer.getText().toString();
        if (text.isBlank()) return;
        graph.sessions.sendText(text);
        composer.setText("");
    }

    private void showProviderPicker() {
        List<ProviderConfig> providers = graph.providers.all();
        String[] labels = providers.stream().map(ProviderConfig::displayName).toArray(String[]::new);
        AiSessionKey current = graph.sessions.currentKey();
        int checked = 0;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).id().equals(current.providerId())) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_ai)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    graph.sessions.switchProvider(providers.get(which).id());
                    dialog.dismiss();
                    onSessionChanged();
                })
                .show();
    }

    private void showAccountPicker() {
        String providerId = graph.sessions.currentKey().providerId();
        List<AiAccount> accounts = graph.accounts.forProvider(providerId);
        String[] labels = new String[accounts.size() + 1];
        for (int i = 0; i < accounts.size(); i++) labels[i] = accounts.get(i).label();
        labels[labels.length - 1] = getString(R.string.add_account);

        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_account)
                .setItems(labels, (dialog, which) -> {
                    if (which == accounts.size()) showAddAccountDialog(providerId);
                    else {
                        graph.sessions.switchAccount(accounts.get(which).id());
                        onSessionChanged();
                    }
                })
                .show();
    }

    private void showAddAccountDialog(String providerId) {
        EditText label = new EditText(this);
        label.setHint(R.string.account_name_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_account)
                .setView(label)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    AiAccount account = AiHubBootstrap.addAccount(
                            graph.providers,
                            graph.accounts,
                            stateStore,
                            providerId,
                            label.getText().toString());
                    graph.sessions.activate(providerId, account.id(), null);
                    onSessionChanged();
                })
                .show();
    }

    private void handleExternalIntent(Intent intent) {
        AiCommand command = AiHubExternalCommandParser.parse(intent, stateStore.clientToken());
        if (command == null) return;
        CommandResult result = graph.commands.execute(command);
        if (!result.success()) {
            Toast.makeText(this, result.message(), Toast.LENGTH_LONG).show();
            return;
        }
        onSessionChanged();
    }

    private void showIntegrationToken() {
        String token = stateStore.clientToken();
        new AlertDialog.Builder(this)
                .setTitle(R.string.integration_token)
                .setMessage(token)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("AIHub client token", token));
                    Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void onSessionChanged() {
        AiSessionKey key = graph.sessions.currentKey();
        ProviderConfig provider = graph.providers.require(key.providerId());
        AiAccount account = graph.accounts.require(key.accountId());
        providerButton.setText(provider.displayName());
        accountButton.setText(account.label());
        stateStore.saveCurrent(key);
    }
}
