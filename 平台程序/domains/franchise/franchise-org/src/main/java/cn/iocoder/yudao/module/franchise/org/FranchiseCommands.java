package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 加盟协同域命令留痕与幂等：每个写命令在同一事务内追加一条平台命令事件。
 *
 * <p>客户端可带幂等键；平台的幂等键全局唯一，这里按「域 + 用户」加前缀，避免不同人的键相撞。
 */
@Component
public class FranchiseCommands {

    private final CommandLog commandLog;

    public FranchiseCommands(CommandLog commandLog) {
        this.commandLog = commandLog;
    }

    /** 同一幂等键已经办理过时返回原事件（内容不同则拒绝），调用方应直接返回当前结果而不重复执行 */
    public Optional<CommandEventDO> replayed(PostSnapshot post, String idempotencyKey, Object input) {
        return replayed(post, idempotencyKey, input, null);
    }

    /**
     * 请求指纹增字段时兼容升级前的已完成事件。调用方命中旧指纹后仍须用已落库聚合校验
     * 新字段，避免把旧指纹缺失的信息当作相同请求。
     */
    public Optional<CommandEventDO> replayed(PostSnapshot post, String idempotencyKey,
                                             Object input, Object legacyInput) {
        if (StrUtil.isBlank(idempotencyKey)) {
            return Optional.empty();
        }
        return commandLog.findByIdempotencyKey(scopedKey(post, idempotencyKey)).map(existing -> {
            // Preserve historical key format, but never replay a fact from another organization.
            String currentHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(input));
            String legacyHash = legacyInput == null ? null
                    : DigestUtil.sha256Hex(JsonUtils.toJsonString(legacyInput));
            if (!java.util.Objects.equals(existing.getOrgId(), post.orgId())
                    || (!currentHash.equals(existing.getPayloadHash())
                    && !java.util.Objects.equals(legacyHash, existing.getPayloadHash()))) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return existing;
        });
    }

    /** 资金写入必须由调用方提供稳定键；拒绝静默截断，避免不同长键折叠为同一命令。 */
    public String requireIdempotencyKey(String idempotencyKey) {
        if (StrUtil.isBlank(idempotencyKey) || idempotencyKey.trim().length() > 80) {
            throw exception(BAD_REQUEST);
        }
        return idempotencyKey.trim();
    }

    /** 追加命令事件；revision 为聚合执行后的版本号。
     * M08-T08/R1 P1（同键并发重复落单）：append 对「幂等键命中且 payload 相同」静默返回原事件——
     * 并发请求 B 在 A 提交后读键，若不校验聚合归属，B 的业务行会随静默通过而提交（重复落单）。
     * 这里按聚合一致性拒绝：返回事件不属于本命令聚合即抛 STATE_VERSION_CONFLICT 整笔回滚，重放由 replayed 承接。 */
    public void record(PostSnapshot post, String aggregateType, long aggregateId, long revision, String command,
                       Object input, String idempotencyKey) {
        CommandEventDO event = commandLog.append(new CommandEventDO()
                .setDomain(FranchiseRoles.DOMAIN).setAggregateType(aggregateType).setAggregateId(aggregateId)
                .setRevision(revision).setCommand(command).setOrgId(post.orgId())
                .setActorUserId(post.userId()).setActorPostId(post.postId()).setActorRoleCode(post.roleCode())
                .setPayload(JsonUtils.toJsonString(input))
                .setIdempotencyKey(StrUtil.isBlank(idempotencyKey) ? null : scopedKey(post, idempotencyKey)));
        if (event.getIdempotencyKey() != null && (!java.util.Objects.equals(event.getOrgId(), post.orgId())
                || event.getAggregateId() != aggregateId
                || !event.getAggregateType().equals(aggregateType))) {
            throw exception(STATE_VERSION_CONFLICT);
        }
    }

    private static String scopedKey(PostSnapshot post, String key) {
        return FranchiseRoles.DOMAIN + ":" + post.userId() + ":" + StrUtil.maxLength(key.trim(), 80);
    }

}
