package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.signature.core.redis.ApiSignatureRedisDAO;
import cn.iocoder.yudao.module.design.capability.CapabilityException;
import cn.iocoder.yudao.module.design.capability.CapabilityOrganizationMapping;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilityKeyring;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import cn.iocoder.yudao.module.franchise.api.capability.security.ReplayWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real mapping SQL and authentication filters; isolates exception classification without changing scope policy. */
class CapabilityMappingAuthenticationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1760000000), ZoneOffset.UTC);
    private final byte[] key = new byte[32];
    private JdbcTemplate jdbc;
    private CapabilityOrganizationMapping mapping;
    private ApiSignatureRedisDAO nonces;

    @BeforeEach
    void setUp() {
        new SecureRandom().nextBytes(key);
        var source = new SimpleDriverDataSource(new org.h2.Driver(), "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE design_capability_organization (caller_app_id varchar(80), org_id bigint, "
                + "tenant_id bigint, service_account_id bigint, active boolean)");
        jdbc.update("INSERT INTO design_capability_organization VALUES ('platform-b',42,888,8000000000000000017,true)");
        mapping = new CapabilityOrganizationMapping(source);
        nonces = mock(ApiSignatureRedisDAO.class);
        when(nonces.setNonce(anyString(), anyString(), anyInt(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void activeMappingAuthenticatesWithItsPersistedIdentity() throws Exception {
        assertEquals(204, authenticate(mapping).getStatus());
    }

    @Test
    void disabledRealMappingReturnsForbiddenInsteadOfUnavailable() throws Exception {
        jdbc.update("UPDATE design_capability_organization SET active=false WHERE org_id=42");
        CapabilityException denied = assertThrows(CapabilityException.class, () -> mapping.resolve("platform-b", 42));
        assertEquals(403, denied.getHttpStatus());
        assertEquals(403, denied.getCode());
        var response = authenticate(mapping);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":403"));
    }

    @Test
    void missingRealMappingAlsoReturnsForbidden() throws Exception {
        jdbc.update("DELETE FROM design_capability_organization WHERE org_id=42");
        assertEquals(403, authenticate(mapping).getStatus());
    }

    @Test
    void databaseConnectionFailureRemainsUnavailable() throws Exception {
        var brokenSource = mock(DataSource.class);
        when(brokenSource.getConnection()).thenThrow(new SQLException("Database unavailable"));
        assertEquals(503, authenticate(new CapabilityOrganizationMapping(brokenSource)).getStatus());
        verify(brokenSource).getConnection();
    }

    @Test
    void redisFailureRemainsUnavailableAndDoesNotInvokeMapping() throws Exception {
        when(nonces.setNonce(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        var observedMapping = spy(mapping);
        assertEquals(503, authenticate(observedMapping).getStatus());
        verify(observedMapping, never()).resolveRequest(any());
    }

    @Test
    void unrelatedRuntimeAndNonForbiddenCapabilityErrorsRemainUnavailable() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new IllegalStateException("Mapping failed"),
                CapabilityException.conflict(), new CapabilityException(500, 403, "Storage failure"),
                new CapabilityException(403, 500, "Non-authorization error")}) {
            CallerOrganizationResolver resolver = (caller, org) -> { throw failure; };
            assertEquals(503, authenticate(resolver).getStatus(), failure.getClass().getSimpleName());
        }
    }

    private MockHttpServletResponse authenticate(CallerOrganizationResolver resolver) throws Exception {
        byte[] body = ("{\"callerAppId\":\"platform-b\",\"orgId\":42,\"actor\":{\"actorUserId\":7,"
                + "\"actorPostId\":9,\"actorRoleCode\":\"OWNER\"},\"operation\":\"LIST_PROJECTS\","
                + "\"requestKey\":\"new-read\",\"nonce\":\"nonce-000000000001\",\"payload\":{\"cursor\":null,\"limit\":10}}")
                .getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", Operation.LIST_PROJECTS.path());
        request.setContentType("application/json; charset=utf-8");
        request.setContent(body);
        request.addHeader(CapabilitySignature.KEY_ID_HEADER, "key-1");
        request.addHeader(CapabilitySignature.TIMESTAMP_HEADER, "1760000000");
        request.addHeader(CapabilitySignature.SIGNATURE_HEADER,
                CapabilitySignature.sign(key, "1760000000", "POST", request.getRequestURI(), body));
        var authenticator = new CapabilityRequestAuthenticator(new CapabilityKeyring(Map.of("key-1",
                new CapabilityKeyring.Key("platform-b", key, Set.of(Operation.LIST_PROJECTS.name())))),
                new ReplayWindow(300, 5), CLOCK, nonces, resolver, 4096);
        var response = new MockHttpServletResponse();
        new CapabilityIngressFilter(authenticator).doFilter(request, response, (bounded, output) ->
                new CapabilityAuthenticationFilter().doFilter(bounded, output, (authenticated, result) -> {
                    assertEquals(new ServicePrincipal("platform-b", 42, 888, 8000000000000000017L),
                            CapabilityAuthenticationFilter.requirePrincipal((jakarta.servlet.http.HttpServletRequest) authenticated));
                    ((jakarta.servlet.http.HttpServletResponse) result).setStatus(204);
                }));
        return response;
    }
}
