package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.PUBLICATION_URL_TAKEN;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.WORK_INVALID;

/**
 * 手动发布登记状态机：新记录只从 publishing 开始，核验后登记外部发布链接。
 * 历史 scheduled 数据继续保留，可由本人显式核验后登记；服务不执行自动发布。
 */
@Service
public class ContentPublicationService {

    private final FrContentPublicationMapper publications;
    private final ContentDraftService drafts;
    private final cn.iocoder.yudao.module.franchise.org.FranchiseCommands commands;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final ContentPublisherAccountService publisherAccounts;

    public ContentPublicationService(FrContentPublicationMapper publications, ContentDraftService drafts,
                                     cn.iocoder.yudao.module.franchise.org.FranchiseCommands commands,
                                     org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                     ContentPublisherAccountService publisherAccounts) {
        this.publications = publications;
        this.drafts = drafts;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
        this.publisherAccounts = publisherAccounts;
    }

    /** 幂等入口：duplicate 重放返回既有（POST /content/publications）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentPublicationDO createFromPost(cn.iocoder.yudao.module.platform.org.PostSnapshot post,
                                                 Map<String, Object> body, String idempotencyKey) {
        requireManualPublication(body);
        var replay = commands.replayed(post, idempotencyKey, body);
        if (replay.isPresent()) {
            return requireOwned(post.orgId(), post.userId(), replay.get().getAggregateId());
        }
        FrContentPublicationDO row = create(post.orgId(), post.userId(), body);
        commands.record(post, "fr_content_publication", row.getId(), 1, "content-publication-create",
                body, idempotencyKey);
        return row;
    }

    public FrContentPublicationDO create(long companyId, long userId, Map<String, Object> body) {
        requireManualPublication(body);
        long workId = requiredLong(body.get("workId"), "workId");
        String platform = requiredPlatform(body.get("platform"));
        Long publisherAccountId = body.containsKey("publisherAccountId") ? publisherAccountId(body.get("publisherAccountId")) : null;
        // Legacy clients keep their explicitly manual identity; new clients use the authorized stable directory ID.
        String accountId = publisherAccountId == null ? requiredAccountId(body.get("accountId"))
                : publisherAccounts.requireUsable(companyId, userId, publisherAccountId, platform).displayName();
        var work = drafts.workOf(companyId, workId);
        if (!Objects.equals(work.getAuthorUserId(), userId)) {
            throw exception(WORK_INVALID);
        }
        String status = "publishing";
        OffsetDateTime now = OffsetDateTime.now();
        FrContentPublicationDO row = new FrContentPublicationDO()
                .setId(IdWorker.getId())
                .setCompanyId(companyId)
                .setAuthorUserId(userId)
                .setWorkId(workId)
                .setTitle(clip(body.get("title") == null ? "发布" : String.valueOf(body.get("title")), 200))
                .setPublishText(body.get("publishText") == null ? null : String.valueOf(body.get("publishText")))
                .setTags(body.get("tags") == null ? null : clip(String.valueOf(body.get("tags")), 500))
                .setCoverAssetId(body.get("coverAssetId") == null ? null
                        : Long.parseLong(String.valueOf(body.get("coverAssetId"))))
                .setAccountId(accountId)
                .setPublisherAccountId(publisherAccountId)
                .setPlatform(platform)
                .setScheduledAt(null)
                .setStatus(status)
                .setVersions(versionsJson(1, status))
                .setCreateTime(now)
                .setUpdateTime(now);
        row.setSignature(signatureOf(row));
        // JSONB 列（versions）经 JdbcTemplate CAST 写入（MyBatis String 直写 jsonb 会 42804）
        int inserted = jdbcTemplate.update(
                    "INSERT INTO fr_content_publication (id, company_id, author_user_id, work_id, title, "
                            + "publish_text, tags, cover_asset_id, account_id, publisher_account_id, platform, scheduled_at, status, "
                            + "versions, signature, create_time, update_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?) "
                            + (publisherAccountId == null
                                ? "ON CONFLICT (work_id, platform, account_id) WHERE status <> 'failed' AND publisher_account_id IS NULL DO NOTHING"
                                : "ON CONFLICT (work_id, publisher_account_id) WHERE status <> 'failed' AND publisher_account_id IS NOT NULL DO NOTHING"),
                    row.getId(), companyId, userId, workId, row.getTitle(), row.getPublishText(), row.getTags(),
                    row.getCoverAssetId(), accountId, publisherAccountId, platform, null, status,
                    row.getVersions(), row.getSignature(), now, now);
        if (inserted == 0) {
            // DO NOTHING keeps PostgreSQL's transaction usable for this authorized recovery lookup.
            LambdaQueryWrapper<FrContentPublicationDO> existingQuery = new LambdaQueryWrapper<FrContentPublicationDO>()
                    .eq(FrContentPublicationDO::getCompanyId, companyId)
                    .eq(FrContentPublicationDO::getAuthorUserId, userId)
                    .eq(FrContentPublicationDO::getWorkId, workId)
                    .eq(FrContentPublicationDO::getPlatform, platform)
                    .ne(FrContentPublicationDO::getStatus, "failed");
            if (publisherAccountId == null) existingQuery.isNull(FrContentPublicationDO::getPublisherAccountId)
                    .eq(FrContentPublicationDO::getAccountId, accountId);
            else existingQuery.eq(FrContentPublicationDO::getPublisherAccountId, publisherAccountId);
            FrContentPublicationDO existing = publications.selectOne(existingQuery);
            if (existing == null) {
                throw invalid("发布记录状态已变化，请刷新发布记录后重试");
            }
            throw new ContentPublicationConflict(existing, sameInput(existing, row));
        }
        return row;
    }

    /** 回执链接：published 且链接全局唯一（1_114_000_003）；幂等重放同链接返回既有序。 */
    public FrContentPublicationDO link(long companyId, long userId, long publicationId, String publishedUrl) {
        FrContentPublicationDO row = requireOwned(companyId, userId, publicationId);
        if (publishedUrl == null || publishedUrl.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        if ("published".equals(row.getStatus()) && publishedUrl.equals(row.getPublishedUrl())) {
            return row; // 幂等重放
        }
        int updated;
        try {
            updated = jdbcTemplate.update(
                    "UPDATE fr_content_publication SET status = 'published', published_url = ?, published_at = now(), "
                            + "versions = CAST(? AS jsonb), update_time = now() "
                            + "WHERE id = ? AND status = 'verify'",
                    publishedUrl.trim(), bumpVersions(row, "published"), publicationId);
        } catch (DuplicateKeyException e) {
            throw exception(PUBLICATION_URL_TAKEN);
        }
        if (updated == 0) {
            throw exception(INPUT_INVALID, "仅「待核验」状态的发布可回执链接");
        }
        return requireOwned(companyId, userId, publicationId);
    }

    /** 人工确认已在外部平台发布：新 publishing 或历史 scheduled 转待回执核验。 */
    public FrContentPublicationDO verify(long companyId, long userId, long publicationId) {
        transition(companyId, userId, publicationId, "publishing", "verify");
        return requireOwned(companyId, userId, publicationId);
    }

    public FrContentPublicationDO fail(long companyId, long userId, long publicationId, String reason) {
        FrContentPublicationDO row = requireOwned(companyId, userId, publicationId);
        jdbcTemplate.update(
                "UPDATE fr_content_publication SET status = 'failed', failure_reason = ?, "
                        + "versions = CAST(? AS jsonb), update_time = now() "
                        + "WHERE id = ? AND status = 'publishing'",
                clip(reason, 500), bumpVersions(row, "failed"), publicationId);
        return requireOwned(companyId, userId, publicationId);
    }

    public java.util.List<FrContentPublicationDO> list(long companyId, Long userId, String status) {
        return publications.selectList(new LambdaQueryWrapper<FrContentPublicationDO>()
                .eq(FrContentPublicationDO::getCompanyId, companyId)
                .eq(userId != null, FrContentPublicationDO::getAuthorUserId, userId)
                .eq(status != null && !status.isBlank(), FrContentPublicationDO::getStatus, status)
                .orderByDesc(FrContentPublicationDO::getCreateTime));
    }

    /** Published record playback is visible within the same organization, matching the publication list scope. */
    public FrContentPublicationDO requirePublishedInCompany(long companyId, long publicationId) {
        FrContentPublicationDO row = publications.selectById(publicationId);
        if (row == null || row.getCompanyId() != companyId || !"published".equals(row.getStatus())) {
            throw exception(WORK_INVALID);
        }
        return row;
    }

    public FrContentPublicationDO requireOwned(long companyId, long userId, long publicationId) {
        FrContentPublicationDO row = publications.selectById(publicationId);
        if (row == null || row.getCompanyId() != companyId || row.getAuthorUserId() != userId) {
            throw exception(WORK_INVALID);
        }
        return row;
    }

    private void transition(long companyId, long userId, long publicationId, String from, String to) {
        FrContentPublicationDO row = requireOwned(companyId, userId, publicationId);
        jdbcTemplate.update(
                "UPDATE fr_content_publication SET status = ?, scheduled_at = NULL, "
                        + "versions = CAST(? AS jsonb), update_time = now() "
                        + "WHERE id = ? AND status IN (?, 'scheduled')",
                to, bumpVersions(row, to), publicationId, from);
    }

    /** 签名防篡改（direct-v1）：内容指纹冻结于创建时（work/account/title/text/platform/scheduledAt）。 */
    static String signatureOf(FrContentPublicationDO row) {
        String canonical = String.join("|",
                String.valueOf(row.getWorkId()), row.getPublisherAccountId() == null ? row.getAccountId()
                        : "registered:" + row.getPublisherAccountId(), row.getTitle(),
                row.getPublishText() == null ? "" : row.getPublishText(), row.getPlatform(),
                row.getScheduledAt() == null ? "" : row.getScheduledAt().toString());
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(canonical);
    }

    private static String versionsJson(int version, String status) {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("version", version);
        versions.put("status", status);
        return JsonUtils.toJsonString(versions);
    }

    private static String bumpVersions(FrContentPublicationDO row, String status) {
        Map<String, Object> versions = new LinkedHashMap<>();
        if (row.getVersions() != null) {
            versions.putAll(JsonUtils.parseObject(row.getVersions(), Map.class));
        }
        int version = 1;
        Object parsed = versions.get("version");
        if (parsed instanceof Number number) {
            version = number.intValue();
        }
        if (row.getScheduledAt() != null) {
            // DB 约束要求非 scheduled 状态清空主列；旧计划时间留在版本元数据中供追溯。
            versions.putIfAbsent("originalScheduledAt", row.getScheduledAt().toString());
        }
        versions.put("version", version + 1);
        versions.put("status", status);
        return JsonUtils.toJsonString(versions);
    }

    private static String requiredAccountId(Object value) {
        if (value == null) {
            throw invalid("请填写实际发布账号名称");
        }
        String accountId = String.valueOf(value).trim();
        if (accountId.isEmpty() || accountId.length() > 64) {
            throw invalid("发布账号名称须为 1–64 个字符");
        }
        return accountId;
    }

    private static long publisherAccountId(Object value) {
        String text = value == null ? "" : value.toString();
        try {
            if (!text.matches("[1-9][0-9]{0,18}")) throw invalid("请选择关联的发布账号");
            return Long.parseLong(text);
        } catch (NumberFormatException error) { throw invalid("发布账号编号无效，请刷新账号列表"); }
    }

    private static String requiredPlatform(Object value) {
        String platform = value == null ? "" : String.valueOf(value).trim();
        if (!"douyin".equals(platform) && !"channels".equals(platform)) {
            throw invalid("请选择有效的发布平台（抖音或视频号）");
        }
        return platform;
    }

    /** 上线策略只允许人工登记；必须在任何查询、幂等读取或写入之前执行。 */
    private static void requireManualPublication(Map<String, Object> body) {
        if (body == null) {
            throw invalid("发布登记内容不能为空");
        }
        Object scheduledAt = body.get("scheduledAt");
        if (scheduledAt != null && !String.valueOf(scheduledAt).isBlank()) {
            throw invalid("当前仅支持手动发布登记，请取消定时发布设置");
        }
    }

    private static long requiredLong(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw invalid("请先选择已保存的作品");
        }
        try {
            long id = Long.parseLong(String.valueOf(value));
            if (id <= 0) throw invalid("作品编号无效，请重新选择作品");
            return id;
        } catch (NumberFormatException e) {
            throw invalid("作品编号无效，请重新选择作品");
        }
    }

    private static boolean sameInput(FrContentPublicationDO existing, FrContentPublicationDO requested) {
        return Objects.equals(existing.getTitle(), requested.getTitle())
                && Objects.equals(empty(existing.getPublishText()), empty(requested.getPublishText()))
                && Objects.equals(empty(existing.getTags()), empty(requested.getTags()))
                && Objects.equals(existing.getCoverAssetId(), requested.getCoverAssetId())
                && existing.getScheduledAt() == null;
    }

    private static String empty(String value) { return value == null ? "" : value; }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid(String message) {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(INPUT_INVALID.getCode(), message);
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

}
