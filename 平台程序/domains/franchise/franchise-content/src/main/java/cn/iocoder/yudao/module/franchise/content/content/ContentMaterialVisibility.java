package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.AssetVisibilityPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;

@Service
public class ContentMaterialVisibility implements AssetVisibilityPort {
    private final JdbcTemplate jdbc;
    public ContentMaterialVisibility(DataSource ds) { jdbc = new JdbcTemplate(ds); }

    @Override public void requireReadable(long tenant, AssetAccessPort.Actor actor, long id) {
        // Background access is reached only through a validated persisted task and service-account mapping.
        if (actor.userId() == 0 && actor.postId() == 0) return;
        var rows = jdbc.queryForList("SELECT COALESCE(p.author_user_id,m.author_user_id) AS author,"
                + " COALESCE(p.visibility,m.visibility) AS visibility FROM fr_content_material m"
                + " LEFT JOIN fr_content_material p ON p.asset_id=m.parent_asset_id"
                + " WHERE m.asset_id=? AND m.tenant_id=? AND m.company_id=?", id, tenant, actor.orgId());
        if (!rows.isEmpty() && ((Number) rows.get(0).get("author")).longValue() != actor.userId()
                && !"COMPANY".equals(rows.get(0).get("visibility"))) throw exception(RESOURCE_FORBIDDEN);
    }
}
