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

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.PUBLICATION_URL_TAKEN;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.WORK_INVALID;

/**
 * 发布记录 direct-v1 状态机（D-1.5-11，v3 蓝本照搬）：
 * 五态 publishing/published/verify/failed/scheduled；回执链接非空唯一（1_114_000_003）；
 * (work_id, account_id) 非失败防重（部分唯一索引）；定时≥1 分钟；签名防篡改（创建时冻结）。
 */
@Service
public class ContentPublicationService {

    /** v3 蓝本：定时发布须至少 1 分钟后（F4）。 */
    private static final long MIN_SCHEDULE_AHEAD_SECONDS = 60;

    private final FrContentPublicationMapper publications;
    private final ContentDraftService drafts;
    private final cn.iocoder.yudao.module.franchise.org.FranchiseCommands commands;

    public ContentPublicationService(FrContentPublicationMapper publications, ContentDraftService drafts,
                                     cn.iocoder.yudao.module.franchise.org.FranchiseCommands commands) {
        this.publications = publications;
        this.drafts = drafts;
        this.commands = commands;
    }

    /** 幂等入口：duplicate 重放返回既有（POST /content/publications）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentPublicationDO createFromPost(cn.iocoder.yudao.module.platform.org.PostSnapshot post,
                                                 Map<String, Object> body, String idempotencyKey) {
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
        long workId = requiredLong(body.get("workId"), "workId");
        drafts.workOf(companyId, workId); // 作品属主校验（WORK_INVALID）
        String accountId = requiredText(body.get("accountId"), 64, "accountId");
        String platform = requiredText(body.get("platform"), 32, "platform");
        String status = "publishing";
        OffsetDateTime scheduledAt = null;
        if (body.get("scheduledAt") != null && !String.valueOf(body.get("scheduledAt")).isBlank()) {
            scheduledAt = OffsetDateTime.parse(String.valueOf(body.get("scheduledAt")));
            if (scheduledAt.isBefore(OffsetDateTime.now().plusSeconds(MIN_SCHEDULE_AHEAD_SECONDS))) {
                throw exception(INPUT_INVALID);
            }
            status = "scheduled";
        }
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
                .setPlatform(platform)
                .setScheduledAt(scheduledAt)
                .setStatus(status)
                .setVersions(versionsJson(1, status));
        row.setSignature(signatureOf(row));
        try {
            publications.insert(row);
        } catch (DuplicateKeyException e) {
            throw exception(INPUT_INVALID, "该作品在此账号已有进行中的发布记录");
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
            updated = publications.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrContentPublicationDO>()
                    .eq(FrContentPublicationDO::getId, publicationId)
                    .eq(FrContentPublicationDO::getStatus, "verify")
                    .set(FrContentPublicationDO::getStatus, "published")
                    .set(FrContentPublicationDO::getPublishedUrl, publishedUrl.trim())
                    .set(FrContentPublicationDO::getPublishedAt, OffsetDateTime.now())
                    .set(FrContentPublicationDO::getVersions, bumpVersions(row, "published")));
        } catch (DuplicateKeyException e) {
            throw exception(PUBLICATION_URL_TAKEN);
        }
        if (updated == 0) {
            throw exception(INPUT_INVALID, "仅「待核验」状态的发布可回执链接");
        }
        return requireOwned(companyId, userId, publicationId);
    }

    /** 演示已发布（v3 F6）：publishing 直转 verify（等回执）。 */
    public FrContentPublicationDO verify(long companyId, long userId, long publicationId) {
        transition(companyId, userId, publicationId, "publishing", "verify");
        return requireOwned(companyId, userId, publicationId);
    }

    public FrContentPublicationDO fail(long companyId, long userId, long publicationId, String reason) {
        FrContentPublicationDO row = requireOwned(companyId, userId, publicationId);
        publications.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrContentPublicationDO>()
                .eq(FrContentPublicationDO::getId, publicationId)
                .eq(FrContentPublicationDO::getStatus, "publishing")
                .set(FrContentPublicationDO::getStatus, "failed")
                .set(FrContentPublicationDO::getFailureReason, clip(reason, 500))
                .set(FrContentPublicationDO::getVersions, bumpVersions(row, "failed")));
        return requireOwned(companyId, userId, publicationId);
    }

    public java.util.List<FrContentPublicationDO> list(long companyId, Long userId, String status) {
        return publications.selectList(new LambdaQueryWrapper<FrContentPublicationDO>()
                .eq(FrContentPublicationDO::getCompanyId, companyId)
                .eq(userId != null, FrContentPublicationDO::getAuthorUserId, userId)
                .eq(status != null && !status.isBlank(), FrContentPublicationDO::getStatus, status)
                .orderByDesc(FrContentPublicationDO::getCreateTime));
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
        publications.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrContentPublicationDO>()
                .eq(FrContentPublicationDO::getId, publicationId)
                .eq(FrContentPublicationDO::getStatus, from)
                .set(FrContentPublicationDO::getStatus, to)
                .set(FrContentPublicationDO::getVersions, bumpVersions(row, to)));
    }

    /** 签名防篡改（direct-v1）：内容指纹冻结于创建时（work/account/title/text/platform/scheduledAt）。 */
    static String signatureOf(FrContentPublicationDO row) {
        String canonical = String.join("|",
                String.valueOf(row.getWorkId()), row.getAccountId(), row.getTitle(),
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
        int version = 1;
        if (row.getVersions() != null) {
            Object parsed = JsonUtils.parseObject(row.getVersions(), Map.class).get("version");
            if (parsed instanceof Number number) {
                version = number.intValue();
            }
        }
        return versionsJson(version + 1, status);
    }

    private static String requiredText(Object value, int max, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw exception(INPUT_INVALID);
        }
        return clip(String.valueOf(value), max);
    }

    private static long requiredLong(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw exception(INPUT_INVALID);
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

}
