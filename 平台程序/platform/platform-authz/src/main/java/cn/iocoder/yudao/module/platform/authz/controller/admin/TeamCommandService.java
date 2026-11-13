package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 组织部门创建命令：用组织行锁串行化同组织重试，业务写入与命令日志共享事务。 */
@Service
public class TeamCommandService {

    private static final int MAX_RAW_KEY_LENGTH = 128;
    private static final String DOMAIN = "platform";
    private static final String AGGREGATE_TYPE = "org-team";
    private static final String COMMAND = "org-team-create";

    private final OrgService orgService;
    private final CommandLog commandLog;

    public TeamCommandService(OrgService orgService, CommandLog commandLog) {
        this.orgService = orgService;
        this.commandLog = commandLog;
    }

    @Transactional(rollbackFor = Exception.class)
    public long create(PostSnapshot post, String idempotencyKey, String name, String line) {
        String normalizedKey = requireKey(idempotencyKey);
        CreateTeamPayload payload = new CreateTeamPayload(post.orgId(), name, line);
        String payloadJson = JsonUtils.toJsonString(payload);
        String scopedKey = scopedKey(post, normalizedKey);

        // 先锁定组织再查重；否则两个同键请求都可能越过查重并各自创建部门。
        orgService.lockOrgForCommand(post.orgId());
        return commandLog.findByIdempotencyKey(scopedKey).map(existing -> {
            if (!DigestUtil.sha256Hex(payloadJson).equals(existing.getPayloadHash())) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return existing.getAggregateId();
        }).orElseGet(() -> createAndRecord(post, scopedKey, payloadJson, name, line));
    }

    private long createAndRecord(PostSnapshot post, String scopedKey, String payload, String name, String line) {
        long deptId = orgService.createTeam(post.orgId(), null, name, line);
        CommandEventDO event = commandLog.append(new CommandEventDO()
                .setDomain(DOMAIN).setAggregateType(AGGREGATE_TYPE).setAggregateId(deptId)
                .setRevision(1L).setCommand(COMMAND).setOrgId(post.orgId())
                .setActorUserId(post.userId()).setActorPostId(post.postId()).setActorRoleCode(post.roleCode())
                .setPayload(payload).setIdempotencyKey(scopedKey));
        return event.getAggregateId();
    }

    private static String requireKey(String key) {
        if (StrUtil.isBlank(key) || key.trim().length() > MAX_RAW_KEY_LENGTH) {
            throw exception(BAD_REQUEST);
        }
        return key.trim();
    }

    private static String scopedKey(PostSnapshot post, String key) {
        // pf_command_event.idempotency_key 最长 128；固定 67 字符避免前缀+原键溢出或截断碰撞。
        return "pt:" + DigestUtil.sha256Hex(COMMAND + ":" + post.orgId() + ":" + post.userId() + ":" + key);
    }

    private record CreateTeamPayload(long orgId, String name, String line) {
    }
}
