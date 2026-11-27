package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

/** Immutable imported history for compatibility tests; never opens a retired business writer or appointment. */
final class LegacyCommissionFixtures {
    private LegacyCommissionFixtures() { }

    static long submission(JdbcTemplate jdbc, PostSnapshot actor, long customer, long headquarters,
                           CommissionService.SubmitCommand input, String status) {
        long id = IdUtil.getSnowflakeNextId();
        var budget = jdbc.queryForMap("SELECT * FROM fr_customer_scheme WHERE customer_id=? AND kind='BUDGET' ORDER BY version DESC LIMIT 1",customer);
        boolean full = "AREA_FULL".equals(input.mode());
        Long rate = full ? jdbc.queryForObject("SELECT rate_cents FROM fr_design_pricing_policy WHERE design_org_id=? AND version=?",Long.class,headquarters,input.feeRuleVersion()) : null;
        long total = full ? DesignPricingService.totalCents((BigDecimal)budget.get("area"),rate) : input.total().movePointRight(2).longValueExact();
        long first = full ? 0 : input.first().movePointRight(2).longValueExact();
        var snapshot = new LinkedHashMap<String,Object>();
        snapshot.put("BUDGET",JsonUtils.parseObject((String)budget.get("payload"),java.util.Map.class));
        if(input.designBrief()!=null) snapshot.put("designBrief",input.designBrief());
        jdbc.update("""
                INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,revision)
                SELECT ?,company_id,?,id,name,?,1,1 FROM fr_customer WHERE id=?
                """,id,headquarters,status,customer);
        jdbc.update("""
                INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,
                    budget_version,plan_version,facade_version,width,depth,floors,area,scheme_snapshot,total_cents,first_cents,
                    recipient,auth_kind,submitted_by_user_id,submitted_by_post_id,mode,pricing_version,rate_cents)
                VALUES(?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,IdUtil.getSnowflakeNextId(),actor.orgId(),headquarters,id,input.text(),input.scope(),input.budgetVersion(),
                input.planVersion(),input.facadeVersion(),budget.get("width"),budget.get("depth"),budget.get("floors"),budget.get("area"),
                JsonUtils.toJsonString(snapshot),total,first,input.recipient()==null?"历史总部设计":input.recipient(),
                "FEE_PENDING".equals(status)||"FEE_RETURNED".equals(status)?"EXCEPTION":"STANDARD",
                actor.userId(),actor.postId(),full?"AREA_FULL":"LEGACY_SPLIT",input.feeRuleVersion(),rate);
        return id;
    }

    static void dispatch(JdbcTemplate jdbc,long id,int seq,String version,long result,long drawing,long review) {
        jdbc.update("""
                INSERT INTO fr_commission_dispatch_manifest(id,commission_id,dispatch_seq,submission_version,design_org_id,
                    result_asset_id,drawing_asset_id,review_asset_id,drawing_version,actor_user_id,actor_post_id)
                SELECT ?,id,?,submission_version,design_org_id,?,?,?,?,1,1 FROM fr_commission WHERE id=?
                """,IdUtil.getSnowflakeNextId(),seq,result,drawing,review,version,id);
        jdbc.update("UPDATE fr_commission SET status='DISPATCHED',dispatch_seq=?,dispatched_submission_version=submission_version WHERE id=?",seq,id);
    }
}
