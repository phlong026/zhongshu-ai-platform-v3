package cn.iocoder.yudao.module.franchise.api.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.NumberSerializers;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Canonical business content only: transport nonce, timestamp and signing key never enter the hash. */
public final class CapabilityJson {
    private final ObjectMapper mapper;
    public CapabilityJson(ObjectMapper mapper) {
        // The internal protocol and fingerprint use exact integer tokens, including Snowflake IDs.
        // Override only the copy: browser-facing JSON must keep its safe-integer string policy.
        var integers = new SimpleModule("design-capability-integers");
        integers.addSerializer(Long.class, new NumberSerializers.LongSerializer(Long.class));
        integers.addSerializer(Long.TYPE, new NumberSerializers.LongSerializer(Long.TYPE));
        this.mapper = mapper.copy().registerModule(integers);
    }

    public String fingerprint(DesignCapabilityContract.Request<?> request) {
        ObjectNode business = mapper.createObjectNode();
        business.set("actor", mapper.valueToTree(request.actor()));
        business.set("payload", mapper.valueToTree(request.payload()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(canonical(business)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 unavailable", e);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) sorted.set(name, canonical(node.get(name)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode values = mapper.createArrayNode();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        return node;
    }

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize design capability payload", e); }
    }

    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted design capability data", e); }
    }

    public Object result(Object value, DesignCapabilityContract.Operation operation) {
        return mapper.convertValue(value, operation.listResult()
                ? mapper.getTypeFactory().constructCollectionType(java.util.List.class, operation.resultType())
                : mapper.getTypeFactory().constructType(operation.resultType()));
    }
}
