import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.tencentcloudapi.common.AbstractClient;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only Tencent Cloud identity/CVM/CBS evidence collector for B deployment review.
 *
 * <p>The cloud call surface is deliberately fixed to GetCallerIdentity,
 * DescribeInstances and DescribeDisks. Authentication and TC3 signing are delegated to
 * Tencent Cloud's official Java SDK; this class contains no signing implementation.</p>
 */
public final class TencentCloudStorageCapture {
    static final String CBS_ENDPOINT = "cbs.tencentcloudapi.com";
    static final String CVM_ENDPOINT = "cvm.tencentcloudapi.com";
    static final String STS_ENDPOINT = "sts.tencentcloudapi.com";
    static final String CBS_VERSION = "2017-03-12";
    static final String CVM_VERSION = "2017-03-12";
    static final String STS_VERSION = "2018-08-13";
    static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 10_000;
    private static final int MAX_CREDENTIAL_BYTES = 16 * 1024;
    private static final int MAX_EVIDENCE_BYTES = 1024 * 1024;
    private static final Pattern INSTANCE_ID = Pattern.compile("ins-[A-Za-z0-9]{6,64}");
    private static final Pattern REGION = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Set<PosixFilePermission> MODE_0600 = Collections.unmodifiableSet(
            PosixFilePermissions.fromString("rw-------"));
    private static final Set<PosixFilePermission> MODE_0700 = Collections.unmodifiableSet(
            PosixFilePermissions.fromString("rwx------"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private TencentCloudStorageCapture() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            Credential credential = readCredential(options.credentialFile);
            ApiTransport transport = new SdkTransport(credential, options.region);
            CaptureResult result = collect(options, transport, Clock.systemUTC());
            writeResult(options.outputDir, result);
            System.out.println("capture complete: " + options.outputDir.toAbsolutePath().normalize());
        } catch (CaptureException e) {
            System.err.println("capture failed: " + e.getMessage());
            System.exit(2);
        }
    }

