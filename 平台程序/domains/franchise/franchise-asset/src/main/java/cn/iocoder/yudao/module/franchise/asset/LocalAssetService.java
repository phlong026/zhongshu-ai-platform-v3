package cn.iocoder.yudao.module.franchise.asset;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort;
import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort.Slot;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** B 自有资产：短事务领取/固化，事务外读取扫描；验证 token 对结果发布做 fencing。 */
@Service
@Slf4j
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class LocalAssetService implements AssetAccessPort {
    private static final long UPLOAD_TTL = 900;
    private static final long DOWNLOAD_TTL = 300;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Long> MAX_BYTES = Map.of(
            "FIN_VOUCHER", 20L << 20, "ENG_DRAWING", 30L << 20, "ENG_SITE_PHOTO", 10L << 20,
            "CONTRACT_ATTACHMENT", 20L << 20, "CONTENT_ASSET", 50L << 20, "DESIGN_REFERENCE", 20L << 20, "DESIGN_DELIVERABLE", 20L << 20,
            "BUSINESS_DOCUMENT", 30L << 20);
    private final AssetSpool spool = new AssetSpool(java.nio.file.Path.of(System.getProperty("platform.asset.spool-dir",
            java.nio.file.Path.of(System.getProperty("user.home"),".zhongshu","asset-spool").toString())),
            Long.getLong("platform.asset.spool-max-bytes",384L << 20));
    @jakarta.annotation.PostConstruct
    void recoverSpool() throws java.io.IOException { spool.recover(); }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectStoragePort storage;
    private final AssetContentScanner scanner;
    private final ContentMediaNormalizer mediaNormalizer = new ContentMediaNormalizer();
    private final CurrentPost posts;
    private final StoreServiceAccountPort accounts;
    private final CommissionDeliverableAccessPort deliverables;
    private java.util.List<cn.iocoder.yudao.module.franchise.api.AssetVisibilityPort> visibilityPolicies = java.util.List.of();
    private cn.iocoder.yudao.module.franchise.api.CustomerWorkflowAssetScopePort workflowScopes;
    private HeadquartersDocumentAccessPort headquartersDocuments;

    @Autowired(required = false)
    public void setHeadquartersDocuments(HeadquartersDocumentAccessPort access) {
        this.headquartersDocuments = access;
    }

    private cn.iocoder.yudao.module.franchise.api.ContractDocumentAccessPort contractScopes;

    @Autowired(required = false)
    public void setContractScopes(cn.iocoder.yudao.module.franchise.api.ContractDocumentAccessPort scopes) {
        this.contractScopes = scopes;
    }

    @Autowired(required = false)
    public void setWorkflowScopes(cn.iocoder.yudao.module.franchise.api.CustomerWorkflowAssetScopePort scopes) {
        this.workflowScopes = scopes;
    }

    @Autowired(required = false)
    public void setVisibilityPolicies(java.util.List<cn.iocoder.yudao.module.franchise.api.AssetVisibilityPort> policies) {
        this.visibilityPolicies = java.util.List.copyOf(policies);
    }

    public LocalAssetService(DataSource dataSource, PlatformTransactionManager transactions,
                             ObjectStoragePort storage, AssetContentScanner scanner,
                             CurrentPost posts, StoreServiceAccountPort accounts) {
        this(dataSource, transactions, storage, scanner, posts, accounts, null);
    }

    @Autowired
    public LocalAssetService(DataSource dataSource, PlatformTransactionManager transactions,
                             ObjectStoragePort storage, AssetContentScanner scanner,
                             CurrentPost posts, StoreServiceAccountPort accounts,
                             @Nullable CommissionDeliverableAccessPort deliverables) {
        this.deliverables = deliverables;
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.storage = storage;
        this.scanner = scanner;
        this.posts = posts;
        this.accounts = accounts;
    }

    private record Asset(long id, long tenant, long org, long account, long user, long post,
                         String type, String mime, long size, String sha, String uploadKey,
                         String objectKey, String status, String reason, String validationToken,
                         long acceptedSize, String acceptedSha) {
        String contentMime() { return "CONTENT_ASSET".equals(type) ? ContentMediaNormalizer.outputMime(mime) : mime; }
    }
    public record DownloadContent(byte[] bytes, String mimeType) { }

    @Override
    public UploadTicketView createUploadTicket(Actor actor, String type, String mime, long size, String sha) {
        long tenant = authorize(actor);
        requireUpload(actor,type,null);
        if (!mimeAllowed(type, mime) || size <= 0 || size > MAX_BYTES.getOrDefault(type, 0L)
                || !isSha256(sha)) throw invalid("上传类型、大小或 SHA-256 无效");
        long id = IdUtil.getSnowflakeNextId();
        String token = token();
        String key = "franchise/" + tenant + "/" + actor.orgId() + "/uploads/" + id + extension(mime);
        String storageUrl = storage.presignUploadUrl(key, UPLOAD_TTL);
        db("issue-upload", id, () -> tx.execute(status -> {
            jdbc.update("INSERT INTO fr_asset (id,tenant_id,org_id,service_account_id,creator_user_id,creator_post_id,"
                            + "asset_type,declared_mime,size_bytes,sha256,upload_key) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id, tenant, actor.orgId(), actor.serviceAccountId(), actor.userId(), actor.postId(),
                    type, mime, size, sha.toLowerCase(java.util.Locale.ROOT), key);
            jdbc.update("INSERT INTO fr_asset_upload_ticket (token_hash,asset_id,tenant_id,org_id,user_id,post_id,expires_at,upload_mode)"
                            + " VALUES (?,?,?,?,?,?,?,?)", hash(token), id, tenant, actor.orgId(), actor.userId(), actor.postId(),
                    Timestamp.from(Instant.now().plusSeconds(UPLOAD_TTL)), storageUrl.startsWith("local://") ? "PROXY" : "DIRECT");
            return null;
        }));
        return new UploadTicketView(id, storageUrl.startsWith("local://")
                ? "/admin-api/franchise/assets/" + id + "/content?token=" + token : storageUrl);
    }

    @Override
    public long importContentImage(Actor actor, byte[] bytes, String mimeType) {
        long tenant = authorize(actor);
        requireChannel(actor, "CONTENT_ASSET");
        if (bytes == null || bytes.length == 0 || bytes.length > (10 << 20)
                || !Set.of("image/png", "image/jpeg").contains(mimeType)) throw invalid("生成封面图片格式或大小无效");
        // The grant stays inside this method; generated bytes use exactly the regular completion scanner.
        var ticket = createUploadTicket(actor, "CONTENT_ASSET", mimeType, bytes.length, hash(bytes));
        Asset asset = owned(tenant, actor, ticket.assetId());
        try (var reservation = spool.reserve(bytes.length); var file = reservation.file()) {
            file.copy(new java.io.ByteArrayInputStream(bytes), bytes.length);
            storage.putObject(asset.uploadKey(), file.path);
            db("import-image", asset.id(), () -> jdbc.update("UPDATE fr_asset_upload_ticket SET consumed_at=CURRENT_TIMESTAMP,"
                    + "completed_at=CURRENT_TIMESTAMP WHERE asset_id=? AND tenant_id=? AND org_id=? AND user_id=? AND post_id=?",
                    asset.id(), tenant, actor.orgId(), actor.userId(), actor.postId()));
        } catch (IOException error) {
            throw invalid("生成封面保存暂未完成，请重试");
        }
        var result = completeUpload(actor, asset.id());
        if ("REJECTED".equals(result.uploadStatus()))
            throw new AssetAccessPort.ContentRejectedException("生成封面未通过图片校验，请重新选择封面");
        if (!"ACCEPTED".equals(result.uploadStatus())) throw invalid("生成封面校验暂未完成，请继续核对");
        return asset.id();
    }

    /** 开发字节入口。读入上限受声明大小约束；先检查票据，再以 CAS 消费，只有赢家允许写对象。 */
    public void receiveUpload(Actor actor, long id, String token, InputStream input) {
        long tenant = authorize(actor);
        Asset asset = owned(tenant, actor, id);
        requireUpload(actor,asset.type(),asset);
        if (!"PENDING".equals(asset.status())) throw exception(RESOURCE_FORBIDDEN);
        if (!validUploadTicket(tenant, actor, id, token)) throw exception(RESOURCE_FORBIDDEN);
        try (var reservation = spool.reserve(asset.size()); var file = reservation.file()) {
            var received = file.copy(input, asset.size());
            if (received.size() != asset.size() || !asset.sha().equals(received.sha())) throw invalid("上传大小或摘要不匹配");
            int consumed = db("consume-upload", id, () -> jdbc.update("UPDATE fr_asset_upload_ticket SET consumed_at=CURRENT_TIMESTAMP"
                            + " WHERE token_hash=? AND asset_id=? AND tenant_id=? AND org_id=? AND user_id=? AND post_id=?"
                            + " AND upload_mode='PROXY' AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP", hash(token), id, tenant,
                    actor.orgId(), actor.userId(), actor.postId()));
            if (consumed != 1) throw exception(RESOURCE_FORBIDDEN);
            try {
                storage.putObject(asset.uploadKey(), file.path);
                db("finish-upload", id, () -> jdbc.update("UPDATE fr_asset_upload_ticket SET completed_at=CURRENT_TIMESTAMP"
                        + " WHERE token_hash=? AND asset_id=? AND tenant_id=? AND org_id=? AND consumed_at IS NOT NULL",
                        hash(token), id, tenant, actor.orgId()));
            } catch (RuntimeException e) {
                log.warn("B asset upload write failed tenant={} org={} asset={} error={}", tenant, actor.orgId(), id, e.getClass().getSimpleName());
                // 写入失败不把未就绪字节送进完整性审查。重试仍必须原票据、原操作者且未过期。
                db("release-upload", id, () -> jdbc.update("UPDATE fr_asset_upload_ticket SET consumed_at=NULL"
                        + " WHERE token_hash=? AND asset_id=? AND tenant_id=? AND org_id=? AND completed_at IS NULL",
                        hash(token), id, tenant, actor.orgId()));
                throw invalid("对象写入失败，请重试上传");
            }
        } catch (AssetSpool.CapacityBusy e) {
            throw invalid("暂存空间繁忙，请使用原上传票据重试");
        } catch (IOException e) {
            log.warn("B asset upload read failed tenant={} org={} asset={} error={}", tenant, actor.orgId(), id, e.getClass().getSimpleName());
            throw invalid("上传读取失败，请重新申请票据");
        }
    }

    private boolean validUploadTicket(long tenant, Actor actor, long id, String token) {
        if (token == null || token.length() != 64) return false;
        return db("check-upload-ticket", id, () -> jdbc.queryForObject("SELECT count(*) FROM fr_asset_upload_ticket"
                        + " WHERE token_hash=? AND asset_id=? AND tenant_id=? AND org_id=? AND user_id=? AND post_id=?"
                        + " AND upload_mode='PROXY' AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP", Integer.class,
                hash(token), id, tenant, actor.orgId(), actor.userId(), actor.postId())) == 1;
    }

    @Override
    public AssetCompleted completeUpload(Actor actor, long id) {
        long tenant = authorize(actor);
        Asset original = owned(tenant, actor, id);
        requireUpload(actor,original.type(),original);
        if ("ACCEPTED".equals(original.status()) || "REJECTED".equals(original.status())) return completed(original);
        String validation = UUID.randomUUID().toString();
        int claimed = db("claim-validation", id, () -> tx.execute(status -> {
            int updated = jdbc.update("UPDATE fr_asset SET upload_status='VALIDATING',validation_token=?,validation_started_at=CURRENT_TIMESTAMP,"
                            + "update_time=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND org_id=? AND deleted=FALSE AND "
                            + "(upload_status='PENDING' OR (upload_status='VALIDATING' AND validation_started_at<?))"
                            + " AND EXISTS (SELECT 1 FROM fr_asset_upload_ticket t WHERE t.asset_id=fr_asset.id"
                            + " AND t.tenant_id=fr_asset.tenant_id AND t.org_id=fr_asset.org_id"
                            + " AND ((t.upload_mode='PROXY' AND t.completed_at IS NOT NULL)"
                            + " OR (t.upload_mode='DIRECT' AND (t.consumed_at IS NOT NULL OR t.expires_at>CURRENT_TIMESTAMP))))",
                    validation, id, tenant, actor.orgId(), Timestamp.from(Instant.now().minusSeconds(600)));
            if (updated == 1) jdbc.update("UPDATE fr_asset_upload_ticket SET consumed_at=COALESCE(consumed_at,CURRENT_TIMESTAMP)"
                    + " WHERE asset_id=? AND tenant_id=? AND org_id=?", id, tenant, actor.orgId());
            return updated;
        }));
        if (claimed != 1) {
            Asset current = owned(tenant, actor, id);
            if (Set.of("VALIDATING", "ACCEPTED", "REJECTED").contains(current.status())) return completed(current);
            throw invalid("资产尚未上传完成，请重试");
        }
        boolean image = original.mime().startsWith("image/");
        boolean media = "CONTENT_ASSET".equals(original.type());
        boolean normalize = image || media;
        long outputLimit = normalize ? MAX_BYTES.get(original.type()) : 0;
        boolean heif = Set.of("image/heic", "image/heif", "image/avif").contains(original.mime());
        try (var reservation=spool.reserve(original.size()+outputLimit*(heif ? 2 : 1)+(media ? 4L << 20 : 0));
             var raw=reservation.file(); var sanitized=normalize ? reservation.file() : null) {
            String rejection = null;
            AssetUploadFile.Integrity verified = null;
            if (!storage.existsWithSize(original.uploadKey(), original.size())) rejection = "SIZE_MISMATCH";
            if (rejection == null) {
                try (var input = storage.getObject(original.uploadKey())) {
                    verified = raw.copy(input, original.size());
                }
                if (verified.size() != original.size()) rejection = "SIZE_MISMATCH";
                else if (!original.sha().equals(verified.sha())) rejection = "SHA256_MISMATCH";
            }
            if (rejection == null) rejection = media
                    ? mediaNormalizer.normalize(original.mime(), raw.path, sanitized.path, outputLimit, scanner)
                    : scanner.scan(original.mime(), raw.path, image ? sanitized.path : null, outputLimit);
            if (rejection == null && normalize) {
                long limit = MAX_BYTES.get(original.type());
                verified = sanitized.integrity(limit);
                if (verified.size() > limit) rejection = "SANITIZED_SIZE_LIMIT";
            }
            String acceptedKey = null;
            if (rejection == null) {
                acceptedKey = "franchise/" + tenant + "/" + actor.orgId() + "/accepted/" + id + "/" + validation + extension(original.contentMime());
                storage.putObject(acceptedKey, normalize ? sanitized.path : raw.path);
            }
            String finalReason = truncate(rejection, 1024);
            String finalScan = rejection == null ? "PASSED:integrity,magic,pixel,metadata,malware-marker" : truncate(rejection, 2048);
            String finalKey = acceptedKey;
            AssetUploadFile.Integrity accepted = verified;
            String finalStatus = rejection == null ? "ACCEPTED" : "REJECTED";
            int changed = db("finish-validation", id, () -> tx.execute(status -> {
                int updated = jdbc.update("UPDATE fr_asset SET upload_status=?,object_key=?,accepted_size_bytes=?,accepted_sha256=?,"
                                + "rejected_reason=?,scan_result=?,validation_token=NULL,update_time=CURRENT_TIMESTAMP"
                                + " WHERE id=? AND tenant_id=? AND org_id=? AND upload_status='VALIDATING' AND validation_token=?",
                        finalStatus, finalKey, finalKey == null ? null : accepted.size(), finalKey == null ? null : accepted.sha(),
                        finalReason, finalScan, id, tenant, actor.orgId(), validation);
                if (updated == 1) jdbc.update("UPDATE fr_asset_upload_ticket SET consumed_at=COALESCE(consumed_at,CURRENT_TIMESTAMP)"
                        + " WHERE asset_id=? AND tenant_id=? AND org_id=?", id, tenant, actor.orgId());
                return updated;
            }));
            if (changed != 1) throw invalid("校验已被新的处理接管，请重新读取状态");
            if (rejection != null) log.warn("B asset rejected tenant={} org={} asset={} reason={}", tenant, actor.orgId(), id, finalReason);
            return new AssetCompleted(id, finalStatus, finalReason);
        } catch (IOException | RuntimeException e) {
            log.warn("B asset validation failed tenant={} org={} asset={} error={}", tenant, actor.orgId(), id, e.getClass().getSimpleName());
            db("release-validation", id, () -> jdbc.update("UPDATE fr_asset SET upload_status='PENDING',validation_token=NULL,"
                            + "update_time=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND org_id=? AND upload_status='VALIDATING'"
                            + " AND validation_token=?", id, tenant, actor.orgId(), validation));
            throw invalid(e instanceof IOException && e.getMessage() != null && e.getMessage().startsWith("媒体")
                    ? e.getMessage() : "资产校验暂未完成，请重试");
        }
    }

    @Override
    public DownloadUrlView createDownloadUrl(Actor actor, long id) {
        long tenant = authorize(actor);
        Asset asset = accepted(tenant, actor, id);
        requireChannel(actor, asset.type());
        requireContractAssetVisibility(asset);
        String token = issueDownload(tenant, actor, id);
        return new DownloadUrlView("/admin-api/franchise/assets/" + id + "/content?token=" + token,
                Instant.now().plusSeconds(DOWNLOAD_TTL).toEpochMilli());
    }

    @Override
    public DownloadUrlView createContractDownloadUrl(Actor actor, long contractId, long assetId) {
        long tenant = authorize(actor);
        Asset asset = accepted(tenant, actor, assetId);
        requireContractScope(contractId, asset);
        requireChannel(actor, asset.type());
        String token = token();
        db("issue-contract-download", assetId, () -> jdbc.update("INSERT INTO fr_asset_download_ticket"
                        + " (token_hash,asset_id,tenant_id,org_id,user_id,post_id,expires_at,scope_contract_id)"
                        + " VALUES (?,?,?,?,?,?,?,?)", hash(token), assetId, tenant, actor.orgId(), actor.userId(), actor.postId(),
                Timestamp.from(Instant.now().plusSeconds(DOWNLOAD_TTL)), contractId));
        return new DownloadUrlView("/admin-api/franchise/assets/" + assetId + "/content?token=" + token,
                Instant.now().plusSeconds(DOWNLOAD_TTL).toEpochMilli());
    }

    private void requireContractScope(long contractId, Asset asset) {
        if (contractScopes == null || !"CONTRACT_ATTACHMENT".equals(asset.type())) throw exception(RESOURCE_FORBIDDEN);
        contractScopes.requireReadable(posts.require(), contractId, asset.id());
    }
    private void requireContractAssetVisibility(Asset asset) {
        if (!"CONTRACT_ATTACHMENT".equals(asset.type())) return;
        if (contractScopes == null) throw exception(RESOURCE_FORBIDDEN);
        contractScopes.requireAssetReadable(posts.require(), asset.id());
    }

    @Override
    public DownloadUrlView createWorkflowDownloadUrl(Actor actor, long customerId, String stepCode, long assetId) {
        long tenant = authorize(actor);
        requireWorkflowScope(customerId, stepCode, assetId);
        requireWorkflowType(accepted(tenant, actor, assetId));
        String token = token();
        db("issue-workflow-download", assetId, () -> jdbc.update("INSERT INTO fr_asset_download_ticket"
                        + " (token_hash,asset_id,tenant_id,org_id,user_id,post_id,expires_at,scope_customer_id,scope_workflow_step)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)", hash(token), assetId, tenant, actor.orgId(), actor.userId(), actor.postId(),
                Timestamp.from(Instant.now().plusSeconds(DOWNLOAD_TTL)), customerId, stepCode));
        return new DownloadUrlView("/admin-api/franchise/assets/" + assetId + "/content?token=" + token,
                Instant.now().plusSeconds(DOWNLOAD_TTL).toEpochMilli());
    }

    private void requireWorkflowScope(long customerId, String step, long assetId) {
        if (workflowScopes == null) throw exception(RESOURCE_FORBIDDEN);
        workflowScopes.requireReadable(posts.require(), customerId, step, assetId);
    }
    private void requireWorkflowType(Asset asset) {
        if (!java.util.Set.of("BUSINESS_DOCUMENT", "DESIGN_REFERENCE").contains(asset.type())) throw exception(RESOURCE_FORBIDDEN);
    }

    @Override
    public DownloadUrlView createCommissionDownloadUrl(Actor actor, long commissionId, int dispatchSeq, Slot slot) {
        long tenant = authorize(actor);
        var ref = requireScope(commissionId, dispatchSeq, slot);
        scopedAsset(tenant, actor, ref);
        String token = token();
        db("issue-scoped-download", ref.assetId(), () -> jdbc.update("INSERT INTO fr_asset_download_ticket"
                        + " (token_hash,asset_id,tenant_id,org_id,user_id,post_id,expires_at,owner_org_id,"
                        + "scope_commission_id,scope_dispatch_seq,scope_slot) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                hash(token), ref.assetId(), tenant, actor.orgId(), actor.userId(), actor.postId(),
                Timestamp.from(Instant.now().plusSeconds(DOWNLOAD_TTL)), ref.ownerOrgId(), commissionId, dispatchSeq, slot.name()));
        return new DownloadUrlView("/admin-api/franchise/assets/" + ref.assetId() + "/content?token=" + token,
                Instant.now().plusSeconds(DOWNLOAD_TTL).toEpochMilli());
    }

    private CommissionDeliverableAccessPort.DeliverableRef requireScope(long commissionId, int dispatchSeq, Slot slot) {
        if (deliverables == null || slot == null || dispatchSeq <= 0) throw exception(RESOURCE_FORBIDDEN);
        var ref = deliverables.requireReadable(posts.require(), commissionId, dispatchSeq, slot);
        if (ref == null || ref.commissionId() != commissionId || ref.dispatchSeq() != dispatchSeq || ref.slot() != slot)
            throw exception(RESOURCE_FORBIDDEN);
        return ref;
    }

    private Asset scopedAsset(long tenant, Actor consumer, CommissionDeliverableAccessPort.DeliverableRef ref) {
        // owner 仅取自服务端清单。当前 consumer 已授权，不能用伪造 owner 调用 authorize。
        long ownerAccount;
        try {
            // 本组织解析器绑定当前员工组织。属主来自已授权的持久化清单，只读恢复其现有映射，不切岗或建号。
            ownerAccount = accounts.requireExistingServiceAccountForTask(tenant, ref.ownerOrgId());
        } catch (StoreServiceAccountPort.ServiceAccountInactiveException inactive) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        var owner = new Actor(ref.ownerOrgId(), ownerAccount, consumer.userId(), consumer.postId());
        Asset asset;
        if (ref.slot().external()) {
            // The customer may not be readable through CRM by the designated engineer. Only the persisted packet grant
            // supplies this owner; generic BUSINESS_DOCUMENT reads still go through CustomerWorkflowAssetPolicy.
            asset = loadOwned(tenant, ref.ownerOrgId(), ownerAccount, ref.assetId());
            if (asset == null || !"ACCEPTED".equals(asset.status()) || asset.objectKey() == null
                    || asset.acceptedSize() <= 0 || !isSha256(asset.acceptedSha())) throw exception(RESOURCE_FORBIDDEN);
        } else asset = accepted(tenant, owner, ref.assetId());
        String type = ref.slot().external() ? "BUSINESS_DOCUMENT"
                : ref.slot() == Slot.CONFIRMATION ? "CONTRACT_ATTACHMENT" : "DESIGN_DELIVERABLE";
        if (!type.equals(asset.type())) throw exception(RESOURCE_FORBIDDEN);
        return asset;
    }

    public DownloadContent readDownload(Actor actor, long id, String token) {
        long tenant = authorize(actor);
        if (token == null || token.length() != 64) throw exception(RESOURCE_FORBIDDEN);
        var tickets = db("read-download-scope", id, () -> jdbc.queryForList("SELECT owner_org_id,scope_commission_id,scope_dispatch_seq,scope_slot,scope_customer_id,scope_workflow_step,scope_contract_id"
                        + " FROM fr_asset_download_ticket WHERE token_hash=? AND asset_id=? AND tenant_id=? AND org_id=?"
                        + " AND user_id=? AND post_id=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP",
                hash(token), id, tenant, actor.orgId(), actor.userId(), actor.postId()));
        if (tickets.size() != 1) throw exception(RESOURCE_FORBIDDEN);
        var ticket = tickets.get(0);
        Long commissionId = ticket.get("scope_commission_id") instanceof Number n ? n.longValue() : null;
        Long customerId = ticket.get("scope_customer_id") instanceof Number n ? n.longValue() : null;
        Long contractId = ticket.get("scope_contract_id") instanceof Number n ? n.longValue() : null;
        String workflowStep = (String) ticket.get("scope_workflow_step");
        CommissionDeliverableAccessPort.DeliverableRef ref = null;
        Asset asset;
        if (commissionId == null) {
            asset = accepted(tenant, actor, id);
            requireDownloadScope(actor, asset, customerId, workflowStep, contractId);
        } else {
            ref = requireScope(commissionId, ((Number) ticket.get("scope_dispatch_seq")).intValue(), Slot.valueOf((String) ticket.get("scope_slot")));
            if (ref.assetId() != id || ref.ownerOrgId() != ((Number) ticket.get("owner_org_id")).longValue())
                throw exception(RESOURCE_FORBIDDEN);
            asset = scopedAsset(tenant, actor, ref);
        }
        consumeDownload(tenant, actor, id, token);
        try (InputStream input = storage.getObject(asset.objectKey())) {
            byte[] bytes = input.readNBytes(Math.toIntExact(MAX_BYTES.get(asset.type())) + 1);
            if (bytes.length > MAX_BYTES.get(asset.type())) throw invalid("资产大小异常");
            authorize(actor);
            if (ref == null) {
                // Customer ownership may change while storage I/O is in flight; the ticket is not an authorization cache.
                Asset current = accepted(tenant, actor, id);
                requireDownloadScope(actor, current, customerId, workflowStep, contractId);
                if (!java.util.Objects.equals(asset.objectKey(), current.objectKey())) throw exception(RESOURCE_FORBIDDEN);
                if (("BUSINESS_DOCUMENT".equals(current.type()) || "CONTRACT_ATTACHMENT".equals(current.type()))
                        && (bytes.length != current.acceptedSize() || !hash(bytes).equals(current.acceptedSha())))
                    throw invalid("资料与已校验内容不一致");
            } else {
                var current = requireScope(ref.commissionId(), ref.dispatchSeq(), ref.slot());
                if (!ref.equals(current)) throw exception(RESOURCE_FORBIDDEN);
                Asset confirmed = scopedAsset(tenant, actor, current);
                if (!java.util.Objects.equals(asset.objectKey(), confirmed.objectKey())) throw exception(RESOURCE_FORBIDDEN);
                if (current.slot().external() && (bytes.length != confirmed.acceptedSize() || !hash(bytes).equals(confirmed.acceptedSha())))
                    throw invalid("资料与已校验内容不一致");
            }
            return new DownloadContent(bytes, asset.contentMime());
        } catch (IOException | RuntimeException e) {
            if (e instanceof ServiceException serviceException) throw serviceException;
            log.warn("B asset download failed tenant={} org={} asset={} error={}", tenant, actor.orgId(), id, e.getClass().getSimpleName());
            throw invalid("下载暂不可用，请重新申请票据");
        }
    }

    /** Does not issue a transferable URL, create a service account, or impersonate a store post. */
    @Override
    public ReferenceContent readHeadquartersDocument(HeadquartersDocumentAccessPort.Request request) {
        if (headquartersDocuments == null) throw exception(RESOURCE_FORBIDDEN);
        var scope = headquartersDocuments.requireReadable(request);
        Asset asset = headquartersAsset(scope);
        try (InputStream input = storage.getObject(asset.objectKey())) {
            byte[] bytes = input.readNBytes(Math.toIntExact(MAX_BYTES.get(asset.type())) + 1);
            if (bytes.length == 0 || bytes.length != asset.acceptedSize() || !hash(bytes).equals(asset.acceptedSha()))
                throw invalid("资料内容与审核摘要不一致");
            // Recheck after storage I/O: a revoked post, switched identity or removed reference cannot finish a download.
            var currentScope = headquartersDocuments.requireReadable(request);
            if (!scope.equals(currentScope)) throw exception(RESOURCE_FORBIDDEN);
            Asset current = headquartersAsset(currentScope);
            if (!asset.objectKey().equals(current.objectKey()) || current.acceptedSize() != bytes.length
                    || !hash(bytes).equals(current.acceptedSha())) throw exception(RESOURCE_FORBIDDEN);
            return new ReferenceContent(bytes, current.contentMime(), current.acceptedSha());
        } catch (IOException | RuntimeException error) {
            if (error instanceof ServiceException serviceException) throw serviceException;
            log.warn("B HQ document read failed tenant={} org={} asset={} error={}",
                    scope.tenantId(), scope.ownerOrgId(), scope.assetId(), error.getClass().getSimpleName());
            throw invalid("资料读取失败，请重试");
        }
    }

    private Asset headquartersAsset(HeadquartersDocumentAccessPort.Scope scope) {
        var current = posts.require();
        if (scope == null || scope.tenantId() != tenant() || scope.readerUserId() != current.userId()
                || scope.readerPostId() != current.postId() || scope.headquartersOrgId() != current.orgId()
                || !"headquarters".equals(current.orgType())
                || !Set.of("hq-admin", "finance", "finance-lead").contains(current.roleCode())) throw exception(RESOURCE_FORBIDDEN);
        long account = accounts.requireExistingServiceAccountForTask(scope.tenantId(), scope.ownerOrgId());
        Asset asset = loadOwned(scope.tenantId(), scope.ownerOrgId(), account, scope.assetId());
        if (asset == null || !"ACCEPTED".equals(asset.status()) || asset.objectKey() == null
                || !scope.allowedTypes().contains(asset.type()) || !MAX_BYTES.containsKey(asset.type())
                || "CONTENT_ASSET".equals(asset.type()) || asset.acceptedSize() <= 0
                || asset.acceptedSize() > MAX_BYTES.get(asset.type())) throw exception(RESOURCE_FORBIDDEN);
        return asset;
    }

    private void requireDownloadScope(Actor actor, Asset asset, Long customerId, String workflowStep, Long contractId) {
        if (contractId != null) {
            requireChannel(actor, asset.type()); requireContractScope(contractId, asset);
        } else if (customerId != null) {
            requireWorkflowScope(customerId, workflowStep, asset.id()); requireWorkflowType(asset);
        } else {
            requireChannel(actor, asset.type()); requireContractAssetVisibility(asset);
        }
    }

    @Override
    public ReferenceContent readDesignReference(Actor actor, long id) {
        long tenant = authorize(actor);
        Asset asset = accepted(tenant, actor, id);
        requireChannel(actor, asset.type());
        if (!"DESIGN_REFERENCE".equals(asset.type())) throw exception(RESOURCE_FORBIDDEN);
        try (InputStream input = storage.getObject(asset.objectKey())) {
            byte[] bytes = input.readNBytes((20 << 20) + 1);
            var facts = db("read-reference-integrity", id, () -> jdbc.queryForMap(
                    "SELECT accepted_size_bytes,accepted_sha256 FROM fr_asset WHERE id=? AND tenant_id=? AND org_id=? AND deleted=FALSE",
                    id, tenant, actor.orgId()));
            if (bytes.length == 0 || bytes.length > (20 << 20)
                    || !(facts.get("accepted_size_bytes") instanceof Number size) || size.longValue() != bytes.length
                    || !hash(bytes).equals(facts.get("accepted_sha256"))) throw invalid("参考图内容与审核摘要不一致");
            // 在对象读取之后重验，停用、切岗或删除不能借已开始的读取绕过。
            accepted(authorize(actor), actor, id);
            return new ReferenceContent(bytes, asset.mime(), hash(bytes));
        } catch (IOException e) {
            log.warn("B reference read failed tenant={} org={} asset={}", tenant, actor.orgId(), id, e);
            throw invalid("参考图读取失败，请重试");
        } catch (RuntimeException e) {
            if (e instanceof ServiceException) throw e;
            log.warn("B reference storage read failed tenant={} org={} asset={}", tenant, actor.orgId(), id, e);
            throw invalid("参考图读取失败，请重试");
        }
    }

    @Override
    public DownloadUrlView createTaskDownloadUrl(long orgId, long id, String requiredType) {
        // 定时任务没有员工会话；租户仅从持久化组织目录恢复，不能由客户端指定。
        var tenants = db("task-owner", id, () -> jdbc.queryForList(
                "SELECT d.tenant_id FROM pf_org_unit u JOIN system_dept d ON d.id=u.dept_id"
                        + " WHERE u.dept_id=? AND u.org_id=? AND u.kind='ORG' AND u.status='ACTIVE'"
                        + " AND d.status=0 AND d.deleted=0", Long.class, orgId, orgId));
        if (tenants.size() != 1) throw exception(RESOURCE_FORBIDDEN);
        long tenant = tenants.get(0);
        Long activeTenant = TenantContextHolder.getTenantId();
        if (activeTenant != null && activeTenant != tenant) throw exception(RESOURCE_FORBIDDEN);
        long account = accounts.requireExistingServiceAccountForTask(tenant, orgId);
        return serviceDownload(tenant, orgId, account, id, requiredType);
    }

    @Override
    public DownloadUrlView createServiceDownloadUrl(long orgId, long account, long id, String requiredType) {
        long tenant = tenant();
        if (accounts.requireExistingServiceAccountForTask(tenant, orgId) != account) throw exception(RESOURCE_FORBIDDEN);
        return serviceDownload(tenant, orgId, account, id, requiredType);
    }

    private DownloadUrlView serviceDownload(long tenant, long orgId, long account, long id, String requiredType) {
        if (!"CONTENT_ASSET".equals(requiredType)) throw exception(RESOURCE_FORBIDDEN);
        Actor service = new Actor(orgId, account, 0, 0);
        Asset asset = accepted(tenant, service, id);
        if (!requiredType.equals(asset.type())) throw exception(RESOURCE_FORBIDDEN);
        String url = storage.presignDownloadUrl(asset.objectKey(), DOWNLOAD_TTL);
        if (url.startsWith("local://")) throw invalid("外部内容服务需要配置 B 私有对象存储");
        String token = issueDownload(tenant, service, id);
        consumeDownload(tenant, service, id, token);
        return new DownloadUrlView(url, Instant.now().plusSeconds(DOWNLOAD_TTL).toEpochMilli());
    }

    private String issueDownload(long tenant, Actor actor, long id) {
        String token = token();
        db("issue-download", id, () -> jdbc.update("INSERT INTO fr_asset_download_ticket"
                        + " (token_hash,asset_id,tenant_id,org_id,user_id,post_id,expires_at) VALUES (?,?,?,?,?,?,?)",
                hash(token), id, tenant, actor.orgId(), actor.userId(), actor.postId(), Timestamp.from(Instant.now().plusSeconds(DOWNLOAD_TTL))));
        return token;
    }

    private void consumeDownload(long tenant, Actor actor, long id, String token) {
        if (token == null || token.length() != 64) throw exception(RESOURCE_FORBIDDEN);
        int updated = db("consume-download", id, () -> jdbc.update("UPDATE fr_asset_download_ticket SET consumed_at=CURRENT_TIMESTAMP"
                        + " WHERE token_hash=? AND asset_id=? AND tenant_id=? AND org_id=? AND user_id=? AND post_id=?"
                        + " AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP", hash(token), id, tenant,
                actor.orgId(), actor.userId(), actor.postId()));
        if (updated != 1) throw exception(RESOURCE_FORBIDDEN);
    }

    @Override public ContentMetadata contentMetadata(Actor actor, long id) {
        long tenant = authorize(actor);
        Asset row = accepted(tenant, actor, id);
        requireChannel(actor, row.type());
        if (!"CONTENT_ASSET".equals(row.type())) throw exception(RESOURCE_FORBIDDEN);
        return new ContentMetadata(row.contentMime(), row.acceptedSha(), row.acceptedSize());
    }

    @Override public ContentMedia readContentMedia(Actor actor, long id) {
        long tenant = authorize(actor);
        Asset asset = accepted(tenant, actor, id);
        requireChannel(actor, asset.type());
        if (!"CONTENT_ASSET".equals(asset.type()) || asset.user() != actor.userId()) throw exception(RESOURCE_FORBIDDEN);
        long limit = MAX_BYTES.get("CONTENT_ASSET");
        try (var reservation = spool.reserve(limit + 1024 * 1024); var file = reservation.file(); var probe = reservation.file();
             var input = storage.getObject(asset.objectKey())) {
            var integrity = file.copy(input, limit);
            if (integrity.size() != asset.acceptedSize() || !integrity.sha().equals(asset.acceptedSha()))
                throw invalid("素材内容与已校验摘要不一致，请重新上传");
            var facts = mediaNormalizer.inspect(file.path, probe.path);
            accepted(authorize(actor), actor, id);
            return new ContentMedia(java.nio.file.Files.readAllBytes(file.path), asset.contentMime(), integrity.sha(),
                    facts.duration(), facts.width(), facts.height());
        } catch (IOException error) {
            throw invalid("媒体读取或解码暂不可用，请重试");
        }
    }

    @Override public String assetTypeOf(Actor actor, long id) {
        Asset row = load(authorize(actor), actor, id);
        return row == null ? null : row.type();
    }

    @Override public boolean validateReference(Actor actor, long id, Set<String> types) {
        Asset row = load(authorize(actor), actor, id);
        return row != null && "ACCEPTED".equals(row.status()) && types.contains(row.type());
    }

    @Override public boolean legacyReferenceAllowed(Actor actor, long id) {
        authorize(actor);
        // 跨租户或被删除的同 ID 也不能通过历史财务路径复活。
        return db("legacy-collision-check", id, () -> jdbc.queryForObject("SELECT count(*) FROM fr_asset WHERE id=?", Integer.class, id)) == 0;
    }

    private long authorize(Actor actor) {
        var post = posts.require();
        if (actor == null || actor.orgId() != post.orgId() || actor.userId() != post.userId()
                || actor.postId() != post.postId() || accounts.requireServiceAccount(actor.orgId()) != actor.serviceAccountId()) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return tenant();
    }

    @Override public boolean isOwnAcceptedUpload(Actor actor,long id,String type) {
        Asset row=load(authorize(actor),actor,id);
        return row!=null && row.user()==actor.userId() && row.post()==actor.postId()
                && "ACCEPTED".equals(row.status()) && type.equals(row.type());
    }
    private void requireUpload(Actor actor,String type,Asset existing) {
        if (AssetAccessService.engineeringVoucher(type,posts.require().roleCode())) {
            if (existing!=null && (existing.user()!=actor.userId() || existing.post()!=actor.postId())) throw exception(RESOURCE_FORBIDDEN);
            return;
        }
        requireChannel(actor,type);
    }

    private void requireChannel(Actor actor, String type) {
        if (!MAX_BYTES.containsKey(type) || !AssetAccessService.channelAllowed(type, posts.require().roleCode())) throw exception(RESOURCE_FORBIDDEN);
    }

    private static long tenant() {
        Long id = TenantContextHolder.getTenantId();
        if (id == null || id < 0) throw exception(RESOURCE_FORBIDDEN);
        return id;
    }

    private Asset accepted(long tenant, Actor actor, long id) {
        Asset row = owned(tenant, actor, id);
        if (!"ACCEPTED".equals(row.status()) || row.objectKey() == null) throw exception(RESOURCE_FORBIDDEN);
        return row;
    }

    private Asset owned(long tenant, Actor actor, long id) {
        Asset row = load(tenant, actor, id);
        if (row == null) throw exception(RESOURCE_FORBIDDEN);
        return row;
    }

    private Asset load(long tenant, Actor actor, long id) {
        Asset row = loadOwned(tenant, actor.orgId(), actor.serviceAccountId(), id);
        if (row != null) {
            var policies = visibilityPolicies.stream().filter(policy -> policy.supports(row.type())).toList();
            // Without the customer policy, a shared company service account must not expose private documents.
            if ("BUSINESS_DOCUMENT".equals(row.type()) && policies.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
            var resource = new cn.iocoder.yudao.module.franchise.api.AssetVisibilityPort.Resource(id, row.type(), row.user());
            for (var policy : policies) policy.requireReadable(tenant, actor, resource);
        }
        return row;
    }

    /** Raw owner lookup stays private; ordinary reads or a freshly verified persisted business grant may use it. */
    private Asset loadOwned(long tenant, long org, long account, long id) {
        return db("load", id, () -> jdbc.query("SELECT * FROM fr_asset WHERE id=? AND tenant_id=? AND org_id=?"
                        + " AND service_account_id=? AND deleted=FALSE", (rs, i) -> new Asset(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getLong("org_id"), rs.getLong("service_account_id"), rs.getLong("creator_user_id"), rs.getLong("creator_post_id"),
                        rs.getString("asset_type"), rs.getString("declared_mime"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("upload_key"), rs.getString("object_key"), rs.getString("upload_status"), rs.getString("rejected_reason"),
                        rs.getString("validation_token"), rs.getLong("accepted_size_bytes"), rs.getString("accepted_sha256")),
                id, tenant, org, account).stream().findFirst().orElse(null));
    }

    private <T> T db(String operation, long id, Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException e) {
            log.error("B asset database failed operation={} tenant={} asset={} error={}", operation, TenantContextHolder.getTenantId(), id, e.getClass().getSimpleName());
            throw invalid("资产数据暂不可用");
        }
    }

    private static boolean mimeAllowed(String type, String mime) {
        if (mime == null || type == null) return false;
        return switch (type) {
            case "FIN_VOUCHER", "ENG_DRAWING", "CONTRACT_ATTACHMENT", "DESIGN_DELIVERABLE", "BUSINESS_DOCUMENT" -> Set.of("image/jpeg", "image/png", "application/pdf").contains(mime);
            case "ENG_SITE_PHOTO", "DESIGN_REFERENCE" -> Set.of("image/jpeg", "image/png").contains(mime);
            case "CONTENT_ASSET" -> ContentMediaNormalizer.supported(mime);
            default -> false;
        };
    }

    private static boolean isSha256(String sha) {
        if (sha == null || sha.length() != 64) return false;
        for (int i = 0; i < sha.length(); i++) if (Character.digit(sha.charAt(i), 16) < 0) return false;
        return true;
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "application/pdf" -> ".pdf";
            case "audio/mpeg" -> ".mp3"; case "video/mp4" -> ".mp4";
            default -> {
                String suffix = ContentMediaNormalizer.inputExtension(mime);
                if (suffix == null) throw invalid("类型不支持");
                yield suffix;
            }
        };
    }
    private static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static String truncate(String value, int size) { return value == null ? null : value.substring(0, Math.min(value.length(), size)); }
    private static ServiceException invalid(String message) { return new ServiceException(1_071_000_001, message); }
    private static AssetCompleted completed(Asset asset) { return new AssetCompleted(asset.id(), asset.status(), asset.reason()); }

    // 无上下文的旧入口仅属于融合 adapter；B 不能把任意 accountId 当成租户/组织授权。
    @Override public UploadTicketView createUploadTicket(long account, String type, String mime, long size, String sha) { throw exception(RESOURCE_FORBIDDEN); }
    @Override public AssetCompleted completeUpload(long account, long id) { throw exception(RESOURCE_FORBIDDEN); }
    @Override public DownloadUrlView createDownloadUrl(long account, long id) { throw exception(RESOURCE_FORBIDDEN); }
    @Override public String assetTypeOf(long account, long id) { throw exception(RESOURCE_FORBIDDEN); }
    @Override public boolean validateReference(long account, long id) { throw exception(RESOURCE_FORBIDDEN); }
}
