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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.yagay.aihub.R;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.CommandResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** AI-first shell: one retained Chromium/WebEngine session per provider, no account/workspace layer. */
public final class AiHubShellActivity extends AppCompatActivity {
    private static final int REQUEST_ATTACHMENTS = 4107;
    private static final int REQUEST_EXPORT_DIAGNOSTICS = 4108;
    private static final int REQUEST_IMPORT_SIGNED_RULES = 4109;
    private static final int MAX_RULE_BUNDLE_BYTES = 2 * 1024 * 1024;

    private AiHubStateStore stateStore;
    private AiHubBootstrap.Graph graph;
    private WebEngineSessionRuntime runtime;
    private LinearLayout providerStrip;
    private TextView currentAiTitle;
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
        activateInitialProvider();
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
            return;
        }
        if (requestCode == REQUEST_IMPORT_SIGNED_RULES) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importSignedRules(data.getData());
        }
    }

    private void bindViews() {
        providerStrip = findViewById(R.id.provider_strip);
        currentAiTitle = findViewById(R.id.current_ai_title);
        composer = findViewById(R.id.composer);

        findViewById(R.id.browser_back).setOnClickListener(v -> graph.sessions.back());
        findViewById(R.id.browser_forward).setOnClickListener(v -> graph.sessions.forward());
        findViewById(R.id.browser_reload).setOnClickListener(v -> graph.sessions.reload());
        findViewById(R.id.new_chat_button).setOnClickListener(v -> graph.sessions.newChat());
        findViewById(R.id.attach_button).setOnClickListener(v -> openAttachmentPicker());
        findViewById(R.id.stop_button).setOnClickListener(v -> graph.sessions.stop());
        findViewById(R.id.send_button).setOnClickListener(v -> sendComposer());
        findViewById(R.id.menu_button).setOnClickListener(v -> showToolsMenu());

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

    private void activateInitialProvider() {
        if (graph.providers.all().isEmpty()) {
            throw new IllegalStateException("No AI providers are available");
        }
        String saved = stateStore.savedProviderId();
        try {
            if (saved != null && graph.providers.contains(saved)) graph.sessions.activate(saved);
            else graph.sessions.activate(graph.providers.all().get(0).id());
        } catch (RuntimeException error) {
            graph.sessions.activate(graph.providers.all().get(0).id());
        }
        onSessionChanged();
    }

    private void switchProvider(String providerId) {
        try {
            graph.sessions.switchProvider(providerId);
            onSessionChanged();
        } catch (RuntimeException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onSessionChanged() {
        stateStore.saveCurrent(graph.sessions.currentKey());
        ProviderConfig current = graph.providers.require(graph.sessions.currentKey().providerId());
        currentAiTitle.setText(current.displayName());
        rebuildProviderStrip();
    }

    private void rebuildProviderStrip() {
        providerStrip.removeAllViews();
        String active = graph.sessions.currentKey().providerId();
        for (ProviderConfig provider : graph.providers.all()) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setText((provider.id().equals(active) ? "● " : "") + provider.displayName());
            button.setContentDescription(getString(R.string.switch_to_ai, provider.displayName()));
            button.setOnClickListener(v -> switchProvider(provider.id()));
            providerStrip.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        Button add = new Button(this);
        add.setAllCaps(false);
        add.setText(R.string.add_ai_short);
        add.setOnClickListener(v -> showAddProviderDialog());
        providerStrip.addView(add, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
                                graph.providers,
                                stateStore,
                                name.getText().toString(),
                                url.getText().toString());
                        switchProvider(provider.id());
                    } catch (RuntimeException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void sendComposer() {
        String text = composer.getText().toString();
        if (text.isBlank()) return;
        graph.sessions.sendText(text);
        composer.setText("");
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
        } catch (SecurityException ignored) {}
        out.add(uri.toString());
    }

    private void handleExternalIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(AiHubEntryActivity.EXTRA_INTERNAL_DISPATCH, false)) return;
        intent.removeExtra(AiHubEntryActivity.EXTRA_INTERNAL_DISPATCH);
        AiCommand command = AiHubExternalCommandParser.parse(intent, stateStore.clientToken());
        if (command == null) return;
        CommandResult result = graph.commands.execute(command);
        if (!result.success()) {
            Toast.makeText(this, result.message(), Toast.LENGTH_LONG).show();
            return;
        }
        onSessionChanged();
    }

    private void showToolsMenu() {
        String[] items = {
                getString(R.string.current_url),
                getString(R.string.provider_diagnostics),
                getString(R.string.export_diagnostics),
                getString(R.string.import_signed_rules),
                getString(R.string.rollback_rules),
                getString(R.string.integration_token),
                getString(R.string.rotate_token),
                getString(R.string.rule_warnings)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.tools)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0 -> showCurrentUrl();
                        case 1 -> showProviderDiagnostics();
                        case 2 -> exportDiagnostics();
                        case 3 -> openSignedRulePicker();
                        case 4 -> rollbackRules();
                        case 5 -> showToken(stateStore.clientToken());
                        case 6 -> confirmRotateToken();
                        case 7 -> showRuleWarnings();
                        default -> { }
                    }
                }).show();
    }

    private void showCurrentUrl() {
        Futures.addCallback(runtime.currentUrl(graph.sessions.currentKey()), new FutureCallback<>() {
            @Override public void onSuccess(String url) { showCopyDialog(getString(R.string.current_url), url); }
            @Override public void onFailure(Throwable error) { showError(error); }
        }, getMainExecutor());
    }

    private void showProviderDiagnostics() {
        Futures.addCallback(runtime.probe(graph.sessions.currentKey()), new FutureCallback<>() {
            @Override public void onSuccess(String value) {
                new AlertDialog.Builder(AiHubShellActivity.this)
                        .setTitle(R.string.provider_diagnostics)
                        .setMessage(value == null ? "" : value)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
            @Override public void onFailure(Throwable error) { showError(error); }
        }, getMainExecutor());
    }

    private void exportDiagnostics() {
        Futures.addCallback(AiHubDiagnostics.snapshot(graph, runtime), new FutureCallback<>() {
            @Override public void onSuccess(String json) {
                pendingDiagnosticsExport = json;
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "aihub-diagnostics.json");
                startActivityForResult(create, REQUEST_EXPORT_DIAGNOSTICS);
            }
            @Override public void onFailure(Throwable error) { showError(error); }
        }, getMainExecutor());
    }

    private void writeDiagnostics(Uri uri) {
        String value = pendingDiagnosticsExport;
        pendingDiagnosticsExport = null;
        if (value == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Cannot open output");
            output.write(value.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, R.string.diagnostics_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openSignedRulePicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(picker, REQUEST_IMPORT_SIGNED_RULES);
    }

    private void importSignedRules(Uri uri) {
        try {
            String envelope = readUtf8(uri, MAX_RULE_BUNDLE_BYTES);
            SignedProviderRuleBundle.Verified verified = new SignedProviderRuleBundle(this, stateStore).install(envelope);
            reloadProviderGraph();
            Toast.makeText(this, getString(R.string.rules_installed, verified.version()), Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.rules_update_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void rollbackRules() {
        try {
            if (!new SignedProviderRuleBundle(this, stateStore).rollback()) {
                Toast.makeText(this, R.string.no_rules_rollback, Toast.LENGTH_LONG).show();
                return;
            }
            reloadProviderGraph();
            Toast.makeText(this, R.string.rules_rolled_back, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.rules_update_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void reloadProviderGraph() {
        String preferred = graph.sessions.currentKey().providerId();
        graph = AiHubBootstrap.create(this, stateStore, runtime);
        if (graph.providers.contains(preferred)) graph.sessions.activate(preferred);
        else graph.sessions.activate(graph.providers.all().get(0).id());
        onSessionChanged();
    }

    private void showRuleWarnings() {
        String message = graph.providerWarnings.isEmpty()
                ? getString(R.string.no_rule_warnings)
                : String.join("\n\n", graph.providerWarnings);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rule_warnings)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showToken(String token) {
        showCopyDialog(getString(R.string.integration_token), token);
    }

    private void confirmRotateToken() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.rotate_token)
                .setMessage(R.string.rotate_token_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        showCopyDialog(getString(R.string.token_rotated), stateStore.rotateClientToken()))
                .show();
    }

    private void showCopyDialog(String title, String value) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(value == null ? "" : value)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, value));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                }).show();
    }

    private String readUtf8(Uri uri, int maxBytes) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Cannot open selected file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] block = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(block)) != -1) {
                total += read;
                if (total > maxBytes) throw new IllegalArgumentException("Selected file is too large");
                out.write(block, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void showError(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? getString(R.string.operation_failed)
                : error.getMessage();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
