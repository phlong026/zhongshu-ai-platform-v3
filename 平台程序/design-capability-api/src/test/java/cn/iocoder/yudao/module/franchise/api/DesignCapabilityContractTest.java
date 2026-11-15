package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DesignCapabilityContractTest {
    @Test
    void internalLongsStayNumericAndStrictlyRoundTripWithoutChangingConsumerJson() throws Exception {
        var consumer = consumerMapper();
        long id = 9_007_199_254_741_117L;
        var request = new Request<>("platform", id, new DesignServicePort.Actor(id + 1, id + 2, "DESIGNER"),
                Operation.CREATE_BUDGET_ESTIMATE, "stable", "nonce",
                new Budget(id + 3, new DesignServicePort.BudgetEstimateCreate("CN", "BRICK", "STANDARD", 100, id + 4, "stable")));
        String before = consumer.writeValueAsString(request);
        assertTrue(consumer.readTree(before).get("orgId").isTextual());
        var json = new cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson(consumer);
        var tree = consumer.readTree(json.write(request));
        for (String path : new String[]{"/orgId", "/actor/actorUserId", "/actor/actorPostId", "/payload/projectId", "/payload/command/resultVersionId"}) {
            assertTrue(tree.at(path).isIntegralNumber(), path);
        }
        var strict = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        var type = strict.getTypeFactory().constructParametricType(Request.class, Budget.class);
        assertEquals(request, strict.readValue(json.write(request), type));
        assertThrows(com.fasterxml.jackson.databind.exc.MismatchedInputException.class, () -> strict.readValue(before, type));
        assertEquals(before, consumer.writeValueAsString(request));
        assertEquals(request.payload(), json.read(json.write(request.payload()), Budget.class));
    }

    @Test
    void fingerprintAndReceiptNumbersDoNotDependOnConsumerLongSerialization() throws Exception {
        long id = 9_007_199_254_741_117L;
        var request = new Request<>("platform", id, new DesignServicePort.Actor(id + 1, id + 2, "DESIGNER"),
                Operation.GET_PROJECT, "stable", "nonce", new Project(id + 3, Long.MAX_VALUE));
        var plain = new cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson(new com.fasterxml.jackson.databind.ObjectMapper());
        var consumer = new cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson(consumerMapper());
        assertEquals(plain.fingerprint(request), consumer.fingerprint(request));
        var receipt = new Receipt("receipt", Operation.GET_PROJECT, "stable", plain.fingerprint(request),
                ReceiptStatus.SUCCEEDED, new DesignServicePort.ProjectCreated(id + 3, false), Long.MAX_VALUE, null, null);
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(consumer.write(receipt));
        assertTrue(tree.get("jobId").isIntegralNumber());
        assertEquals(Long.MAX_VALUE, tree.get("jobId").longValue());
        assertTrue(tree.at("/result/projectId").isIntegralNumber());
    }

    @Test
    void internalProtocolStringsDoNotInheritConsumerInputCleaning() throws Exception {
        String signedUrl = "https://cos.example/乡墅.png?x=1&y=<原值>";
        var cleaning = new com.fasterxml.jackson.databind.module.SimpleModule("consumer-input-cleaner");
        cleaning.addDeserializer(String.class, new com.fasterxml.jackson.databind.deser.std.StringDeserializer() {
            @Override
            public String deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                      com.fasterxml.jackson.databind.DeserializationContext context)
                    throws java.io.IOException {
                return super.deserialize(parser, context).replace("&", "&amp;").replace("<", "&lt;");
            }
        });
        var consumer = consumerMapper().registerModule(cleaning);
        var capability = new cn.iocoder.yudao.module.franchise.api.capability.CapabilityJson(consumer);
        var source = new DesignServicePort.DownloadUrlView(signedUrl, Long.MAX_VALUE);

        assertNotEquals(signedUrl, consumer.convertValue(source, DesignServicePort.DownloadUrlView.class).downloadUrl());
        assertEquals(signedUrl, capability.result(source, Operation.CREATE_DOWNLOAD_URL)
                instanceof DesignServicePort.DownloadUrlView view ? view.downloadUrl() : null);
        assertEquals(source, capability.read(capability.write(source), DesignServicePort.DownloadUrlView.class));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree(capability.write(source))
                .get("expiresAt").isIntegralNumber());
    }

    private com.fasterxml.jackson.databind.ObjectMapper consumerMapper() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule("consumer-long-fixture");
        module.addSerializer(Long.class, cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        module.addSerializer(Long.TYPE, cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(module);
    }

    @Test
    void everyRemotePortMethodHasExactlyOneFixedPostAction() {
        Set<String> expected = Arrays.stream(DesignServicePort.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).filter(name -> !name.equals("validateAsset"))
                .collect(Collectors.toSet());
        Set<String> actual = Arrays.stream(Operation.values()).map(Operation::portMethod)
                .filter(name -> name != null).collect(Collectors.toSet());
        assertEquals(24, expected.size());
        assertEquals(expected, actual);
        assertEquals(Operation.values().length, Arrays.stream(Operation.values()).map(Operation::path).distinct().count());
        for (Operation action : Operation.values()) {
            assertTrue(action.path().startsWith("/internal-api/design/v1/platform/"));
            assertFalse(action.path().contains("?"));
            assertNotNull(action.payloadType());
        }
    }

    @Test
    void readsRequireExplicitOrganizationAndActorAndWritesRequireStableKey() {
        var actor = new DesignServicePort.Actor(1, 2, "DESIGNER");
        assertThrows(IllegalArgumentException.class,
                () -> new Request<>("platform", 0, actor, Operation.LIST_CASE_STYLES, "read", "nonce", new Empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new Request<>("platform", 3, null, Operation.LIST_CASE_STYLES, "read", "nonce", new Empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new Request<>("platform", 3, actor, Operation.CREATE_PROJECT, " ", "nonce", new Empty()));
    }

    @Test
    void settlementDoesNotTreatJobProgressAsFinancialVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(1, 0, "RUNNING", false, 1, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(1, 2, "SUCCEEDED", true, 1, 2, 0, 0, 0, 0, 0, 0));
        var facts = new Settlement(1, 2, "SUCCEEDED", true, 2, 1, 10, 4, 6, 1, 1, 0);
        assertEquals(2, facts.version());
        assertEquals(1, facts.validResultCount());
    }
}
