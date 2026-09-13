package com.yagay.aihub.chromium;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import com.yagay.aihub.api.AiHubContract;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandType;

import java.util.ArrayList;
import java.util.List;

/** Converts Android intents/deep links/share intents into the same AiCommand used by the app UI. */
public final class AiHubExternalCommandParser {
    private AiHubExternalCommandParser() {}

    public static AiCommand parse(Intent intent, String expectedClientToken) {
        if (intent == null) return null;
        String action = intent.getAction();

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<String> streams = collectSharedUris(intent);
            if (!streams.isEmpty()) return AiCommand.attach(streams);
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.toString().isBlank()) return AiCommand.send(shared.toString());
            return null;
        }

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return parseDeepLink(intent.getData(), expectedClientToken);
        }

        if (isPrivateAction(action) && !tokenMatches(
                intent.getStringExtra(AiHubContract.EXTRA_CLIENT_TOKEN), expectedClientToken)) {
            return null;
        }

        String provider = intent.getStringExtra(AiHubContract.EXTRA_PROVIDER_ID);
        String account = intent.getStringExtra(AiHubContract.EXTRA_ACCOUNT_ID);
        String workspace = intent.getStringExtra(AiHubContract.EXTRA_WORKSPACE_ID);

        if (AiHubContract.ACTION_SWITCH.equals(action)) {
            return AiCommand.switchTo(provider, account, workspace);
        }
        if (AiHubContract.ACTION_SEND_TEXT.equals(action)) {
            return AiCommand.sendTo(
                    provider,
                    account,
                    workspace,
                    intent.getStringExtra(AiHubContract.EXTRA_TEXT));
        }
        if (AiHubContract.ACTION_ATTACH.equals(action)) {
            ArrayList<String> uris = intent.getStringArrayListExtra(AiHubContract.EXTRA_URI_LIST);
            return AiCommand.attach(uris == null ? List.of() : uris);
        }
        if (AiHubContract.ACTION_NEW_CHAT.equals(action)) return AiCommand.simple(AiCommandType.NEW_CHAT);
        if (AiHubContract.ACTION_STOP.equals(action)) return AiCommand.simple(AiCommandType.STOP);
        if (AiHubContract.ACTION_BACK.equals(action)) return AiCommand.simple(AiCommandType.BACK);
        if (AiHubContract.ACTION_FORWARD.equals(action)) return AiCommand.simple(AiCommandType.FORWARD);
        if (AiHubContract.ACTION_RELOAD.equals(action)) return AiCommand.simple(AiCommandType.RELOAD);
        if (AiHubContract.ACTION_NEXT_PROVIDER.equals(action)) return AiCommand.simple(AiCommandType.NEXT_PROVIDER);
        if (AiHubContract.ACTION_PREVIOUS_PROVIDER.equals(action)) return AiCommand.simple(AiCommandType.PREVIOUS_PROVIDER);
        return null;
    }

    private static AiCommand parseDeepLink(Uri uri, String expectedClientToken) {
        if (!AiHubContract.DEEP_LINK_SCHEME.equals(uri.getScheme())) return null;
        if (!tokenMatches(uri.getQueryParameter("token"), expectedClientToken)) return null;

        String command = uri.getHost();
        if (command == null && !uri.getPathSegments().isEmpty()) command = uri.getPathSegments().get(0);
        String provider = uri.getQueryParameter("provider");
        String account = uri.getQueryParameter("account");
        String workspace = uri.getQueryParameter("workspace");

        if ("send".equals(command)) {
            return AiCommand.sendTo(provider, account, workspace, uri.getQueryParameter("text"));
        }
        if ("switch".equals(command)) return AiCommand.switchTo(provider, account, workspace);
        if ("new-chat".equals(command)) return AiCommand.simple(AiCommandType.NEW_CHAT);
        if ("stop".equals(command)) return AiCommand.simple(AiCommandType.STOP);
        if ("attach".equals(command)) return AiCommand.attach(List.of());
        if ("back".equals(command)) return AiCommand.simple(AiCommandType.BACK);
        if ("forward".equals(command)) return AiCommand.simple(AiCommandType.FORWARD);
        if ("reload".equals(command)) return AiCommand.simple(AiCommandType.RELOAD);
        if ("next".equals(command)) return AiCommand.simple(AiCommandType.NEXT_PROVIDER);
        if ("previous".equals(command)) return AiCommand.simple(AiCommandType.PREVIOUS_PROVIDER);
        return null;
    }

    private static List<String> collectSharedUris(Intent intent) {
        List<String> out = new ArrayList<>();
        ArrayList<Uri> multiple = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (multiple != null) {
            for (Uri uri : multiple) if (uri != null) out.add(uri.toString());
        }
        Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (single != null && !out.contains(single.toString())) out.add(single.toString());
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !out.contains(uri.toString())) out.add(uri.toString());
            }
        }
        return out;
    }

    private static boolean isPrivateAction(String action) {
        return AiHubContract.ACTION_SWITCH.equals(action)
                || AiHubContract.ACTION_SEND_TEXT.equals(action)
                || AiHubContract.ACTION_NEW_CHAT.equals(action)
                || AiHubContract.ACTION_STOP.equals(action)
                || AiHubContract.ACTION_ATTACH.equals(action)
                || AiHubContract.ACTION_BACK.equals(action)
                || AiHubContract.ACTION_FORWARD.equals(action)
                || AiHubContract.ACTION_RELOAD.equals(action)
                || AiHubContract.ACTION_NEXT_PROVIDER.equals(action)
                || AiHubContract.ACTION_PREVIOUS_PROVIDER.equals(action);
    }

    private static boolean tokenMatches(String supplied, String expected) {
        return expected != null && !expected.isBlank() && expected.equals(supplied);
    }
}