    static CaptureResult collect(Options options, ApiTransport transport, Clock clock) throws CaptureException {
        OutputDirectory outputDirectory = prepareOutputDirectory(options.outputDir);
        boolean completed = false;
        try {
        Instant startedAt = clock.instant();
        List<RawResponse> raw = new ArrayList<RawResponse>();

        JsonObject identityRequest = new JsonObject();
        JsonObject identity = callAndUnwrap(transport, "sts", "GetCallerIdentity", identityRequest, raw,
                "raw-sts-get-caller-identity.json");
        requireString(identity, "RequestId", "STS response");
        requireString(identity, "AccountId", "STS response");
        requireString(identity, "UserId", "STS response");
        requireString(identity, "PrincipalId", "STS response");
        requireString(identity, "Type", "STS response");
        requireString(identity, "Arn", "STS response");

        JsonObject instanceRequest = new JsonObject();
        JsonArray instanceIds = new JsonArray();
        instanceIds.add(options.instanceId);
        instanceRequest.add("InstanceIds", instanceIds);
        JsonObject instanceResponse = callAndUnwrap(transport, "cvm", "DescribeInstances", instanceRequest, raw,
                "raw-cvm-describe-instances.json");
        requireString(instanceResponse, "RequestId", "CVM response");
        int instanceTotal = requireNonNegativeInt(instanceResponse, "TotalCount", "CVM response");
        JsonArray instanceSet = requireArray(instanceResponse, "InstanceSet", "CVM response");
        if (instanceTotal != 1 || instanceSet.size() != 1) {
            throw new CaptureException("DescribeInstances must return exactly one instance");
        }
        JsonObject instance = requireObject(instanceSet.get(0), "CVM InstanceSet[0]");
        if (!options.instanceId.equals(requireString(instance, "InstanceId", "CVM instance"))) {
            throw new CaptureException("DescribeInstances returned a different instance");
        }

        List<JsonObject> disks = new ArrayList<JsonObject>();
        Set<String> diskIds = new HashSet<String>();
        Set<String> requestIds = new HashSet<String>();
        int offset = 0;
        Integer expectedTotal = null;
        int page = 0;
        while (expectedTotal == null || disks.size() < expectedTotal.intValue()) {
            page++;
            if (page > MAX_PAGES) {
                throw new CaptureException("DescribeDisks exceeded the pagination safety limit");
            }
            JsonObject request = describeDisksRequest(options.instanceId, offset);
            String rawPath = String.format("raw-cbs-describe-disks-page-%04d.json", page);
            JsonObject response = callAndUnwrap(transport, "cbs", "DescribeDisks", request, raw, rawPath);
            String requestId = requireString(response, "RequestId", "CBS response");
            if (!requestIds.add(requestId)) {
                throw new CaptureException("DescribeDisks returned a duplicate RequestId");
            }
            int total = requireNonNegativeInt(response, "TotalCount", "CBS response");
            if (expectedTotal == null) {
                expectedTotal = Integer.valueOf(total);
            } else if (expectedTotal.intValue() != total) {
                throw new CaptureException("DescribeDisks TotalCount changed during pagination");
            }
            JsonArray pageDisks = requireArray(response, "DiskSet", "CBS response");
            if (pageDisks.size() > PAGE_LIMIT) {
                throw new CaptureException("DescribeDisks returned more records than the requested page limit");
            }
            if (pageDisks.size() == 0 && disks.size() < expectedTotal.intValue()) {
                throw new CaptureException("DescribeDisks returned an empty page before TotalCount was reached");
            }
            for (JsonElement element : pageDisks) {
                JsonObject disk = requireObject(element, "CBS DiskSet item");
                String diskId = requireString(disk, "DiskId", "CBS disk");
                String attachedInstance = requireString(disk, "InstanceId", "CBS disk");
                if (!options.instanceId.equals(attachedInstance)) {
                    throw new CaptureException("DescribeDisks returned a disk attached to another instance");
                }
                if (!diskIds.add(diskId)) {
                    throw new CaptureException("DescribeDisks returned duplicate DiskId " + diskId);
                }
                disks.add(disk.deepCopy());
            }
            if (disks.size() > expectedTotal.intValue()) {
                throw new CaptureException("DescribeDisks returned more records than TotalCount");
            }
            offset = disks.size();
        }
        if (disks.size() != expectedTotal.intValue()) {
            throw new CaptureException("DescribeDisks pagination did not reach TotalCount");
        }

        Instant completedAt = clock.instant();
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1);
        manifest.addProperty("captureStartedAt", formatInstant(startedAt));
        manifest.addProperty("captureCompletedAt", formatInstant(completedAt));
        JsonObject target = new JsonObject();
        target.addProperty("region", options.region);
        target.addProperty("instanceId", options.instanceId);
        manifest.add("target", target);
        manifest.add("callerIdentity", copyFields(identity,
                "AccountId", "UserId", "PrincipalId", "Type", "Arn", "RequestId"));
        manifest.add("instance", instance.deepCopy());
        JsonArray diskEvidence = new JsonArray();
        for (JsonObject disk : disks) {
            diskEvidence.add(disk);
        }
        manifest.add("disks", diskEvidence);
        manifest.addProperty("diskCount", disks.size());
        JsonArray calls = new JsonArray();
        for (RawResponse response : raw) {
            JsonObject call = new JsonObject();
            call.addProperty("service", response.service);
            call.addProperty("action", response.action);
            call.addProperty("endpoint", endpointFor(response.service));
            call.addProperty("apiVersion", versionFor(response.service));
            call.addProperty("requestId", response.requestId);
            call.addProperty("rawResponseFile", response.path);
            calls.add(call);
        }
        manifest.add("calls", calls);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("version", 1);
        evidence.addProperty("provider", "tencent-cbs");
        evidence.addProperty("region", options.region);
        evidence.addProperty("instanceId", options.instanceId);
        evidence.addProperty("accountUin", identity.get("AccountId").getAsString());
        evidence.addProperty("collectedAt", formatInstant(completedAt));
        JsonObject responses = new JsonObject();
        responses.add("getCallerIdentity", evidenceCall(raw.get(0)));
        responses.add("describeInstances", evidenceCall(raw.get(1)));
        JsonArray describeDisks = new JsonArray();
        for (int i = 2; i < raw.size(); i++) {
            describeDisks.add(evidenceCall(raw.get(i)));
        }
        responses.add("describeDisks", describeDisks);
        evidence.add("responses", responses);
        CaptureResult result = new CaptureResult(raw, manifest, evidence, outputDirectory);
        completed = true;
        return result;
        } finally {
            if (!completed) outputDirectory.close();
        }
    }

    static void writeResult(Path outputDir, CaptureResult result) throws CaptureException {
        if (!outputDir.toAbsolutePath().normalize().equals(result.outputDirectory.path)) {
            throw new CaptureException("output directory differs from the captured directory identity");
        }
        try {
        result.outputDirectory.verifyVisible();
        String evidenceJson = GSON.toJson(result.evidence) + "\n";
        if (evidenceJson.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
            throw new CaptureException("evidence.json exceeds the 1 MiB safety limit");
        }
            SecureDirectoryStream<Path> root = result.outputDirectory.root;
            result.outputDirectory.verifyVisible(root);
            for (RawResponse response : result.rawResponses) {
                Path relative = Paths.get(response.path);
                if (relative.getNameCount() != 1 || !relative.toString().startsWith("raw-")) {
                    throw new CaptureException("raw response path escaped output directory");
                }
                writePrivateNew(root, relative, response.rawJson + "\n", result.outputDirectory, root);
            }
            writePrivateNew(root, Paths.get("capture-manifest.json"), GSON.toJson(result.manifest) + "\n",
                    result.outputDirectory, root);
            writePrivateNew(root, Paths.get("evidence.json"), evidenceJson,
                    result.outputDirectory, root);
            result.outputDirectory.verifyVisible(root);
        } finally {
            result.outputDirectory.close();
        }
    }

    private static JsonObject evidenceCall(RawResponse raw) {
        JsonObject value = new JsonObject();
        value.add("request", raw.request.deepCopy());
        value.add("response", raw.envelope.deepCopy());
        return value;
    }

    private static String formatInstant(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static JsonObject describeDisksRequest(String instanceId, int offset) {
        JsonObject request = new JsonObject();
        JsonArray filters = new JsonArray();
        JsonObject filter = new JsonObject();
        filter.addProperty("Name", "instance-id");
        JsonArray values = new JsonArray();
        values.add(instanceId);
        filter.add("Values", values);
        filters.add(filter);
        request.add("Filters", filters);
        request.addProperty("Offset", offset);
        request.addProperty("Limit", PAGE_LIMIT);
        return request;
    }

    private static JsonObject callAndUnwrap(ApiTransport transport, String service, String action,
                                             JsonObject request, List<RawResponse> raw, String rawPath)
            throws CaptureException {
        final String responseJson;
        try {
            responseJson = transport.call(service, action, GSON.toJson(request));
        } catch (Exception e) {
            // SDK exception bodies are intentionally not propagated: they may include request context.
            throw new CaptureException("cloud API call failed for " + action);
        }
        JsonObject parsed;
        try {
            parsed = requireObject(parseStrictJson(responseJson), action + " response JSON");
        } catch (CaptureException | RuntimeException e) {
            throw new CaptureException(action + " returned invalid JSON");
        }
        JsonObject envelope;
        if (parsed.has("Response")) {
            envelope = parsed;
        } else if (parsed.has("RequestId")) {
            envelope = new JsonObject();
            envelope.add("Response", parsed);
        } else {
            throw new CaptureException(action + " response is missing the Response envelope");
        }
        JsonObject response = requireObject(envelope.get("Response"), action + " response envelope");
        if (response.has("Error")) {
            throw new CaptureException(action + " returned a cloud API error");
        }
        String requestId = requireString(response, "RequestId", action + " response");
        raw.add(new RawResponse(service, action, rawPath, requestId, request.deepCopy(),
                envelope.deepCopy(), responseJson));
        return response;
    }

    private static JsonElement parseStrictJson(String json) throws CaptureException {
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            JsonElement value = readStrictValue(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new CaptureException("JSON has trailing content");
            }
            return value;
        } catch (CaptureException e) {
            throw e;
        } catch (Exception e) {
            throw new CaptureException("JSON is malformed");
        }
    }

    private static JsonElement readStrictValue(JsonReader reader) throws IOException, CaptureException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                reader.beginObject();
                JsonObject object = new JsonObject();
                Set<String> names = new HashSet<String>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) {
                        throw new CaptureException("JSON contains duplicate key " + name);
                    }
                    object.add(name, readStrictValue(reader));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    array.add(readStrictValue(reader));
                }
                reader.endArray();
                return array;
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                String number = reader.nextString();
                try {
                    return new JsonPrimitive(new BigDecimal(number));
                } catch (NumberFormatException e) {
                    throw new CaptureException("JSON contains an invalid number");
                }
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return com.google.gson.JsonNull.INSTANCE;
            default:
                throw new CaptureException("JSON contains an unexpected token");
        }
    }

    private static Credential readCredential(Path path) throws CaptureException {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            BasicFileAttributes before = Files.readAttributes(normalized, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || Files.isSymbolicLink(normalized)) {
                throw new CaptureException("credential file must be a regular non-symlink file");
            }
            if (!Files.getPosixFilePermissions(normalized, LinkOption.NOFOLLOW_LINKS).equals(MODE_0600)) {
                throw new CaptureException("credential file permissions must be exactly 0600");
            }
            if (before.size() <= 0 || before.size() > MAX_CREDENTIAL_BYTES) {
                throw new CaptureException("credential file size is invalid");
            }
            byte[] bytes;
            OpenOption[] options = new OpenOption[]{StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS};
            try (FileChannel channel = FileChannel.open(normalized, options)) {
                ByteBuffer buffer = ByteBuffer.allocate((int) before.size() + 1);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // bounded by the validated file size plus one byte
                }
                if (buffer.position() != before.size()) {
                    throw new CaptureException("credential file changed while it was read");
                }
                bytes = Arrays.copyOf(buffer.array(), buffer.position());
            }
            BasicFileAttributes after = Files.readAttributes(normalized, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!sameFile(before, after)) {
                throw new CaptureException("credential file changed while it was read");
            }
            JsonObject value = requireObject(parseStrictJson(new String(bytes, StandardCharsets.UTF_8)),
                    "credential file JSON");
            String secretId = requireString(value, "SecretId", "credential file");
            String secretKey = requireString(value, "SecretKey", "credential file");
            String token = optionalString(value, "Token");
            return token == null ? new Credential(secretId, secretKey) : new Credential(secretId, secretKey, token);
        } catch (CaptureException e) {
            throw e;
        } catch (Exception e) {
            throw new CaptureException("credential file could not be read safely");
        }
    }

    private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
        Object firstKey = first.fileKey();
        Object secondKey = second.fileKey();
        return first.isRegularFile() && second.isRegularFile()
                && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && firstKey != null && firstKey.equals(secondKey);
    }

    private static OutputDirectory prepareOutputDirectory(Path outputDir) throws CaptureException {
        Path normalized = outputDir.toAbsolutePath().normalize();
        rejectSymlinkComponents(normalized, true);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new CaptureException("output directory must be a real directory");
                }
                if (!Files.getPosixFilePermissions(normalized, LinkOption.NOFOLLOW_LINKS).equals(MODE_0700)) {
                    throw new CaptureException("existing output directory permissions must be exactly 0700");
                }
                try (java.util.stream.Stream<Path> entries = Files.list(normalized)) {
                    if (entries.findAny().isPresent()) {
                        throw new CaptureException("output directory must be empty; evidence is never overwritten");
                    }
                }
            } catch (IOException e) {
                throw new CaptureException("output directory could not be inspected safely");
            }
        } else {
            throw new CaptureException("output directory must be pre-created empty with mode 0700");
        }
        rejectSymlinkComponents(normalized, false);
        return OutputDirectory.capture(normalized);
    }

    private static void rejectSymlinkComponents(Path path, boolean allowMissingLeaf) throws CaptureException {
        Path current = path.getRoot();
        if (current == null) {
            throw new CaptureException("output directory must be absolute");
        }
        for (int index = 0; index < path.getNameCount(); index++) {
            current = current.resolve(path.getName(index));
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (allowMissingLeaf && index == path.getNameCount() - 1) return;
                throw new CaptureException("output directory parent does not exist");
            }
            if (Files.isSymbolicLink(current)) {
                throw new CaptureException("output directory path must not contain symlinks");
            }
        }
    }

    private static void writePrivateNew(SecureDirectoryStream<Path> directory, Path relative,
                                        String content, OutputDirectory identity,
                                        SecureDirectoryStream<Path> root) throws CaptureException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Set<OpenOption> options = new HashSet<OpenOption>();
        options.add(StandardOpenOption.CREATE_NEW);
        options.add(StandardOpenOption.WRITE);
        options.add(LinkOption.NOFOLLOW_LINKS);
        identity.verifyVisible(root);
        try (SeekableByteChannel opened = directory.newByteChannel(relative, options,
                PosixFilePermissions.asFileAttribute(MODE_0600))) {
            if (!(opened instanceof FileChannel)) {
                throw new CaptureException("secure output channel cannot be durably synchronized");
            }
            FileChannel channel = (FileChannel) opened;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (IOException e) {
            throw new CaptureException("could not create evidence file without overwrite");
        }
        identity.verifyVisible(root);
    }

    private static JsonObject copyFields(JsonObject source, String... names) {
        JsonObject copy = new JsonObject();
        for (String name : names) {
            copy.add(name, source.get(name).deepCopy());
        }
        return copy;
    }

    private static JsonArray requireArray(JsonObject value, String name, String context) throws CaptureException {
        JsonElement element = value.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new CaptureException(context + " is missing " + name);
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement value, String context) throws CaptureException {
        if (value == null || !value.isJsonObject()) {
            throw new CaptureException(context + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requireString(JsonObject value, String name, String context) throws CaptureException {
        String result = optionalString(value, name);
        if (result == null) {
            throw new CaptureException(context + " is missing " + name);
        }
        return result;
    }

    private static String optionalString(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = element.getAsString();
        return text.trim().isEmpty() ? null : text;
    }

    private static int requireNonNegativeInt(JsonObject value, String name, String context) throws CaptureException {
        JsonElement element = value.get(name);
        try {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new NumberFormatException();
            }
            BigDecimal decimal = element.getAsBigDecimal();
            int result = decimal.intValueExact();
            if (result < 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (RuntimeException e) {
            throw new CaptureException(context + " has invalid " + name);
        }
    }

    private static String endpointFor(String service) throws CaptureException {
        if ("cbs".equals(service)) return CBS_ENDPOINT;
        if ("cvm".equals(service)) return CVM_ENDPOINT;
        if ("sts".equals(service)) return STS_ENDPOINT;
        throw new CaptureException("unsupported service");
    }

    private static String versionFor(String service) throws CaptureException {
        if ("cbs".equals(service)) return CBS_VERSION;
        if ("cvm".equals(service)) return CVM_VERSION;
        if ("sts".equals(service)) return STS_VERSION;
        throw new CaptureException("unsupported service");
    }

    interface ApiTransport {
        String call(String service, String action, String requestJson) throws Exception;
    }

    static final class SdkTransport implements ApiTransport {
        private final Map<String, FixedClient> clients = new HashMap<String, FixedClient>();

        SdkTransport(Credential credential, String region) {
            clients.put("cbs", new FixedClient("cbs", CBS_VERSION, CBS_ENDPOINT, credential, region));
            clients.put("cvm", new FixedClient("cvm", CVM_VERSION, CVM_ENDPOINT, credential, region));
            clients.put("sts", new FixedClient("sts", STS_VERSION, STS_ENDPOINT, credential, region));
        }

        @Override
        public String call(String service, String action, String requestJson) throws TencentCloudSDKException {
            if (!("cbs".equals(service) && "DescribeDisks".equals(action))
                    && !("cvm".equals(service) && "DescribeInstances".equals(action))
                    && !("sts".equals(service) && "GetCallerIdentity".equals(action))) {
                throw new IllegalArgumentException("action is not allowlisted");
            }
            FixedClient client = clients.get(service);
            if (client == null) {
                throw new IllegalArgumentException("service is not allowlisted");
            }
            return client.call(action, requestJson);
        }
    }

    static final class FixedClient extends AbstractClient {
        FixedClient(String service, String version, String endpoint, Credential credential, String region) {
            super(service, version, credential, region, profile(endpoint));
        }

        private static ClientProfile profile(String endpoint) {
            HttpProfile http = new HttpProfile();
            http.setProtocol(HttpProfile.REQ_HTTPS);
            http.setReqMethod(HttpProfile.REQ_POST);
            http.setEndpoint(endpoint);
            http.setConnTimeout(10);
            http.setReadTimeout(30);
            http.setWriteTimeout(30);
            ClientProfile profile = new ClientProfile(ClientProfile.SIGN_TC3_256, http);
            profile.setDebug(false);
            return profile;
        }
    }

    static final class Options {
        final Path credentialFile;
        final String instanceId;
        final String region;
        final Path outputDir;

        Options(Path credentialFile, String instanceId, String region, Path outputDir) throws CaptureException {
            if (credentialFile == null || outputDir == null) {
                throw new CaptureException("credential and output paths are required");
            }
            if (instanceId == null || !INSTANCE_ID.matcher(instanceId).matches()) {
                throw new CaptureException("instance id format is invalid");
            }
            if (region == null || !REGION.matcher(region).matches()) {
                throw new CaptureException("region format is invalid");
            }
            this.credentialFile = credentialFile;
            this.instanceId = instanceId;
            this.region = region;
            this.outputDir = outputDir.toAbsolutePath().normalize();
        }

        static Options parse(String[] args) throws CaptureException {
            if (args.length != 8) {
                throw new CaptureException("required: --credential-file FILE --instance-id ID --region REGION --output-dir DIR");
            }
            Map<String, String> values = new LinkedHashMap<String, String>();
            Set<String> allowed = new HashSet<String>(Arrays.asList(
                    "--credential-file", "--instance-id", "--region", "--output-dir"));
            for (int i = 0; i < args.length; i += 2) {
                String key = args[i];
                if (!allowed.contains(key) || values.put(key, args[i + 1]) != null) {
                    throw new CaptureException("unknown or duplicate argument");
                }
            }
            if (values.size() != allowed.size()) {
                throw new CaptureException("all four named arguments are required");
            }
            return new Options(Paths.get(values.get("--credential-file")), values.get("--instance-id"),
                    values.get("--region"), Paths.get(values.get("--output-dir")));
        }
    }

    static final class RawResponse {
        final String service;
        final String action;
        final String path;
        final String requestId;
        final JsonObject request;
        final JsonObject envelope;
        final String rawJson;

        RawResponse(String service, String action, String path, String requestId,
                    JsonObject request, JsonObject envelope, String rawJson) {
            this.service = service;
            this.action = action;
            this.path = path;
            this.requestId = requestId;
            this.request = request;
            this.envelope = envelope;
            this.rawJson = rawJson;
        }
    }

    static final class CaptureResult {
        final List<RawResponse> rawResponses;
        final JsonObject manifest;
        final JsonObject evidence;
        final OutputDirectory outputDirectory;

        CaptureResult(List<RawResponse> rawResponses, JsonObject manifest, JsonObject evidence,
                      OutputDirectory outputDirectory) {
            this.rawResponses = Collections.unmodifiableList(new ArrayList<RawResponse>(rawResponses));
            this.manifest = manifest;
            this.evidence = evidence;
            this.outputDirectory = outputDirectory;
        }
    }

    static final class OutputDirectory implements AutoCloseable {
        final Path path;
        final Object fileKey;
        final java.nio.file.DirectoryStream<Path> parentStream;
        final SecureDirectoryStream<Path> parent;
        final SecureDirectoryStream<Path> root;
        boolean closed;

        private OutputDirectory(Path path, Object fileKey, java.nio.file.DirectoryStream<Path> parentStream,
                                SecureDirectoryStream<Path> parent, SecureDirectoryStream<Path> root) {
            this.path = path;
            this.fileKey = fileKey;
            this.parentStream = parentStream;
            this.parent = parent;
            this.root = root;
        }

        static OutputDirectory capture(Path path) throws CaptureException {
            java.nio.file.DirectoryStream<Path> openedParent = null;
            try {
                openedParent = Files.newDirectoryStream(path.getParent());
                if (!(openedParent instanceof SecureDirectoryStream)) {
                    throw new CaptureException("output filesystem lacks secure directory-relative writes");
                }
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> parent = (SecureDirectoryStream<Path>) openedParent;
                BasicFileAttributes attributes = parent.getFileAttributeView(path.getFileName(),
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attributes.isDirectory() || attributes.fileKey() == null) {
                    throw new CaptureException("output directory lacks a stable file identity");
                }
                SecureDirectoryStream<Path> root = parent.newDirectoryStream(path.getFileName(),
                        LinkOption.NOFOLLOW_LINKS);
                return new OutputDirectory(path, attributes.fileKey(), openedParent, parent, root);
            } catch (CaptureException e) {
                if (openedParent != null) try { openedParent.close(); } catch (IOException ignored) { }
                throw e;
            } catch (IOException e) {
                if (openedParent != null) try { openedParent.close(); } catch (IOException ignored) { }
                throw new CaptureException("output directory identity could not be captured");
            }
        }

        void verifyVisible() throws CaptureException {
            if (closed) throw new CaptureException("output directory transaction is already closed");
            rejectSymlinkComponents(path, false);
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || !fileKey.equals(attributes.fileKey())) {
                    throw new CaptureException("output directory was replaced after capture started");
                }
            } catch (IOException e) {
                throw new CaptureException("output directory is no longer visible");
            }
        }

        void verifyVisible(SecureDirectoryStream<Path> root) throws CaptureException {
            verifyVisible();
            try {
                BasicFileAttributeView view = root.getFileAttributeView(BasicFileAttributeView.class);
                BasicFileAttributes attributes = view.readAttributes();
                if (!attributes.isDirectory() || !fileKey.equals(attributes.fileKey())) {
                    throw new CaptureException("secure output descriptor differs from the captured directory");
                }
            } catch (IOException e) {
                throw new CaptureException("secure output descriptor could not be verified");
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try { root.close(); } catch (IOException ignored) { }
            try { parentStream.close(); } catch (IOException ignored) { }
        }
    }

    static final class CaptureException extends Exception {
        private static final long serialVersionUID = 1L;

        CaptureException(String message) {
            super(message);
        }
    }
}
