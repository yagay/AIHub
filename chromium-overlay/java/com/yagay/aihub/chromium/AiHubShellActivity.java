package com.yagay.aihub.chromium;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.yagay.aihub.R;
import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.AiWorkspace;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.CommandResult;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Unified AI shell: every provider/account/action shares one SessionManager/AiCommandBus path. */
public final class AiHubShellActivity extends AppCompatActivity {
    private static final int REQUEST_ATTACHMENTS = 4107;
    private static final int REQUEST_EXPORT_DIAGNOSTICS = 4108;

    private AiHubStateStore stateStore;
    private AiHubBootstrap.Graph graph;
    private WebEngineSessionRuntime runtime;
    private Button providerButton;
    private Button accountButton;
    private Button workspaceButton;
    private EditText composer;
    private String pendingDiagnosticsExport;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.aihub_activity_main);
        stateStore = new AiHubStateStore(this);
        AiWebEngineHost host = new AiWebEngineHost(this, getSupportFragmentManager(), R.id.web_container);
        runtime = new WebEngineSessionRuntime(host);
        graph = AiHubBootstrap.create(this, stateStore, runtime);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ATTACHMENTS) {
            if (resultCode != RESULT_OK || data == null) return;
            List<String> uris = collectSelectedUris(data);
            if (!uris.isEmpty()) graph.sessions.attach(uris);
            return;
        }
        if (requestCode == REQUEST_EXPORT_DIAGNOSTICS) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            writeDiagnostics(data.getData());
        }
    }

    private void bindViews() {
        providerButton = findViewById(R.id.provider_button);
        accountButton = findViewById(R.id.account_button);
        workspaceButton = findViewById(R.id.workspace_button);
        composer = findViewById(R.id.composer);
        findViewById(R.id.browser_back).setOnClickListener(v -> graph.sessions.back());
        findViewById(R.id.browser_forward).setOnClickListener(v -> graph.sessions.forward());
        findViewById(R.id.browser_reload).setOnClickListener(v -> graph.sessions.reload());
        findViewById(R.id.previous_provider).setOnClickListener(v -> { graph.sessions.previousProvider(); onSessionChanged(); });
        findViewById(R.id.next_provider).setOnClickListener(v -> { graph.sessions.nextProvider(); onSessionChanged(); });
        providerButton.setOnClickListener(v -> showProviderPicker());
        accountButton.setOnClickListener(v -> showAccountPicker());
        workspaceButton.setOnClickListener(v -> showWorkspacePicker());
        findViewById(R.id.new_chat_button).setOnClickListener(v -> graph.sessions.newChat());
        findViewById(R.id.attach_button).setOnClickListener(v -> openAttachmentPicker());
        findViewById(R.id.stop_button).setOnClickListener(v -> graph.sessions.stop());
        findViewById(R.id.send_button).setOnClickListener(v -> sendComposer());
        findViewById(R.id.menu_button).setOnClickListener(v -> showToolsMenu());
        composer.setSingleLine(false);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setOnEditorActionListener((v, actionId, event) -> {
            boolean keyboardSend = actionId == EditorInfo.IME_ACTION_SEND;
            boolean hardwareSend = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed();
            if (!keyboardSend && !hardwareSend) return false;
            sendComposer();
            return true;
        });
    }

    private void openAttachmentPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_ATTACHMENTS);
    }

    private List<String> collectSelectedUris(Intent data) {
        List<String> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) addSelectedUri(out, clip.getItemAt(i).getUri());
        }
        addSelectedUri(out, data.getData());
        return out;
    }

    private void addSelectedUri(List<String> out, Uri uri) {
        if (uri == null || out.contains(uri.toString())) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant only temporary read access; upload starts immediately.
        }
        out.add(uri.toString());
    }

    private void activateInitialSession() {
        String provider = stateStore.savedProviderId();
        String account = stateStore.savedAccountId();
        String workspace = stateStore.savedWorkspaceId();
        try {
            if (workspace != null && graph.workspaces.contains(workspace)) {
                graph.sessions.activate(provider, null, workspace);
            } else if (provider != null && account != null && graph.providers.contains(provider) && graph.accounts.contains(account)) {
                graph.sessions.activate(provider, account, null);
            } else activateFirstSession();
        } catch (RuntimeException error) { activateFirstSession(); }
        onSessionChanged();
    }

    private void activateFirstSession() {
        ProviderConfig first = graph.providers.all().get(0);
        AiAccount firstAccount = graph.accounts.forProvider(first.id()).get(0);
        graph.sessions.activate(first.id(), firstAccount.id(), null);
    }

    private void sendComposer() {
        String text = composer.getText().toString();
        if (text.isBlank()) return;
        graph.sessions.sendText(text);
        composer.setText("");
    }

    private void showProviderPicker() {
        List<ProviderConfig> providers = graph.providers.all();
        String[] labels = new String[providers.size() + 1];
        for (int i = 0; i < providers.size(); i++) labels[i] = providers.get(i).displayName();
        labels[labels.length - 1] = getString(R.string.add_ai_website);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_ai)
                .setItems(labels, (dialog, which) -> {
                    if (which == providers.size()) showAddProviderDialog();
                    else {
                        graph.sessions.switchProvider(providers.get(which).id());
                        onSessionChanged();
                    }
                }).show();
    }

    private void showAddProviderDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        EditText name = new EditText(this);
        name.setHint(R.string.ai_name_hint);
        EditText url = new EditText(this);
        url.setHint(R.string.ai_url_hint);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(url);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_ai_website)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        ProviderConfig provider = AiHubBootstrap.addCustomProvider(
                                graph.providers, graph.accounts, stateStore,
                                name.getText().toString(), url.getText().toString());
                        AiAccount account = graph.accounts.forProvider(provider.id()).get(0);
                        addProviderToWorkspaces(provider.id(), account.id());
                        graph.sessions.activate(provider.id(), account.id(), graph.sessions.activeWorkspaceId());
                        onSessionChanged();
                    } catch (RuntimeException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void addProviderToWorkspaces(String providerId, String accountId) {
        for (AiWorkspace workspace : graph.workspaces.all()) {
            if (workspace.providerAccounts().containsKey(providerId)) continue;
            Map<String, String> mapping = new LinkedHashMap<>(workspace.providerAccounts());
            mapping.put(providerId, accountId);
            graph.workspaces.register(new AiWorkspace(workspace.id(), workspace.label(), mapping));
        }
        stateStore.saveWorkspaces(graph.workspaces.all());
    }

    private void showAccountPicker() {
        String providerId = graph.sessions.currentKey().providerId();
        List<AiAccount> accounts = graph.accounts.forProvider(providerId);
        String[] labels = new String[accounts.size() + 1];
        for (int i = 0; i < accounts.size(); i++) labels[i] = accounts.get(i).label();
        labels[labels.length - 1] = getString(R.string.add_account);
        new AlertDialog.Builder(this).setTitle(R.string.choose_account).setItems(labels, (dialog, which) -> {
            if (which == accounts.size()) showAddAccountDialog(providerId);
            else selectAccount(accounts.get(which));
        }).show();
    }

    private void selectAccount(AiAccount account) {
        String workspaceId = graph.sessions.activeWorkspaceId();
        if (workspaceId == null) graph.sessions.switchAccount(account.id());
        else {
            AiWorkspace old = graph.workspaces.require(workspaceId);
            Map<String, String> mapping = new LinkedHashMap<>(old.providerAccounts());
            mapping.put(account.providerId(), account.id());
            graph.workspaces.register(new AiWorkspace(old.id(), old.label(), mapping));
            stateStore.saveWorkspaces(graph.workspaces.all());
            graph.sessions.switchWorkspace(workspaceId);
        }
        onSessionChanged();
    }

    private void showAddAccountDialog(String providerId) {
        EditText label = new EditText(this);
        label.setHint(R.string.account_name_hint);
        new AlertDialog.Builder(this).setTitle(R.string.add_account).setView(label)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    AiAccount account = AiHubBootstrap.addAccount(graph.providers, graph.accounts, stateStore,
                            providerId, label.getText().toString());
                    selectAccount(account);
                }).show();
    }

    private void showWorkspacePicker() {
        List<AiWorkspace> workspaces = graph.workspaces.all();
        String[] labels = new String[workspaces.size() + 2];
        labels[0] = getString(R.string.no_workspace);
        for (int i = 0; i < workspaces.size(); i++) labels[i + 1] = workspaces.get(i).label();
        labels[labels.length - 1] = getString(R.string.create_workspace);
        new AlertDialog.Builder(this).setTitle(R.string.choose_workspace).setItems(labels, (dialog, which) -> {
            if (which == 0) {
                graph.sessions.clearWorkspace();
                stateStore.saveWorkspaceId(null);
                onSessionChanged();
            } else if (which == labels.length - 1) showCreateWorkspaceDialog();
            else {
                graph.sessions.switchWorkspace(workspaces.get(which - 1).id());
                onSessionChanged();
            }
        }).show();
    }

    private void showCreateWorkspaceDialog() {
        EditText label = new EditText(this);
        label.setHint(R.string.workspace_name_hint);
        new AlertDialog.Builder(this).setTitle(R.string.create_workspace).setView(label)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = label.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.workspace_default_name);
                    Map<String, String> mapping = new LinkedHashMap<>();
                    for (ProviderConfig provider : graph.providers.all()) {
                        mapping.put(provider.id(), graph.sessions.preferredAccountId(provider.id()));
                    }
                    String id = "workspace:" + UUID.randomUUID().toString().replace("-", "");
                    graph.workspaces.register(new AiWorkspace(id, name, mapping));
                    stateStore.saveWorkspaces(graph.workspaces.all());
                    graph.sessions.switchWorkspace(id);
                    onSessionChanged();
                }).show();
    }

    private void showToolsMenu() {
        String[] items = {
                getString(R.string.provider_diagnostics),
                getString(R.string.export_diagnostics),
                getString(R.string.integration_token),
                getString(R.string.rotate_token),
                getString(R.string.rule_warnings),
                getString(R.string.current_url)
        };
        new AlertDialog.Builder(this).setTitle(R.string.tools).setItems(items, (dialog, which) -> {
            switch (which) {
                case 0 -> showProviderDiagnostics();
                case 1 -> exportDiagnostics();
                case 2 -> showIntegrationToken(false);
                case 3 -> confirmRotateToken();
                case 4 -> showRuleWarnings();
                case 5 -> showCurrentUrl();
                default -> { }
            }
        }).show();
    }

    private void showProviderDiagnostics() {
        Futures.addCallback(AiHubDiagnostics.snapshot(graph, runtime), new FutureCallback<>() {
            @Override public void onSuccess(String result) {
                showCopyDialog(getString(R.string.provider_diagnostics), result == null ? "{}" : result);
            }
            @Override public void onFailure(Throwable error) {
                Toast.makeText(AiHubShellActivity.this,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, getMainExecutor());
    }

    private void exportDiagnostics() {
        Futures.addCallback(AiHubDiagnostics.snapshot(graph, runtime), new FutureCallback<>() {
            @Override public void onSuccess(String result) {
                pendingDiagnosticsExport = result == null ? "{}" : result;
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "aihub-diagnostics.json");
                startActivityForResult(create, REQUEST_EXPORT_DIAGNOSTICS);
            }
            @Override public void onFailure(Throwable error) {
                Toast.makeText(AiHubShellActivity.this, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show();
            }
        }, getMainExecutor());
    }

    private void writeDiagnostics(Uri uri) {
        String text = pendingDiagnosticsExport;
        pendingDiagnosticsExport = null;
        if (text == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("No output stream");
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, R.string.diagnostics_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showCurrentUrl() {
        Futures.addCallback(runtime.currentUrl(graph.sessions.currentKey()), new FutureCallback<>() {
            @Override public void onSuccess(String url) { showCopyDialog(getString(R.string.current_url), url); }
            @Override public void onFailure(Throwable error) { }
        }, getMainExecutor());
    }

    private void showIntegrationToken(boolean rotated) {
        showCopyDialog(rotated ? getString(R.string.token_rotated) : getString(R.string.integration_token),
                stateStore.clientToken());
    }

    private void confirmRotateToken() {
        new AlertDialog.Builder(this).setTitle(R.string.rotate_token).setMessage(R.string.rotate_token_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    stateStore.rotateClientToken();
                    showIntegrationToken(true);
                }).show();
    }

    private void showRuleWarnings() {
        String message = graph.providerWarnings.isEmpty() ? getString(R.string.no_rule_warnings)
                : String.join("\n", graph.providerWarnings);
        new AlertDialog.Builder(this).setTitle(R.string.rule_warnings).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void showCopyDialog(String title, String value) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(value == null ? "" : value)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, value == null ? "" : value));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                }).show();
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

    private void onSessionChanged() {
        AiSessionKey key = graph.sessions.currentKey();
        ProviderConfig provider = graph.providers.require(key.providerId());
        AiAccount account = graph.accounts.require(key.accountId());
        providerButton.setText(provider.displayName());
        accountButton.setText(account.label());
        String workspaceId = graph.sessions.activeWorkspaceId();
        workspaceButton.setText(workspaceId == null ? getString(R.string.no_workspace_short)
                : graph.workspaces.require(workspaceId).label());
        stateStore.saveCurrent(key);
        stateStore.saveWorkspaceId(workspaceId);
    }
}
