package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.commission.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/** Immutable manifest is the sole authority for cross-organization deliverable access. */
@Service
public class CommissionManifestService {
    public record Manifest(long commissionId,int dispatchSeq,int submissionVersion,long designOrgId,
                           long resultAssetId,long drawingAssetId,long reviewAssetId,String drawingVersion,
                           long actorUserId,OffsetDateTime createTime) { }
    private final JdbcTemplate jdbc;
    private final AssetAccessPort assets;
    private final StoreServiceAccountPort accounts;
    public CommissionManifestService(JdbcTemplate jdbc,AssetAccessPort assets,StoreServiceAccountPort accounts) {
        this.jdbc=jdbc;this.assets=assets;this.accounts=accounts;
    }
    public List<Manifest> list(long commissionId) {
        return jdbc.query("SELECT * FROM fr_commission_dispatch_manifest WHERE commission_id=? ORDER BY dispatch_seq DESC",(rs,n)->
                new Manifest(rs.getLong("commission_id"),rs.getInt("dispatch_seq"),rs.getInt("submission_version"),rs.getLong("design_org_id"),
                        rs.getLong("result_asset_id"),rs.getLong("drawing_asset_id"),rs.getLong("review_asset_id"),rs.getString("drawing_version"),
                        rs.getLong("actor_user_id"),rs.getObject("create_time",OffsetDateTime.class)),commissionId);
    }
    public Manifest require(long commissionId,int seq) {
        return list(commissionId).stream().filter(m->m.dispatchSeq()==seq).findFirst().orElseThrow(()->exception(HANDOVER_INPUT_INVALID));
    }
    public void validate(PostSnapshot post,long ownerOrgId,long assetId,String type) {
        if(post.orgId()!=ownerOrgId || assetId<=0 || !assets.validateReference(new AssetAccessPort.Actor(ownerOrgId,
                accounts.requireServiceAccount(ownerOrgId),post.userId(),post.postId()),assetId,Set.of(type))) throw exception(PROGRESS_INPUT_INVALID);
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void append(PostSnapshot actor,CommissionDO c,int seq,long result,long drawing,long review,String version) {
        validate(actor,c.getDesignOrgId(),result,"DESIGN_DELIVERABLE");
        validate(actor,c.getDesignOrgId(),drawing,"DESIGN_DELIVERABLE");
        validate(actor,c.getDesignOrgId(),review,"DESIGN_DELIVERABLE");
        if(version==null || version.isBlank() || version.length()>64) throw exception(PROGRESS_INPUT_INVALID);
        jdbc.update("INSERT INTO fr_commission_dispatch_manifest(id,commission_id,dispatch_seq,submission_version,design_org_id,result_asset_id,drawing_asset_id,review_asset_id,drawing_version,actor_user_id,actor_post_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                IdUtil.getSnowflakeNextId(),c.getId(),seq,c.getSubmissionVersion(),c.getDesignOrgId(),result,drawing,review,version.trim(),actor.userId(),actor.postId());
    }
    public static String fileRef(long commissionId,int dispatchSeq,String slot,long assetId,String name) {
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(java.util.Map.of(
                "commissionId",String.valueOf(commissionId),"dispatchSeq",dispatchSeq,"slot",slot,
                "assetId",String.valueOf(assetId),"name",name));
    }
}
