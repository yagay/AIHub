package com.yagay.aihub.web;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.List;

/** Owns the single native file-picker pipeline shared by every retained WebView. */
public final class FileChooserCoordinator {
    private final ActivityResultLauncher<Intent> launcher;
    private ValueCallback<Uri[]> pending;

    public FileChooserCoordinator(ComponentActivity activity) {
        launcher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    ValueCallback<Uri[]> callback = pending;
                    pending = null;
                    if (callback == null) return;
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        callback.onReceiveValue(null);
                        return;
                    }
                    callback.onReceiveValue(extractUris(result.getData()));
                });
    }

    public boolean show(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        cancel();
        pending = callback;
        try {
            Intent intent = params.createIntent();
            if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            launcher.launch(intent);
            return true;
        } catch (Exception error) {
            cancel();
            return false;
        }
    }

    public void cancel() {
        ValueCallback<Uri[]> callback = pending;
        pending = null;
        if (callback != null) callback.onReceiveValue(null);
    }

    public void destroy() {
        cancel();
    }

    private static Uri[] extractUris(Intent data) {
        if (data == null) return null;
        List<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris.isEmpty() ? null : uris.toArray(new Uri[0]);
    }
}
