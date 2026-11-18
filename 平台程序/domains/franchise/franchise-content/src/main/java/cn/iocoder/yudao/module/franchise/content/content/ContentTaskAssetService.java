package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderException;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;

/** Caller authorization is captured in the immutable task input BEFORE any reservation or enqueue. */
@Service
public class ContentTaskAssetService {
    public static final String SNAPSHOT_KEY = "_authorizedAssets";
    private final CurrentPost posts;
    private final StoreServiceAccountPort accounts;
    private final AssetAccessPort assets;
    public ContentTaskAssetService(CurrentPost posts,StoreServiceAccountPort accounts,AssetAccessPort assets) {
        this.posts=posts;this.accounts=accounts;this.assets=assets;
    }
    public record Checked(Map<String,Object> snapshot,Map<Long,AssetAccessPort.ContentMetadata> metadata) { }
    public Checked authorize(long company,long user,Collection<Long> ids) {
        var post=posts.require();Long tenant=TenantContextHolder.getTenantId();
        if(tenant==null||post.orgId()!=company||post.userId()!=user)throw exception(RESOURCE_FORBIDDEN);
        var actor=new AssetAccessPort.Actor(company,accounts.requireServiceAccount(company),user,post.postId());
        Map<Long,AssetAccessPort.ContentMetadata> metadata=new LinkedHashMap<>();
        for(Long id:ids) {
            if(id==null||id<=0)throw exception(RESOURCE_FORBIDDEN);
            // This port enforces ACCEPTED, CONTENT_ASSET, organization and material/cover visibility.
            var value=assets.contentMetadata(actor,id);
            if(value==null)throw exception(RESOURCE_FORBIDDEN);
            metadata.put(id,value);
        }
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("version",1);snapshot.put("tenantId",tenant.toString());snapshot.put("companyId",Long.toString(company));
        snapshot.put("authorUserId",Long.toString(user));snapshot.put("postId",Long.toString(post.postId()));
        snapshot.put("assets",metadata.entrySet().stream().map(e->Map.of("assetId",Long.toString(e.getKey()),"sha256",e.getValue().sha256())).toList());
        return new Checked(Collections.unmodifiableMap(snapshot),Collections.unmodifiableMap(metadata));
    }
    public String download(FrContentTaskDO task,long id) {
        Map<String,Object> input=JsonUtils.parseObject(task.getInputSnapshot(),Map.class);
        Object raw=input.get(SNAPSHOT_KEY);
        if(!(raw instanceof Map<?,?> proof) || !"1".equals(String.valueOf(proof.get("version")))
                || !String.valueOf(task.getCompanyId()).equals(proof.get("companyId"))
                || !String.valueOf(task.getAuthorUserId()).equals(proof.get("authorUserId"))
                || !(proof.get("assets") instanceof List<?> references)
                || references.stream().noneMatch(r->r instanceof Map<?,?> ref && Long.toString(id).equals(ref.get("assetId")))) {
            // Fail before dispatch; legacy unverified queued inputs must not gain service privileges.
            throw new ContentProviderException.Rejection("任务素材缺少提交时的授权记录，请重新选择素材",403,"MATERIAL_NOT_AUTHORIZED");
        }
        Long tenant=TenantContextHolder.getTenantId();
        if(tenant!=null&&!tenant.toString().equals(proof.get("tenantId")))
            throw new ContentProviderException.Rejection("任务素材不属于当前组织",403,"MATERIAL_SCOPE_MISMATCH");
        return assets.createTaskDownloadUrl(task.getCompanyId(),id,"CONTENT_ASSET").downloadUrl();
    }
}
