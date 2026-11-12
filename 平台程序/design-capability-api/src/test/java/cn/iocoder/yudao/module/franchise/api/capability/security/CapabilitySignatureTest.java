package cn.iocoder.yudao.module.franchise.api.capability.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilitySignatureTest {
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void preservesRuntimeGoldenVectorWithoutSharingItsKeyConfiguration() {
        assertEquals("363c3ff8e9d0ecf0f2626b7a4c54b5f334a3cc428094c5a26ce381a0df1dd454",
                CapabilitySignature.sign(bytes("golden-secret-0123456789abcdef"), "1760000000", "POST",
                        "/internal/note-polish", bytes("{\"note\":\"老人房在一楼\",\"line\":\"FLAT\"}")));
    }

    @Test
    void capabilityEnvelopeGoldenVectorMatchesIndependentPythonHmac() {
        byte[] body = bytes("{\"callerAppId\":\"platform-b\",\"orgId\":42,\"actor\":{\"actorUserId\":7,\"actorPostId\":9,\"actorRoleCode\":\"OWNER\"},\"operation\":\"LIST_PROJECTS\",\"requestKey\":\"request-1\",\"nonce\":\"nonce-000000000001\",\"payload\":{\"cursor\":null,\"limit\":10}}");
        assertEquals("21632676f4be8d1a521532271ee20ff26345fc17149a433910fb2b207c4d5d3a",
                CapabilitySignature.sign(bytes("test-capability-key-0123456789abcdef"), "1760000000", "POST",
                        "/internal-api/design/v1/platform/list-projects", body));
    }

    @Test
    void signsExactBytesAndRejectsEveryChangedSignedComponent() {
        byte[] key = bytes("test-capability-key-0123456789abcdef");
        String path = "/internal-api/design/v1/platform/project-create";
        byte[] body = bytes("{\"callerAppId\":\"platform-b\",\"orgId\":42,\"actor\":{\"actorUserId\":7,\"actorPostId\":9,\"actorRoleCode\":\"OWNER\"},\"operation\":\"PROJECT_CREATE\",\"requestKey\":\"request-1\",\"nonce\":\"nonce-000000000001\",\"payload\":{\"name\":\"老人房\"}}");
        String signature = CapabilitySignature.sign(key, "1760000000", "POST", path, body);
        assertTrue(CapabilitySignature.verify(key, "1760000000", "POST", path, body, signature));
        assertFalse(CapabilitySignature.verify(key, "1760000001", "POST", path, body, signature));
        assertFalse(CapabilitySignature.verify(key, "1760000000", "GET", path, body, signature));
        assertFalse(CapabilitySignature.verify(key, "1760000000", "POST", path + "/", body, signature));
        assertFalse(CapabilitySignature.verify(key, "1760000000", "POST", path, bytes(new String(body, StandardCharsets.UTF_8) + " "), signature));
        assertFalse(CapabilitySignature.verify(bytes("runtime-key-is-a-separate-secret-123"), "1760000000", "POST", path, body, signature));
        assertFalse(CapabilitySignature.verify(key, "1760000000", "POST", path, body, "00"));
        assertFalse(CapabilitySignature.verify(key, "1760000000", "POST", path, body, null));
    }

    @Test
    void keyRotationIsCallerBoundAndUnknownKeysNeverFallBack() {
        byte[] oldKey = bytes("test-old-key-0123456789abcdef012345");
        byte[] newKey = bytes("test-new-key-0123456789abcdef012345");
        CapabilityKeyring keys = new CapabilityKeyring(Map.of(
                "old", new CapabilityKeyring.Key("platform-b", oldKey, Set.of("PROJECT_CREATE")),
                "new", new CapabilityKeyring.Key("platform-b", newKey, Set.of("PROJECT_CREATE"))));
        assertArrayEquals(oldKey, keys.require("old", "platform-b", "PROJECT_CREATE"));
        assertArrayEquals(newKey, keys.require("new", "platform-b", "PROJECT_CREATE"));
        assertThrows(SecurityException.class, () -> keys.require("runtime", "platform-b", "PROJECT_CREATE"));
        assertThrows(SecurityException.class, () -> keys.require("old", "other", "PROJECT_CREATE"));
        assertThrows(SecurityException.class, () -> keys.require("old", "platform-b", "JOB_CANCEL"));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityKeyring.Key("platform-b", bytes("short"), Set.of("PROJECT_CREATE")));
        oldKey[0] = 0;
        byte[] exported = keys.require("old", "platform-b", "PROJECT_CREATE");
        assertNotEquals(0, exported[0]);
        exported[0] = 0;
        assertNotEquals(0, keys.require("old", "platform-b", "PROJECT_CREATE")[0]);
    }

    @Test
    void windowIncludesBothBoundariesAndTtlCoversSkewAndSecondRounding() {
        ReplayWindow window = new ReplayWindow(300, 5);
        assertTrue(window.accepts("695", 1000));
        assertTrue(window.accepts("1305", 1000));
        assertFalse(window.accepts("694", 1000));
        assertFalse(window.accepts("1306", 1000));
        assertEquals(621, window.nonceTtlSeconds());
        // First verifier is +5s from UTC; another can be -5s. The nonce must outlive both windows.
        assertTrue(window.accepts("1305", 990 + window.nonceTtlSeconds() - 1));
        assertFalse(window.accepts("1305", 990 + window.nonceTtlSeconds()));
        assertFalse(window.accepts(Long.toString(Long.MIN_VALUE), 1000));
        assertFalse(window.accepts(Long.toString(Long.MAX_VALUE), 1000));
        assertFalse(window.accepts("+1000", 1000));
        assertFalse(window.accepts("01000", 1000));
        assertFalse(window.accepts("100000000000000000000000000000", 1000));
        assertTrue(window.accepts(Long.toString(Long.MAX_VALUE), Long.MAX_VALUE));
        assertFalse(window.accepts("0", Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ReplayWindow(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReplayWindow(1, -1));
    }
}
