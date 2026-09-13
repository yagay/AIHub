package com.yagay.aihub.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads provider data from either a build-generated Chromium class or normal Android assets.
 *
 * The asset path is the normal WebView-app path. Reflection remains only so the archived Chromium
 * integration can still compile from the same stable Android layer if needed.
 */
final class AiHubEmbeddedRules {
    private static final String GENERATED = "com.yagay.aihub.generated.AiHubGeneratedRules";
    private static final String PROVIDER_INDEX = "aihub/providers/index.txt";
    private static final String PUBLIC_KEY = "aihub/rules_public_key.txt";

    private AiHubEmbeddedRules() {}

    static List<String> builtinRules(Context context) {
        List<String> generated = generatedRules();
        if (!generated.isEmpty()) return generated;

        List<String> out = new ArrayList<>();
        try (BufferedReader index = new BufferedReader(new InputStreamReader(
                context.getAssets().open(PROVIDER_INDEX), StandardCharsets.UTF_8))) {
            String name;
            while ((name = index.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                out.add(readAsset(context, "aihub/providers/" + name));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }

    static String rulesPublicKey(Context context) {
        String generated = generatedPublicKey();
        if (!generated.isBlank()) return generated;
        try {
            return readAsset(context, PUBLIC_KEY).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> generatedRules() {
        try {
            Class<?> type = Class.forName(GENERATED);
            Method method = type.getMethod("builtinRules");
            Object value = method.invoke(null);
            if (!(value instanceof List<?> list)) return List.of();
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) out.add(text);
            }
            return List.copyOf(out);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static String generatedPublicKey() {
        try {
            Class<?> type = Class.forName(GENERATED);
            Method method = type.getMethod("rulesPublicKey");
            Object value = method.invoke(null);
            return value instanceof String text ? text.trim() : "";
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }

    private static String readAsset(Context context, String path) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(path), StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) out.append(buffer, 0, read);
            return out.toString();
        }
    }
}
