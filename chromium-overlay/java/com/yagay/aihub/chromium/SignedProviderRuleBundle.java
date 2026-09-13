package com.yagay.aihub.chromium;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies signed provider-rule bundles. The envelope signs the raw decoded payload bytes, avoiding
 * ambiguous JSON canonicalization. If no public key is packaged, remote rule installation is off.
 */
public final class SignedProviderRuleBundle {
    private static final String PUBLIC_KEY_ASSET = "aihub/rules_public_key.txt";

    public record Verified(int version, List<ProviderRuleCodec.Decoded> rules, String envelope) {
        public Verified {
            rules = List.copyOf(rules);
        }
    }

    private final Context context;
    private final AiHubStateStore stateStore;

    public SignedProviderRuleBundle(Context context, AiHubStateStore stateStore) {
        this.context = context.getApplicationContext();
        this.stateStore = stateStore;
    }

    public boolean isConfigured() {
        try {
            return !readPublicKeyText().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Verified verify(String envelopeJson) throws Exception {
        if (envelopeJson == null || envelopeJson.isBlank()) {
            throw new IllegalArgumentException("Rule bundle is empty");
        }
        String keyText = readPublicKeyText().trim();
        if (keyText.isEmpty()) {
            throw new IllegalStateException("Remote provider rules are disabled: no Ed25519 public key configured");
        }

        JSONObject envelope = new JSONObject(envelopeJson);
        byte[] payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT);
        byte[] signatureBytes = Base64.decode(envelope.getString("signature"), Base64.DEFAULT);
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.decode(keyText, Base64.DEFAULT)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        if (!verifier.verify(signatureBytes)) {
            throw new SecurityException("Provider rule signature verification failed");
        }

        JSONObject payloadJson = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        int version = payloadJson.getInt("version");
        if (version < 1) throw new IllegalArgumentException("Rule bundle version must be >= 1");
        JSONArray ruleArray = payloadJson.getJSONArray("rules");
        if (ruleArray.length() == 0) throw new IllegalArgumentException("Rule bundle contains no rules");
        List<ProviderRuleCodec.Decoded> rules = new ArrayList<>();
        for (int i = 0; i < ruleArray.length(); i++) {
            rules.add(ProviderRuleCodec.decode(ruleArray.getJSONObject(i).toString()));
        }
        return new Verified(version, rules, envelopeJson);
    }

    public Verified install(String envelopeJson) throws Exception {
        Verified incoming = verify(envelopeJson);
        String currentEnvelope = stateStore.remoteRuleBundle();
        if (currentEnvelope != null && !currentEnvelope.isBlank()) {
            try {
                Verified current = verify(currentEnvelope);
                if (incoming.version() <= current.version()) {
                    throw new IllegalArgumentException(
                            "Rule bundle version " + incoming.version()
                                    + " is not newer than installed version " + current.version());
                }
            } catch (SecurityException | IllegalArgumentException error) {
                throw error;
            } catch (Exception ignored) {
                // A previously stored bundle that can no longer be verified must not block recovery.
            }
        }
        stateStore.installRemoteRuleBundle(envelopeJson);
        return incoming;
    }

    public List<ProviderRuleCodec.Decoded> loadInstalled() throws Exception {
        String envelope = stateStore.remoteRuleBundle();
        if (envelope == null || envelope.isBlank()) return List.of();
        return verify(envelope).rules();
    }

    public boolean rollback() throws Exception {
        if (!stateStore.canRollbackRemoteRuleBundle()) return false;
        if (!stateStore.rollbackRemoteRuleBundle()) return false;
        // Verify after swapping. If the previous slot is invalid, swap back before failing.
        try {
            loadInstalled();
            return true;
        } catch (Exception error) {
            stateStore.rollbackRemoteRuleBundle();
            throw error;
        }
    }

    private String readPublicKeyText() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(PUBLIC_KEY_ASSET), StandardCharsets.UTF_8))) {
            StringBuilder value = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) value.append(line.trim());
            return value.toString();
        }
    }
}
