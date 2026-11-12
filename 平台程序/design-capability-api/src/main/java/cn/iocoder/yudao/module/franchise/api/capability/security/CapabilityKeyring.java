package cn.iocoder.yudao.module.franchise.api.capability.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dedicated service keys. No Runtime key lookup, default key or caller fallback. */
public final class CapabilityKeyring {
    private final Map<String, Key> keys;

    public CapabilityKeyring(Map<String, Key> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Capability keyring must not be empty");
        }
        keys.keySet().forEach(id -> requireToken(id, 64));
        this.keys = Map.copyOf(keys);
    }

    public byte[] require(String keyId, String callerAppId, String operation) {
        Key key = keyId == null ? null : keys.get(keyId);
        if (key == null || !key.callerAppId.equals(callerAppId) || !key.operations.contains(operation)) {
            throw new SecurityException("Capability key, caller or operation denied");
        }
        return key.secret.clone();
    }

    public static void requireToken(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid capability identifier");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
                    && c != '-' && c != '_' && c != '.') {
                throw new IllegalArgumentException("Invalid capability identifier");
            }
        }
    }

    public static final class Key {
        private final String callerAppId;
        private final byte[] secret;
        private final Set<String> operations;

        /** Secret is at least 32 securely generated random bytes, decoded from deployment configuration. */
        public Key(String callerAppId, byte[] secret, Set<String> operations) {
            requireToken(callerAppId, 80);
            if (secret == null || secret.length < 32 || secret.length > 128) {
                throw new IllegalArgumentException("Capability key must contain 32 to 128 random bytes");
            }
            if (Objects.requireNonNull(operations, "operations").isEmpty()) {
                throw new IllegalArgumentException("Capability key requires explicit operations");
            }
            operations.forEach(operation -> requireToken(operation, 64));
            this.callerAppId = callerAppId;
            this.secret = secret.clone();
            this.operations = Set.copyOf(operations);
        }
    }
}
