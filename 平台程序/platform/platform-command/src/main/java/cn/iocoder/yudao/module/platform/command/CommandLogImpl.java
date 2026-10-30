package cn.iocoder.yudao.module.platform.command;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

@Service
@Slf4j
public class CommandLogImpl implements CommandLog {

    @Resource
    private CommandEventMapper commandEventMapper;

    @Override
    public CommandEventDO append(CommandEventDO event) {
        Objects.requireNonNull(event, "event");
        String hash = DigestUtil.sha256Hex(Objects.toString(event.getPayload(), ""));
        if (event.getIdempotencyKey() != null) {
            CommandEventDO existing = commandEventMapper.selectByIdempotencyKey(event.getIdempotencyKey());
            if (existing != null) {
                if (!existing.getPayloadHash().equals(hash)) {
                    throw exception(IDEMPOTENCY_KEY_REUSED);
                }
                return existing;
            }
        }
        event.setPayloadHash(hash);
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(OffsetDateTime.now());
        }
        try {
            commandEventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            // 同一聚合同一 revision，或同一幂等键被并发请求抢先写入；本事务已失效，交由调用方刷新重试
            log.warn("[append][{}/{}#{} revision={} command={}] 并发写入冲突", event.getDomain(),
                    event.getAggregateType(), event.getAggregateId(), event.getRevision(), event.getCommand());
            throw exception(STATE_VERSION_CONFLICT);
        }
        return event;
    }

    @Override
    public Optional<CommandEventDO> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(commandEventMapper.selectByIdempotencyKey(idempotencyKey));
    }

    @Override
    public List<CommandEventDO> listByAggregate(String domain, String aggregateType, long aggregateId) {
        return commandEventMapper.selectListByAggregate(domain, aggregateType, aggregateId);
    }

}
