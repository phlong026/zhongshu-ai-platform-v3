package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.DesignFeeProgressApi;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 加盟协同域 M2 整机端到端的开发搭建接口：一键建总部/门店与五名员工任职，
 * 以及模拟首尾款到账（M3 财务接入前的替代通道）。
 * 只在 local profile 且非 prod 下注册；@PermitAll 是因为搭建发生在员工登录之前。
 * 生产严禁出现本控制器（@Profile 双重守卫，与 StubWechatStaffClient 同口径）。
 */
@Tag(name = "开发 - 加盟域端到端搭建")
@RestController
@RequestMapping("/admin-api/platform/dev-setup/franchise-e2e")
@PermitAll
public class FranchiseE2ESetupController {

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private AdminUserMapper adminUserMapper;
    @Resource
    private DesignFeeProgressApi feeProgress;
    @Resource
    private cn.iocoder.yudao.module.platform.authz.CurrentPost currentPost;

    /** 一键搭建：总部（设计行政）+ 门店（业务、老板、财务、工程负责人） */
    /** batch：批次后缀（组织名追加），保证脚本可重复执行 */
    public record SetupReq(String hqName, String storeName, String mobilePrefix, String batch) {
    }

    /** 搭建结果：各角色手机号，供 stub 登录（loginCode 任意、phoneCode=手机号）换取令牌 */
    public record SetupResult(long headquartersOrgId, long storeOrgId,
                              Staff designAdmin, Staff sales, Staff boss, Staff finance, Staff engineer,
                              Staff engineerB, Staff owner,
                              long salesTeamId, long engineeringTeamId) {

        public record Staff(long userId, long postId, String name, String mobile) {
    }
    }

    /** 模拟到账（M3 财务接入前由开发/联调调用，等价于财务在核实事务内调 DesignFeeProgressApi） */
    public record FeeSettlementReq(long commissionId, int submissionVersion, String stage) {
    }

    @PostMapping("/setup")
    @jakarta.annotation.security.PermitAll
    @Operation(summary = "一键搭建总部与门店五名员工（幂等：手机号已存在则复用账号与任职）")
    public CommonResult<SetupResult> setup(@RequestBody(required = false) SetupReq req) {
        String prefix = req == null || req.mobilePrefix() == null || req.mobilePrefix().isBlank()
                ? "1380000000" : req.mobilePrefix();
        String batch = req == null || req.batch() == null ? "" : "-" + req.batch();
        java.util.List<Long> existingHq = orgService.listActiveOrgIds("headquarters");
        long hq = existingHq.size() == 1 ? existingHq.get(0)
                : orgService.createOrg("headquarters", (req == null || req.hqName() == null ? "众墅总部" : req.hqName()) + batch, null);
        long store = orgService.createOrg("store", (req == null || req.storeName() == null ? "湖北加盟商" : req.storeName()) + batch, null);
        long salesTeam = orgService.createTeam(store, null, "业务部", "sales");
        long engineeringTeam = orgService.createTeam(store, null, "工程部", "engineering");
        return success(new SetupResult(hq, store,
                staff(prefix + "1", "林设计", hq, "hq-design-admin", null),
                staff(prefix + "2", "李业务", store, "sales", salesTeam),
                staff(prefix + "3", "张老板", store, "boss", null),
                staff(prefix + "4", "王财务", store, "finance", null),
                staff(prefix + "5", "张工程", store, "project-manager", engineeringTeam),
                // 阶段 2 工程域 e2e：同组织第二工程岗（engineer 角色，供 owner 校验 000 断言）
                staff(prefix + "6", "李工程", store, "engineer", engineeringTeam),
                // 阶段 3 绩效域 e2e：门店负责人 owner（提成可看不可改/管理动作对照）
                staff(prefix + "7", "周负责人", store, "owner", null),
                salesTeam, engineeringTeam));
    }

    @PostMapping("/fee-settlement")
    @jakarta.annotation.security.PermitAll
    @Operation(summary = "模拟首尾款到账：first-ready / tail-paid / tail-ready")
    public CommonResult<Boolean> feeSettlement(@RequestBody FeeSettlementReq req) {
        // 优先用请求携带的财务岗位（X-Post-Id，与 M3 财务真实调用方式一致）；
        // 未带岗位时以门店财务的系统账号留痕（setup 默认手机号）
        var requestPost = currentPost.find();
        var actor = requestPost.orElseGet(() -> {
            AdminUserDO financeUser = adminUserMapper.selectOne(AdminUserDO::getMobile, "1380000004");
            long financeUserId = financeUser == null ? 0L : financeUser.getId();
            return new cn.iocoder.yudao.module.platform.org.PostSnapshot(0, financeUserId, 0, "store", "finance", null, false, java.util.Set.of());
        });
        switch (req.stage() == null ? "" : req.stage()) {
            case "first-ready" -> feeProgress.markFirstReady(req.commissionId(), req.submissionVersion(), actor);
            case "tail-paid" -> feeProgress.markTailPaid(req.commissionId(), req.submissionVersion(), actor);
            case "tail-ready" -> feeProgress.markTailReady(req.commissionId(), req.submissionVersion(), actor);
            default -> throw new IllegalArgumentException("stage 须为 first-ready / tail-paid / tail-ready");
        }
        return success(true);
    }

    /** 建员工账号（手机号已存在则复用）并任职，返回带 postId 的身份 */
    private SetupResult.Staff staff(String mobile, String name, long orgId, String roleCode, Long teamId) {
        AdminUserDO existing = adminUserMapper.selectOne(AdminUserDO::getMobile, mobile);
        long userId;
        if (existing != null) {
            userId = existing.getId();
        } else {
            AdminUserDO user = new AdminUserDO();
            user.setUsername(mobile);
            user.setMobile(mobile);
            user.setNickname(name);
            user.setStatus(CommonStatusEnum.ENABLE.getStatus());
            user.setTenantId(1L);
            adminUserMapper.insert(user);
            userId = user.getId();
        }
        long postId = staffPostService.listActivePosts(userId).stream()
                .filter(p -> p.getOrgId() == orgId && roleCode.equals(p.getRoleCode()))
                .findFirst()
                .map(cn.iocoder.yudao.module.platform.org.dal.StaffPostDO::getId)
                .orElseGet(() -> staffPostService.assignPost(userId, orgId, roleCode, teamId, userId));
        return new SetupResult.Staff(userId, postId, name, mobile);
    }

}
