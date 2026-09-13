package com.yagay.aihub.android;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads build-generated provider data without making the stable Android layer depend on generated
 * sources at compile time. scripts/apply_chrome_overlay.py generates
 * com.yagay.aihub.generated.AiHubGeneratedRules inside the Chromium checkout.
 */
final class AiHubEmbeddedRules {
    private static final String GENERATED = "com.yagay.aihub.generated.AiHubGeneratedRules";

    private AiHubEmbeddedRules() {}

    static List<String> builtinRules() {
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

    static String rulesPublicKey() {
        try {
            Class<?> type = Class.forName(GENERATED);
            Method method = type.getMethod("rulesPublicKey");
            Object value = method.invoke(null);
            return value instanceof String text ? text.trim() : "";
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }
}
