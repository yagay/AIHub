package com.yagay.aihub.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class ChromeTargetManager {
    public record Target(String id, String url, String webSocketUrl) {}

    private static final String BASE = "http://127.0.0.1:" + RootCdpBridge.PORT;
    private static final MediaType OCTET = MediaType.get("application/octet-stream");

    private final Context context;
    private final OkHttpClient http;

    public ChromeTargetManager(Context context, OkHttpClient http) {
        this.context = context.getApplicationContext();
        this.http = http;
    }

    public Target openTarget(BrowserProvider provider) throws Exception {
        Target fresh = createTarget(provider.homeUrl());
        if (fresh != null) return fresh;

        openExternal(provider.homeUrl());
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            for (Target target : listTargets()) {
                if (provider.ownsUrl(target.url()) && target.webSocketUrl() != null) return target;
            }
            Thread.sleep(250);
        }
        throw new BrowserStateException(
                "target_unavailable",
                "Could not attach to " + provider.name() + " in Chrome.");
    }

    public void closeTarget(Target target) {
        if (target == null || target.id() == null) return;
        Request request = new Request.Builder().url(BASE + "/json/close/" + target.id()).get().build();
        try (Response ignored = http.newCall(request).execute()) {
        } catch (Exception ignored) {}
    }

    private Target createTarget(String url) {
        String encoded = Uri.encode(url);
        Request request = new Request.Builder()
                .url(BASE + "/json/new?" + encoded)
                .put(RequestBody.create(new byte[0], OCTET))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            return target(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Target> listTargets() throws Exception {
        Request request = new Request.Builder().url(BASE + "/json/list").get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return List.of();
            JSONArray array = new JSONArray(response.body().string());
            List<Target> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (!"page".equals(item.optString("type"))) continue;
                Target target = target(item);
                if (target.webSocketUrl() != null) result.add(target);
            }
            return result;
        }
    }

    private static Target target(JSONObject item) {
        String ws = item.optString("webSocketDebuggerUrl", null);
        return new Target(item.optString("id"), item.optString("url"), ws);
    }

    private void openExternal(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }
}
