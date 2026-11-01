package cn.iocoder.yudao.module.franchise.asset;

import cn.hutool.core.util.IdUtil;
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
        long accountId = serviceAccount(post);
        DesignServicePort.UploadTicketView view = assets.createUploadTicket(accountId, assetType, mimeType, sizeBytes, sha256);
        audit(post, "upload-ticket", view.assetId(), assetType);
        return view;
    }

    public DesignServicePort.AssetCompleted completeUpload(PostSnapshot post, long assetId) {
        long accountId = serviceAccount(post);
        DesignServicePort.AssetCompleted completed = assets.completeUpload(accountId, assetId);
        audit(post, "complete", assetId, null);
        return completed;
    }

    public DesignServicePort.DownloadUrlView createDownloadUrl(PostSnapshot post, long assetId) {
        return assets.createDownloadUrl(serviceAccount(post), assetId);
    }

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
