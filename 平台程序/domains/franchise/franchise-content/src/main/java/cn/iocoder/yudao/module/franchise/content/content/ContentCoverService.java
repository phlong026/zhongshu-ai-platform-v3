package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.CoverImageFetcher;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.COVER_GENERATION_RETIRED;

/** Reads pre-retirement requests. Only an already-issued image receipt can be archived. */
@Service
public class ContentCoverService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UNKNOWN = "历史封面结果待核实，请联系管理员核对原记录";
    private final JdbcTemplate jdbc;
    private final AssetAccessPort assets;
    private final StoreServiceAccountPort accounts;
    private final ContentProviderProperties properties;
    private final CoverImageFetcher images;

    public ContentCoverService(JdbcTemplate jdbc, AssetAccessPort assets, StoreServiceAccountPort accounts,
                               ContentProviderProperties properties, CoverImageFetcher images) {
        this.jdbc = jdbc; this.assets = assets; this.accounts = accounts; this.properties = properties;
        this.images = images;
    }
    private record Row(long id, String hash, String status, String providerUrl,
                       Long assetId, String failure, Instant created) { }
    private static final RowMapper<Row> MAPPER = (rs, index) -> new Row(rs.getLong("id"), rs.getString("request_hash"),
            rs.getString("status"), rs.getString("provider_url"),
            rs.getObject("cover_asset_id", Long.class), rs.getString("failure_reason"), rs.getTimestamp("create_time").toInstant());

    public Map<String, Object> recover(PostSnapshot post, String key, Map<String, Object> body) {
        long tenant = tenant();
        if (key == null || !key.matches("[A-Za-z0-9:_.-]{1,100}")) throw invalid("封面请求标识无效，请重新进入页面");
        Row row = requireOriginal(tenant, post, key);
        String template = text(body, "templateId", 200), title = text(body, "title", 200);
        String canvas = text(body, "canvasPreset", 4);
        if (!Set.of("9:16", "3:4").contains(canvas)) throw invalid("请选择竖版或图文封面比例");
        String hash = hash(List.of(template, title, canvas));
        if (!hash.equals(row.hash())) throw invalid("此封面请求的内容已变化，请先核对原生成结果");
        var actor = new AssetAccessPort.Actor(post.orgId(), accounts.requireServiceAccount(post.orgId()), post.userId(), post.postId());
        if ("ARCHIVE_PENDING".equals(row.status())) {
            archive(row, actor);
            row = requireOriginal(tenant, post, key);
        }
        return view(row, actor);
    }

    private void archive(Row row, AssetAccessPort.Actor actor) {
        String lease = UUID.randomUUID().toString();
        int claimed = jdbc.update("UPDATE fr_content_cover_request SET archive_token=?,archive_until=?"
                        + " WHERE id=? AND status='ARCHIVE_PENDING' AND (archive_until IS NULL OR archive_until<CURRENT_TIMESTAMP)",
                lease, Timestamp.from(Instant.now().plusSeconds(180)), row.id());
        if (claimed != 1) return;
        try {
            var image = images.fetch(row.providerUrl());
            long asset = assets.importContentImage(actor, image.bytes(), image.mimeType());
            jdbc.update("UPDATE fr_content_cover_request SET cover_asset_id=?,status='READY',failure_reason=NULL,"
                            + "archive_token=NULL,archive_until=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND archive_token=?",
                    asset, row.id(), lease);
        } catch (AssetAccessPort.ContentRejectedException error) {
            jdbc.update("UPDATE fr_content_cover_request SET status='FAILED',failure_reason=?,archive_token=NULL,archive_until=NULL,"
                            + "update_time=CURRENT_TIMESTAMP WHERE id=? AND archive_token=?",
                    "原封面未通过图片校验，可上传封面图片", row.id(), lease);
        } catch (RuntimeException error) {
            jdbc.update("UPDATE fr_content_cover_request SET archive_token=NULL,archive_until=NULL,update_time=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND archive_token=?", row.id(), lease);
        }
    }

    private Map<String, Object> view(Row row, AssetAccessPort.Actor actor) {
        String status = row.status();
        if ("PROCESSING".equals(status) && row.created().isBefore(Instant.now().minusSeconds(properties.getHifly().getTimeoutSeconds() + 120L)))
            status = "UNCERTAIN"; // No reset: the original request may still return and archive safely.
        var view = new LinkedHashMap<String, Object>();
        view.put("requestId", Long.toString(row.id())); view.put("status", status);
        view.put("terminal", Set.of("READY", "FAILED").contains(status));
        view.put("message", switch (status) {
            case "READY" -> "原封面已保存";
            case "FAILED" -> row.failure() == null ? "原封面未生成，可上传封面图片" : row.failure();
            case "UNCERTAIN" -> UNKNOWN;
            case "ARCHIVE_PENDING" -> "封面已生成，保存暂未完成，请继续核对";
            default -> "历史封面请求待核对";
        });
        if ("READY".equals(status) && row.assetId() != null) {
            // The asset number is durable; temporary download links are never the stored cover identity.
            assets.contentMetadata(actor, row.assetId());
            view.put("coverAssetId", Long.toString(row.assetId()));
        }
        return view;
    }

    private Row requireOriginal(long tenant, PostSnapshot post, String key) {
        return jdbc.query("SELECT * FROM fr_content_cover_request WHERE tenant_id=? AND company_id=?"
                        + " AND author_user_id=? AND author_post_id=? AND idempotency_key=?", MAPPER,
                tenant, post.orgId(), post.userId(), post.postId(), key).stream().findFirst().orElseThrow(() -> exception(COVER_GENERATION_RETIRED));
    }
    private static long tenant() {
        Long id = TenantContextHolder.getTenantId();
        if (id == null || id < 0) throw exception(RESOURCE_FORBIDDEN);
        return id;
    }
    private static String text(Map<String, Object> body, String field, int max) {
        if (body == null || !(body.get(field) instanceof String value) || value.isBlank() || value.trim().length() > max)
            throw invalid("封面模板、标题或比例无效，请检查后重试");
        return value.trim();
    }
    private static ServiceException invalid(String message) { return new ServiceException(1_114_000_001, message); }
    private static String hash(List<String> value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (java.security.NoSuchAlgorithmException | JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
