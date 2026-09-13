package com.yagay.aihub.web;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.List;

/** Shared native file picker used by GeckoView file prompts. */
public final class GeckoFilePicker {
    public interface Callback {
        void onResult(Uri[] uris);
    }

    private final ActivityResultLauncher<Intent> launcher;
    private Callback pending;

    public GeckoFilePicker(ComponentActivity activity) {
        launcher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Callback callback = pending;
                    pending = null;
                    if (callback == null) return;
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        callback.onResult(null);
                        return;
                    }
                    callback.onResult(extractUris(result.getData()));
                });
    }

    public boolean show(String[] mimeTypes, boolean multiple, Callback callback) {
        cancel();
        pending = callback;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(resolvePrimaryType(mimeTypes));
            if (mimeTypes != null && mimeTypes.length > 1) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            }
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            launcher.launch(intent);
            return true;
        } catch (Exception error) {
            cancel();
            return false;
        }
    }

    public void cancel() {
        Callback callback = pending;
        pending = null;
        if (callback != null) callback.onResult(null);
    }

    public void destroy() {
        cancel();
    }

    private static String resolvePrimaryType(String[] mimeTypes) {
        if (mimeTypes == null || mimeTypes.length == 0) return "*/*";
        if (mimeTypes.length == 1 && mimeTypes[0] != null && !mimeTypes[0].isBlank()) return mimeTypes[0];
        return "*/*";
    }

    private static Uri[] extractUris(Intent data) {
        if (data == null) return null;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris.isEmpty() ? null : uris.toArray(new Uri[0]);
    }
}
