package com.yagay.aihub.chromium;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.yagay.aihub.R;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandType;
import com.yagay.aihub.core.provider.ProviderRegistry;

/**
 * The only exported Activity. Token-gated automation can pass through immediately; ordinary shares
 * and deep links require explicit user confirmation before reaching the unexported shell.
 */
public final class AiHubEntryActivity extends AppCompatActivity {
    public static final String EXTRA_INTERNAL_DISPATCH = "com.yagay.aihub.internal.DISPATCH";

    private AiHubStateStore stateStore;
    private AlertDialog confirmationDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stateStore = new AiHubStateStore(this);
        route(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dismissConfirmation();
        route(intent);
    }

    @Override
    protected void onDestroy() {
        dismissConfirmation();
        super.onDestroy();
    }

    private void route(Intent source) {
        String action = source == null ? null : source.getAction();
        if (Intent.ACTION_MAIN.equals(action) || action == null) {
            forwardToShell(null);
            return;
        }

        AiCommand command = AiHubExternalCommandParser.parse(source, stateStore.clientToken());
        if (command == null) {
            deny();
            return;
        }

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            confirmAndForward(source, R.string.share_confirm_title, command);
            return;
        }
        if (Intent.ACTION_VIEW.equals(action)) {
            confirmAndForward(source, R.string.link_confirm_title, command);
            return;
        }

        forwardToShell(source);
    }

    private void confirmAndForward(Intent source, int title, AiCommand command) {
        confirmationDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(buildConfirmationDetails(command))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> forwardToShell(source))
                .create();
        confirmationDialog.show();
    }

    private String buildConfirmationDetails(AiCommand command) {
        ProviderRegistry providers = new ProviderRuleLoader(this, stateStore).load();
        String providerId = command.providerId();
        if (providerId == null || providerId.isBlank()) providerId = stateStore.savedProviderId();

        String providerLabel = getString(R.string.external_target_current);
        if (command.type() == AiCommandType.NEXT_PROVIDER || command.type() == AiCommandType.PREVIOUS_PROVIDER) {
            providerLabel = getString(R.string.external_target_automatic);
        } else if (providerId != null && !providerId.isBlank()) {
            try {
                ProviderConfig provider = providers.require(providerId);
                providerLabel = provider.displayName();
            } catch (RuntimeException ignored) {
                providerLabel = providerId;
            }
        }

        return getString(
                R.string.external_confirm_details,
                actionLabel(command.type()),
                providerLabel);
    }

    private String actionLabel(AiCommandType type) {
        return switch (type) {
            case SWITCH -> getString(R.string.external_action_switch);
            case SEND_TEXT -> getString(R.string.external_action_send);
            case ATTACH -> getString(R.string.external_action_attach);
            case ATTACH_AND_SEND -> getString(R.string.external_action_attach_send);
            case NEW_CHAT -> getString(R.string.external_action_new_chat);
            case STOP -> getString(R.string.external_action_stop);
            case BACK -> getString(R.string.external_action_back);
            case FORWARD -> getString(R.string.external_action_forward);
            case RELOAD -> getString(R.string.external_action_reload);
            case NEXT_PROVIDER -> getString(R.string.external_action_next);
            case PREVIOUS_PROVIDER -> getString(R.string.external_action_previous);
        };
    }

    private void forwardToShell(@Nullable Intent source) {
        dismissConfirmation();
        Intent target = source == null ? new Intent() : new Intent(source);
        target.setClass(this, AiHubShellActivity.class);
        target.putExtra(EXTRA_INTERNAL_DISPATCH, source != null);
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target);
        finish();
    }

    private void deny() {
        dismissConfirmation();
        Toast.makeText(this, R.string.external_request_denied, Toast.LENGTH_LONG).show();
        finish();
    }

    private void dismissConfirmation() {
        if (confirmationDialog != null) {
            confirmationDialog.dismiss();
            confirmationDialog = null;
        }
    }
}
