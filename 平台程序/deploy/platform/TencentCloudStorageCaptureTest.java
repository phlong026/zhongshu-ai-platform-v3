import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class TencentCloudStorageCaptureTest {
    private static final String INSTANCE = "ins-abcdef12";

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--emit-evidence".equals(args[0])) {
            emitEvidence(Path.of(args[1]));
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("unknown test arguments");
        happyPathPaginatesAndWritesPrivateEvidence();
        duplicateDiskFailsClosed();
        mismatchedInstanceFailsClosed();
        emptyPageBeforeTotalFailsClosed();
        sdkProfilesAreFixedAndNonDebug();
        sdkFailureBodyIsNotPropagated();
        duplicateJsonKeyFailsClosed();
        nonIntegerTotalCountFailsClosed();
        oversizedEvidenceIsNotPublished();
        parentSymlinkFailsBeforeCloudCalls();
        replacedOutputDirectoryFailsBeforeWrite();
        System.out.println("PASS: TencentCloudStorageCapture offline tests");
    }

    private static void emitEvidence(Path output) throws Exception {
        FakeTransport fake = baseTransport();
        fake.responses.add(cbsResponse(101, 0, 100, false));
        fake.responses.add(cbsResponse(101, 100, 1, false));
        TencentCloudStorageCapture.CaptureResult result = TencentCloudStorageCapture.collect(
                preparedOptions(output), fake, Clock.systemUTC());
        TencentCloudStorageCapture.writeResult(output, result);
        System.out.println(output.resolve("evidence.json"));
    }

    private static void happyPathPaginatesAndWritesPrivateEvidence() throws Exception {
        Path parent = tempDirectory("tencent-storage-capture-test-");
        Path output = parent.resolve("evidence");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponse(101, 0, 100, false));
            fake.responses.add(cbsResponse(101, 100, 1, false));
            TencentCloudStorageCapture.Options options = preparedOptions(output);
            TencentCloudStorageCapture.CaptureResult result = TencentCloudStorageCapture.collect(options, fake,
                    Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
            assertEquals(101, result.manifest.get("diskCount").getAsInt(), "disk count");
            assertEquals(4, result.rawResponses.size(), "raw response count");
            assertDescribeDisksRequest(fake.requests.get(2), 0);
            assertDescribeDisksRequest(fake.requests.get(3), 100);
            JsonObject evidence = result.evidence;
            assertEquals(1, evidence.get("version").getAsInt(), "evidence version");
            assertEquals("tencent-cbs", evidence.get("provider").getAsString(), "evidence provider");
            assertEquals("ap-shanghai", evidence.get("region").getAsString(), "evidence region");
            assertEquals(INSTANCE, evidence.get("instanceId").getAsString(), "evidence instance");
            assertEquals("10001", evidence.get("accountUin").getAsString(), "account uin");
            assertEquals("2026-11-14T12:00:00Z", evidence.get("collectedAt").getAsString(), "collected at");
            JsonObject responses = evidence.getAsJsonObject("responses");
            assertEquals(0, responses.getAsJsonObject("getCallerIdentity")
                    .getAsJsonObject("request").size(), "STS request");
            assertTrue(responses.getAsJsonObject("getCallerIdentity").getAsJsonObject("response")
                    .has("Response"), "STS envelope");
            assertTrue(!responses.getAsJsonObject("getCallerIdentity").getAsJsonObject("response")
                    .getAsJsonObject("Response").has("Response"), "STS must not be double wrapped");
            assertEquals(INSTANCE, responses.getAsJsonObject("describeInstances")
                    .getAsJsonObject("request").getAsJsonArray("InstanceIds").get(0).getAsString(), "CVM request");
            JsonArray diskCalls = responses.getAsJsonArray("describeDisks");
            assertEquals(2, diskCalls.size(), "CBS evidence pages");
            assertEquals(100, diskCalls.get(1).getAsJsonObject().getAsJsonObject("request")
                    .get("Offset").getAsInt(), "CBS second offset");
            if (!supportsSecureDirectoryStream(output)) {
                try {
                    TencentCloudStorageCapture.writeResult(output, result);
                    throw new AssertionError("unsupported filesystem should fail closed");
                } catch (TencentCloudStorageCapture.CaptureException expected) {
                    assertTrue(expected.getMessage().contains("secure directory-relative"),
                            "unexpected secure directory error");
                }
                assertTrue(!Files.exists(output.resolve("evidence.json")), "unsupported filesystem wrote evidence");
                return;
            }
            TencentCloudStorageCapture.writeResult(output, result);
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(output)), "output mode");
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(output.resolve("capture-manifest.json"))), "manifest mode");
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(output.resolve("evidence.json"))), "evidence mode");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                    output.resolve("raw-cbs-describe-disks-page-0002.json"))), "raw mode");
            assertTrue(!Files.exists(output.resolve("raw")), "raw subdirectory must not be created");
            JsonObject manifest = JsonParser.parseString(Files.readString(
                    output.resolve("capture-manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(INSTANCE, manifest.getAsJsonObject("target").get("instanceId").getAsString(), "target");
            boolean refusedOverwrite = false;
            try {
                TencentCloudStorageCapture.writeResult(output, result);
            } catch (TencentCloudStorageCapture.CaptureException expected) {
                refusedOverwrite = true;
            }
            assertTrue(refusedOverwrite, "evidence overwrite must fail");
        } finally {
            deleteTree(parent);
        }
    }

    private static void duplicateDiskFailsClosed() throws Exception {
        Path parent = tempDirectory("tencent-storage-duplicate-");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponse(2, 0, 2, true));
            expectFailure(preparedOptions(parent.resolve("evidence")), fake, "duplicate DiskId");
            assertTrue(!Files.exists(parent.resolve("evidence/capture-manifest.json")), "manifest must be absent");
        } finally {
            deleteTree(parent);
        }
    }

    private static void mismatchedInstanceFailsClosed() throws Exception {
        Path parent = tempDirectory("tencent-storage-mismatch-");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponseForInstance("ins-other123", 1));
            expectFailure(preparedOptions(parent.resolve("evidence")), fake, "another instance");
        } finally {
            deleteTree(parent);
        }
    }

    private static void emptyPageBeforeTotalFailsClosed() throws Exception {
        Path parent = tempDirectory("tencent-storage-empty-page-");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponse(2, 0, 1, false));
            fake.responses.add(cbsResponse(2, 1, 0, false));
            expectFailure(preparedOptions(parent.resolve("evidence")), fake, "empty page");
        } finally {
            deleteTree(parent);
        }
    }

    private static void sdkProfilesAreFixedAndNonDebug() {
        Credential credential = new Credential("offline-id", "offline-key");
        assertProfile(new TencentCloudStorageCapture.FixedClient("cbs", TencentCloudStorageCapture.CBS_VERSION,
                TencentCloudStorageCapture.CBS_ENDPOINT, credential, "ap-shanghai"),
                TencentCloudStorageCapture.CBS_ENDPOINT);
        assertProfile(new TencentCloudStorageCapture.FixedClient("cvm", TencentCloudStorageCapture.CVM_VERSION,
                TencentCloudStorageCapture.CVM_ENDPOINT, credential, "ap-shanghai"),
                TencentCloudStorageCapture.CVM_ENDPOINT);
        assertProfile(new TencentCloudStorageCapture.FixedClient("sts", TencentCloudStorageCapture.STS_VERSION,
                TencentCloudStorageCapture.STS_ENDPOINT, credential, "ap-shanghai"),
                TencentCloudStorageCapture.STS_ENDPOINT);
    }

    private static void assertProfile(TencentCloudStorageCapture.FixedClient client, String endpoint) {
        ClientProfile profile = client.getClientProfile();
        HttpProfile http = profile.getHttpProfile();
        assertEquals(ClientProfile.SIGN_TC3_256, profile.getSignMethod(), "TC3 signing");
        assertTrue(!profile.isDebug(), "SDK debug must be disabled");
        assertEquals(HttpProfile.REQ_HTTPS, http.getProtocol(), "HTTPS protocol");
        assertEquals(HttpProfile.REQ_POST, http.getReqMethod(), "POST method");
        assertEquals(endpoint, http.getEndpoint(), "fixed endpoint");
        assertEquals(10, http.getConnTimeout(), "connect timeout");
        assertEquals(30, http.getReadTimeout(), "read timeout");
        assertEquals(30, http.getWriteTimeout(), "write timeout");
    }

    private static void sdkFailureBodyIsNotPropagated() throws Exception {
        Path parent = tempDirectory("tencent-storage-sdk-failure-");
        try {
            TencentCloudStorageCapture.ApiTransport failure = (service, action, requestJson) -> {
                throw new Exception("SDK_RESPONSE_WITH_SECRET_SENTINEL");
            };
            try {
                TencentCloudStorageCapture.collect(preparedOptions(parent.resolve("evidence")), failure, Clock.systemUTC());
                throw new AssertionError("SDK failure should fail closed");
            } catch (TencentCloudStorageCapture.CaptureException expected) {
                assertTrue(!expected.getMessage().contains("SECRET_SENTINEL"), "SDK body leaked");
                assertTrue(expected.getMessage().contains("GetCallerIdentity"), "action context missing");
            }
        } finally {
            deleteTree(parent);
        }
    }

    private static void duplicateJsonKeyFailsClosed() throws Exception {
        Path parent = tempDirectory("tencent-storage-duplicate-json-");
        try {
            FakeTransport fake = new FakeTransport();
            fake.responses.add("{\"Response\":{\"AccountId\":\"1\",\"AccountId\":\"2\","
                    + "\"UserId\":\"u\",\"PrincipalId\":\"p\",\"Type\":\"CAMUser\","
                    + "\"Arn\":\"qcs::cam::uin/1:uin/u\",\"RequestId\":\"req\"}}");
            expectFailure(preparedOptions(parent.resolve("evidence")), fake, "invalid JSON");
        } finally {
            deleteTree(parent);
        }
    }

    private static void nonIntegerTotalCountFailsClosed() throws Exception {
        Path parent = tempDirectory("tencent-storage-non-integer-");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add("{\"Response\":{\"TotalCount\":1.5,\"DiskSet\":[],"
                    + "\"RequestId\":\"req-cbs-decimal\"}}");
            expectFailure(preparedOptions(parent.resolve("evidence")), fake, "invalid TotalCount");
        } finally {
            deleteTree(parent);
        }
    }

    private static void oversizedEvidenceIsNotPublished() throws Exception {
        Path parent = tempDirectory("tencent-storage-oversized-");
        Path output = parent.resolve("evidence");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponse(0, 0, 0, false));
            TencentCloudStorageCapture.CaptureResult result = TencentCloudStorageCapture.collect(
                    preparedOptions(output), fake, Clock.systemUTC());
            result.evidence.addProperty("oversized", "x".repeat(1024 * 1024));
            try {
                TencentCloudStorageCapture.writeResult(output, result);
                throw new AssertionError("oversized evidence should fail");
            } catch (TencentCloudStorageCapture.CaptureException expected) {
                assertTrue(expected.getMessage().contains("1 MiB"), "unexpected size error");
            }
            assertTrue(!Files.exists(output.resolve("evidence.json")), "oversized evidence must be absent");
            assertTrue(!Files.exists(output.resolve("capture-manifest.json")), "manifest must not precede size gate");
            assertTrue(!Files.exists(output.resolve("raw")), "raw must not precede size gate");
        } finally {
            deleteTree(parent);
        }
    }

    private static void parentSymlinkFailsBeforeCloudCalls() throws Exception {
        Path parent = tempDirectory("tencent-storage-parent-symlink-");
        try {
            Path real = parent.resolve("real-parent");
            Files.createDirectory(real, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            Path alias = parent.resolve("parent-alias");
            Files.createSymbolicLink(alias, real);
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponse(0, 0, 0, false));
            expectFailure(options(alias.resolve("evidence")), fake, "must not contain symlinks");
            assertEquals(0, fake.requests.size(), "cloud calls before output path validation");
            assertTrue(!Files.exists(real.resolve("evidence")), "symlink parent must not receive output");
        } finally {
            deleteTree(parent);
        }
    }

    private static void replacedOutputDirectoryFailsBeforeWrite() throws Exception {
        Path parent = tempDirectory("tencent-storage-replaced-output-");
        Path output = parent.resolve("evidence");
        try {
            FakeTransport fake = baseTransport();
            fake.responses.add(cbsResponse(0, 0, 0, false));
            TencentCloudStorageCapture.CaptureResult result = TencentCloudStorageCapture.collect(
                    preparedOptions(output), fake, Clock.systemUTC());
            Path moved = parent.resolve("captured-directory-moved");
            Files.move(output, moved, StandardCopyOption.ATOMIC_MOVE);
            Files.createDirectory(output, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            try {
                TencentCloudStorageCapture.writeResult(output, result);
                throw new AssertionError("replaced output directory should fail");
            } catch (TencentCloudStorageCapture.CaptureException expected) {
                assertTrue(expected.getMessage().contains("replaced"), "unexpected replacement error");
            }
            assertTrue(!Files.exists(output.resolve("evidence.json")), "replacement directory must stay empty");
            assertTrue(!Files.exists(moved.resolve("evidence.json")), "moved directory must stay empty");
        } finally {
            deleteTree(parent);
        }
    }

    private static FakeTransport baseTransport() {
        FakeTransport fake = new FakeTransport();
        fake.responses.add("{\"Response\":{\"AccountId\":\"10001\",\"UserId\":\"10002\","
                + "\"PrincipalId\":\"qcs::cam::uin/10001:uin/10002\",\"Type\":\"CAMUser\","
                + "\"Arn\":\"qcs::cam::uin/10001:uin/10002\",\"RequestId\":\"req-sts\"}}");
        fake.responses.add("{\"Response\":{\"TotalCount\":1,\"InstanceSet\":[{\"InstanceId\":\""
                + INSTANCE + "\",\"InstanceName\":\"b-host\",\"InstanceState\":\"RUNNING\","
                + "\"Placement\":{\"Zone\":\"ap-shanghai-5\"},"
                + "\"SystemDisk\":{\"DiskId\":\"disk-system001\"}}],\"RequestId\":\"req-cvm\"}}");
        return fake;
    }

    private static String cbsResponse(int total, int start, int count, boolean duplicate) {
        JsonObject response = new JsonObject();
        response.addProperty("TotalCount", total);
        response.addProperty("RequestId", "req-cbs-" + start);
        JsonArray disks = new JsonArray();
        for (int i = 0; i < count; i++) {
            int absoluteIndex = start + i;
            JsonObject disk = new JsonObject();
            disk.addProperty("DiskId", duplicate ? "disk-duplicate001"
                    : absoluteIndex == 0 ? "disk-system001" : String.format("disk-data%06d", absoluteIndex));
            disk.addProperty("InstanceId", INSTANCE);
            disk.addProperty("DiskType", "CLOUD_PREMIUM");
            disk.addProperty("DiskUsage", absoluteIndex == 0 ? "SYSTEM_DISK" : "DATA_DISK");
            disk.addProperty("DiskState", "ATTACHED");
            disk.addProperty("Attached", true);
            disk.addProperty("Shareable", false);
            JsonObject placement = new JsonObject();
            placement.addProperty("Zone", "ap-shanghai-5");
            disk.add("Placement", placement);
            disk.addProperty("Encrypt", false);
            disk.addProperty("DiskSize", 100);
            disks.add(disk);
        }
        response.add("DiskSet", disks);
        JsonObject envelope = new JsonObject();
        envelope.add("Response", response);
        return envelope.toString();
    }

    private static String cbsResponseForInstance(String instanceId, int total) {
        JsonObject response = new JsonObject();
        response.addProperty("TotalCount", total);
        response.addProperty("RequestId", "req-cbs-other");
        JsonArray disks = new JsonArray();
        JsonObject disk = new JsonObject();
        disk.addProperty("DiskId", "disk-other");
        disk.addProperty("InstanceId", instanceId);
        disk.addProperty("DiskUsage", "DATA_DISK");
        disk.addProperty("DiskState", "ATTACHED");
        disk.addProperty("Attached", true);
        disk.addProperty("Shareable", false);
        JsonObject placement = new JsonObject();
        placement.addProperty("Zone", "ap-shanghai-5");
        disk.add("Placement", placement);
        disk.addProperty("Encrypt", false);
        disks.add(disk);
        response.add("DiskSet", disks);
        JsonObject envelope = new JsonObject();
        envelope.add("Response", response);
        return envelope.toString();
    }

    private static TencentCloudStorageCapture.Options options(Path output) throws Exception {
        return new TencentCloudStorageCapture.Options(output.resolveSibling("unused-credential"), INSTANCE,
                "ap-shanghai", output);
    }

    private static TencentCloudStorageCapture.Options preparedOptions(Path output) throws Exception {
        Files.createDirectory(output, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        return options(output);
    }

    private static Path tempDirectory(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).toRealPath();
    }

    private static boolean supportsSecureDirectoryStream(Path path) throws Exception {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            return stream instanceof SecureDirectoryStream;
        }
    }

    private static void expectFailure(TencentCloudStorageCapture.Options options, FakeTransport fake,
                                      String expectedText) throws Exception {
        try {
            TencentCloudStorageCapture.collect(options, fake, Clock.systemUTC());
            throw new AssertionError("expected failure containing " + expectedText);
        } catch (TencentCloudStorageCapture.CaptureException expected) {
            assertTrue(expected.getMessage().contains(expectedText), "unexpected error: " + expected.getMessage());
        }
    }

    private static void assertDescribeDisksRequest(Request request, int offset) {
        assertEquals("cbs", request.service, "service");
        assertEquals("DescribeDisks", request.action, "action");
        JsonObject value = JsonParser.parseString(request.json).getAsJsonObject();
        assertEquals(offset, value.get("Offset").getAsInt(), "offset");
        assertEquals(100, value.get("Limit").getAsInt(), "limit");
        JsonObject filter = value.getAsJsonArray("Filters").get(0).getAsJsonObject();
        assertEquals("instance-id", filter.get("Name").getAsString(), "filter name");
        assertEquals(INSTANCE, filter.getAsJsonArray("Values").get(0).getAsString(), "filter value");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + " got " + actual);
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (java.util.stream.Stream<Path> entries = Files.walk(path)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(value -> {
                try {
                    Files.deleteIfExists(value);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static final class FakeTransport implements TencentCloudStorageCapture.ApiTransport {
        final Deque<String> responses = new ArrayDeque<String>();
        final List<Request> requests = new ArrayList<Request>();

        @Override
        public String call(String service, String action, String requestJson) throws Exception {
            requests.add(new Request(service, action, requestJson));
            if (responses.isEmpty()) throw new IllegalStateException("missing fake response");
            return responses.removeFirst();
        }
    }

    private static final class Request {
        final String service;
        final String action;
        final String json;

        Request(String service, String action, String json) {
            this.service = service;
            this.action = action;
            this.json = json;
        }
    }
}
