package com.yagay.aihub.chromium;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.yagay.aihub.R;
import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiWorkspace;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandType;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.util.List;

/**
 * The only exported Activity. Private automation is token-gated; ordinary shares and deep links
 * require explicit user confirmation before they can reach the unexported shell.
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

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            AiCommand command = AiHubExternalCommandParser.parse(source, stateStore.clientToken());
            if (command == null) {
                deny();
                return;
            }
            confirmAndForward(source, R.string.share_confirm_title, command);
            return;
        }

        if (Intent.ACTION_VIEW.equals(action)) {
            AiCommand command = AiHubExternalCommandParser.parse(source, stateStore.clientToken());
            if (command == null) {
                deny();
                return;
            }
            confirmAndForward(source, R.string.link_confirm_title, command);
            return;
        }

        // Unattended custom Intent actions must carry the local client token in an Intent extra.
        AiCommand command = AiHubExternalCommandParser.parse(source, stateStore.clientToken());
        if (command == null) {
            deny();
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

    /** Builds a non-secret preview using the same target priority as AiCommandBus. */
    private String buildConfirmationDetails(AiCommand command) {
        ProviderRegistry providers = new ProviderRuleLoader(this, stateStore).load();
        List<AiAccount> accounts = stateStore.loadAccounts();
        List<AiWorkspace> workspaces = stateStore.loadWorkspaces();

        String providerId = clean(command.providerId());
        String accountId = clean(command.accountId());
        String workspaceId = clean(command.workspaceId());

        if (accountId != null) {
            AiAccount explicit = findAccount(accounts, accountId);
            if (explicit != null) providerId = explicit.providerId();
            workspaceId = null;
        } else if (workspaceId != null) {
            if (providerId == null) providerId = clean(stateStore.savedProviderId());
            accountId = accountForWorkspace(workspaces, workspaceId, providerId);
        } else if (providerId != null) {
            // Provider-only commands preserve the current workspace.
            workspaceId = clean(stateStore.savedWorkspaceId());
            if (workspaceId != null) {
                accountId = accountForWorkspace(workspaces, workspaceId, providerId);
            } else if (providerId.equals(clean(stateStore.savedProviderId()))) {
                accountId = clean(stateStore.savedAccountId());
            }
        } else {
            providerId = clean(stateStore.savedProviderId());
            accountId = clean(stateStore.savedAccountId());
            workspaceId = clean(stateStore.savedWorkspaceId());
        }

        if (providerId == null && !providers.all().isEmpty()) {
            providerId = providers.all().get(0).id();
        }
        if (accountId == null && providerId != null) {
            for (AiAccount candidate : accounts) {
                if (providerId.equals(candidate.providerId())) {
                    accountId = candidate.id();
                    break;
                }
            }
        }

        String providerLabel = providerLabel(providers, providerId);
        String accountLabel = accountLabel(accounts, accountId);
        String workspaceLabel = workspaceLabel(workspaces, workspaceId);

        if (command.type() == AiCommandType.NEXT_PROVIDER
                || command.type() == AiCommandType.PREVIOUS_PROVIDER) {
            providerLabel = getString(R.string.external_target_automatic);
            accountLabel = getString(R.string.external_target_automatic);
        }

        return getString(
                R.string.external_confirm_details,
                actionLabel(command.type()),
                providerLabel,
                accountLabel,
                workspaceLabel);
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

    private String providerLabel(ProviderRegistry providers, String providerId) {
        if (providerId == null) return getString(R.string.external_target_automatic);
        try {
            ProviderConfig provider = providers.require(providerId);
            return provider.displayName();
        } catch (RuntimeException ignored) {
            return providerId;
        }
    }

    private String accountLabel(List<AiAccount> accounts, String accountId) {
        if (accountId == null) return getString(R.string.external_target_automatic);
        AiAccount account = findAccount(accounts, accountId);
        return account == null ? accountId : account.label();
    }

    private String workspaceLabel(List<AiWorkspace> workspaces, String workspaceId) {
        if (workspaceId == null) return getString(R.string.external_target_none);
        for (AiWorkspace workspace : workspaces) {
            if (workspace.id().equals(workspaceId)) return workspace.label();
        }
        return workspaceId;
    }

    private static AiAccount findAccount(List<AiAccount> accounts, String accountId) {
        if (accountId == null) return null;
        for (AiAccount account : accounts) {
            if (account.id().equals(accountId)) return account;
        }
        return null;
    }

    private static String accountForWorkspace(
            List<AiWorkspace> workspaces,
            String workspaceId,
            String providerId) {
        if (workspaceId == null || providerId == null) return null;
        for (AiWorkspace workspace : workspaces) {
            if (workspace.id().equals(workspaceId)) return clean(workspace.accountFor(providerId));
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
