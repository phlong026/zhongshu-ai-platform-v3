package cn.iocoder.yudao.module.franchise.asset;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.ServiceAccountInactiveException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * B 端资产通道编排（基-1，14 文档）：currentPost → 服务账号（每次过状态校验）→ AssetAccessPort → 审计。
 *
 * <p>事务边界与板块 A 资产端点同口径（05 §3.4）：资产能力自带短事务边界，本层不包外层事务；
 * 审计为能力调用返回后的独立短事务 record（仅 upload-ticket/complete 两个写操作，download 不记）。
 * 审计 record 失败将使请求失败、能力已生效——complete 幂等重试无害；
 * upload-ticket 无幂等键，重试=签发新票据（孤儿 PENDING 行不可引用、无害但非幂等）。
 * 通道权限=登录+任职（D-基1-6 显式放宽）：类型级岗位管控随消费域批次挂其域端点。
 */
@Service
@Slf4j
public class AssetAccessService {

    /** 本通道可操作的资产类型白名单（与 design AssetTypePolicy 的 B_END 同源；新增先登记 14 文档 §五.2）。
     *  包级可见供快照锁测试比对（跨域经 Port 禁直依赖 design 模块——评审基线强制清单 #13）。 */
    static final Set<String> B_END_TYPES =
            Set.of("FIN_VOUCHER", "ENG_DRAWING", "ENG_SITE_PHOTO", "CONTRACT_ATTACHMENT", "CONTENT_ASSET");

    private final AssetAccessPort assets;
    private final StoreServiceAccountPort serviceAccounts;
    private final FranchiseCommands commands;
    private final CurrentPost currentPost;

    public AssetAccessService(AssetAccessPort assets, StoreServiceAccountPort serviceAccounts,
                              FranchiseCommands commands, CurrentPost currentPost) {
        this.assets = assets;
        this.serviceAccounts = serviceAccounts;
        this.commands = commands;
        this.currentPost = currentPost;
    }

    public PostSnapshot post() {
        return currentPost.require();
    }

    /** 服务账号解析（每次都过状态校验；停用 → 1_112_000_004，与板块 A 同口径） */
    public long serviceAccount(PostSnapshot post) {
        try {
            return serviceAccounts.requireServiceAccount(post.orgId());
        } catch (ServiceAccountInactiveException e) {
            throw exception(FranchiseDesignErrorCodes.SERVICE_ACCOUNT_INACTIVE);
        }
    }

    public DesignServicePort.UploadTicketView uploadTicket(PostSnapshot post, String assetType,
                                                           String mimeType, long sizeBytes, String sha256) {
        requireChannelPermission(post, assetType); // M5 分派（R2 P1-1）
        long accountId = serviceAccount(post);
        DesignServicePort.UploadTicketView view = assets.createUploadTicket(accountId, assetType, mimeType, sizeBytes, sha256);
        audit(post, "upload-ticket", view.assetId(), assetType);
        return view;
    }

    public DesignServicePort.AssetCompleted completeUpload(PostSnapshot post, long assetId) {
        requireChannelPermission(post, channelTypeOf(post, assetId)); // M5 分派
        long accountId = serviceAccount(post);
        String assetType = requireBEndAsset(accountId, assetId);
        DesignServicePort.AssetCompleted completed = assets.completeUpload(accountId, assetId);
        audit(post, "complete", assetId, assetType);
        return completed;
    }

    public DesignServicePort.DownloadUrlView createDownloadUrl(PostSnapshot post, long assetId) {
        requireChannelPermission(post, channelTypeOf(post, assetId)); // M5 分派
        long accountId = serviceAccount(post);
        requireBEndAsset(accountId, assetId);
        return assets.createDownloadUrl(accountId, assetId);
    }

    /**
     * 通道类型限制：franchise-asset 权限宽于设计通道（D-基1-6 登录+任职），只放行 B_END 资产——
     * 堵「无 franchise:design:use 的员工经本通道读同组织私有人草图」的权限旁路（Codex 补审 P1）。
     * 不存在/非属主/非 B_END 一律 RESOURCE_FORBIDDEN（同码，不泄露区分）。
     */
    /** 通道分派前置查类型（不存在→null→分派拒绝；不泄露区分） */
    public String channelTypeOf(PostSnapshot post, long assetId) {
        long accountId = serviceAccount(post);
        return assets.assetTypeOf(accountId, assetId);
    }

    private String requireBEndAsset(long accountId, long assetId) {
        String assetType = assets.assetTypeOf(accountId, assetId);
        if (assetType == null || !B_END_TYPES.contains(assetType)) {
            throw exception(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN);
        }
        return assetType;
    }

    /**
     * 通道消费域权限分派（M5 / 21 号 V3 新 P1-1，落实 14 文档 D-基1-6 凭证批消费域权限）：
     * 按 assetType 映射消费域岗位集（对齐各域 8xx 权限种子的 query 矩阵）；
     * 越权 RESOURCE_FORBIDDEN（同码不泄露）。upload 从入参、complete/download 由调用方查得类型后调用。
     */
    public void requireChannelPermission(PostSnapshot post, String assetType) {
        var roles = assetType == null ? null : CHANNEL_ROLES.get(assetType);
        if (roles == null) {
            return; // 未知类型放行给后续资产校验（保持 1_071_000_001 原语义）
        }
        if (!roles.contains(post.roleCode())) {
            throw exception(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN);
        }
    }

    // 严格对齐各域 8xx 种子 query 矩阵（R3：ENG 归工程岗、CONTRACT 归业务岗——不跨域放宽）
    private static final Map<String, List<String>> CHANNEL_ROLES = Map.of(
            "FIN_VOUCHER", List.of("finance", "finance-lead", "boss", "owner"),
            "ENG_DRAWING", List.of("engineer", "project-manager", "boss", "owner"),
            "ENG_SITE_PHOTO", List.of("engineer", "project-manager", "boss", "owner"),
            "CONTRACT_ATTACHMENT", List.of("sales", "sales-lead", "boss", "owner"),
            "CONTENT_ASSET", List.of("sales", "sales-lead", "boss", "owner"));

    /** 代理层审计（14 文档 §四）：独立短事务（无外层事务包裹），仅写操作调用 */
    private void audit(PostSnapshot post, String action, long assetId, String assetType) {
        var input = new LinkedHashMap<String, Object>();
        input.put("actorUserId", post.userId());
        input.put("actorPostId", post.postId());
        input.put("actorRoleCode", post.roleCode());
        if (assetType != null) {
            input.put("assetType", assetType);
        }
        commands.record(post, "fr-asset-" + action, assetId, IdUtil.getSnowflakeNextId(),
                "asset-" + action, input, "asset-audit:" + action + ":" + java.util.UUID.randomUUID());
    }

}
