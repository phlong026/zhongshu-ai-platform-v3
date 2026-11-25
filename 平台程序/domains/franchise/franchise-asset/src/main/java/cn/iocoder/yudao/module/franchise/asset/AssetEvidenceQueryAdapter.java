package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads acceptance metadata only. It neither creates service accounts nor issues file URLs. */
@Component
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class AssetEvidenceQueryAdapter implements AssetEvidenceQueryPort {
    private final NamedParameterJdbcTemplate jdbc;
    public AssetEvidenceQueryAdapter(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    @Override public Map<Long, Long> acceptedOwners(long tenant, Set<Long> assetIds, Set<String> types) {
        if (!Objects.equals(TenantContextHolder.getTenantId(), tenant)) return Map.of();
        if (assetIds.isEmpty() || types.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT a.id,a.org_id FROM fr_asset a JOIN system_dept d ON d.id=a.org_id
                WHERE a.tenant_id=:tenant AND d.tenant_id=:tenant AND d.status=0 AND d.deleted=0
                  AND a.id IN (:ids) AND a.asset_type IN (:types) AND NOT a.deleted
                  AND a.upload_status='ACCEPTED' AND a.object_key IS NOT NULL
                  AND a.accepted_size_bytes>0 AND a.accepted_sha256 ~ '^[0-9a-f]{64}$'
                """, Map.of("tenant", tenant, "ids", assetIds, "types", types), (rs, n) ->
                Map.entry(rs.getLong("id"), rs.getLong("org_id")))
                .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
