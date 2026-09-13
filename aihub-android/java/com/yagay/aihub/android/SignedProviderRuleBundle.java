package com.yagay.aihub.android;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/** Verifies Ed25519-signed provider-rule bundles against the build-embedded public key. */
public final class SignedProviderRuleBundle {
    public record Verified(int version, List<ProviderRuleCodec.Decoded> rules, String envelope) {
        public Verified {
            rules = List.copyOf(rules);
        }
    }

    private final AiHubStateStore stateStore;

    public SignedProviderRuleBundle(AiHubStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public boolean isConfigured() {
        return !AiHubEmbeddedRules.rulesPublicKey().isBlank();
    }

    public Verified verify(String envelopeJson) throws Exception {
        if (envelopeJson == null || envelopeJson.isBlank()) {
            throw new IllegalArgumentException("Rule bundle is empty");
        }
        String keyText = AiHubEmbeddedRules.rulesPublicKey();
        if (keyText.isBlank()) {
            throw new IllegalStateException(
                    "Remote provider rules are disabled: no Ed25519 public key configured");
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
        int currentVersion = 0;
        String currentEnvelope = stateStore.remoteRuleBundle();
        if (currentEnvelope != null && !currentEnvelope.isBlank()) {
            try {
                currentVersion = verify(currentEnvelope).version();
            } catch (Exception ignored) {
                // Corrupt/old-key state may be replaced by a newly verified bundle.
            }
        }
        if (currentVersion > 0 && incoming.version() <= currentVersion) {
            throw new IllegalArgumentException(
                    "Rule bundle version " + incoming.version()
                            + " is not newer than installed version " + currentVersion);
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
        try {
            loadInstalled();
            return true;
        } catch (Exception error) {
            stateStore.rollbackRemoteRuleBundle();
            throw error;
        }
    }
}
