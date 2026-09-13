package com.yagay.aihub.chromium;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.yagay.aihub.R;
import com.yagay.aihub.core.command.AiCommand;

/**
 * The only exported Activity. It validates private automation requests and requires explicit user
 * confirmation for ordinary Android shares before forwarding them to the unexported shell.
 */
public final class AiHubEntryActivity extends AppCompatActivity {
    public static final String EXTRA_INTERNAL_DISPATCH = "com.yagay.aihub.internal.DISPATCH";

    private AiHubStateStore stateStore;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stateStore = new AiHubStateStore(this);
        route(getIntent());
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
            new AlertDialog.Builder(this)
                    .setTitle(R.string.share_confirm_title)
                    .setMessage(R.string.share_confirm_message)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish())
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> forwardToShell(source))
                    .show();
            return;
        }

        // Private Intent actions and deep links must already carry the local client token.
        AiCommand command = AiHubExternalCommandParser.parse(source, stateStore.clientToken());
        if (command == null) {
            deny();
            return;
        }
        forwardToShell(source);
    }

    private void forwardToShell(@Nullable Intent source) {
        Intent target = source == null ? new Intent() : new Intent(source);
        target.setClass(this, AiHubShellActivity.class);
        target.putExtra(EXTRA_INTERNAL_DISPATCH, source != null);
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target);
        finish();
    }

    private void deny() {
        Toast.makeText(this, R.string.external_request_denied, Toast.LENGTH_LONG).show();
        finish();
    }
}
