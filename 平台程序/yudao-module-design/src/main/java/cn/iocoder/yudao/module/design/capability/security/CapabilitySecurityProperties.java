package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilityKeyring;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("zhongshu.design.capability")
public class CapabilitySecurityProperties {
    private boolean enabled;
    private long replayWindowSeconds = 300;
    private long clockSkewSeconds = 5;
    private int maxBodyBytes = 262144;
    private Map<String, KeyProperties> keys = new LinkedHashMap<>();

    public CapabilityKeyring keyring() {
        Map<String, CapabilityKeyring.Key> decoded = new LinkedHashMap<>();
        keys.forEach((keyId, key) -> decoded.put(keyId, new CapabilityKeyring.Key(key.getCallerAppId(),
                Base64.getDecoder().decode(key.getSecretBase64()),
                key.getOperations().stream().map(Operation::name).collect(Collectors.toSet()))));
        return new CapabilityKeyring(decoded);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getReplayWindowSeconds() { return replayWindowSeconds; }
    public void setReplayWindowSeconds(long value) { replayWindowSeconds = value; }
    public long getClockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(long value) { clockSkewSeconds = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { maxBodyBytes = value; }
    public Map<String, KeyProperties> getKeys() { return keys; }
    public void setKeys(Map<String, KeyProperties> keys) { this.keys = keys; }

    public static class KeyProperties {
        private String callerAppId;
        private String secretBase64;
        private Set<Operation> operations = Set.of();

        public String getCallerAppId() { return callerAppId; }
        public void setCallerAppId(String value) { callerAppId = value; }
        public String getSecretBase64() { return secretBase64; }
        public void setSecretBase64(String value) { secretBase64 = value; }
        public Set<Operation> getOperations() { return operations; }
        public void setOperations(Set<Operation> value) { operations = value; }
    }
}
