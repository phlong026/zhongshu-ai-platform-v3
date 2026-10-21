package cn.iocoder.yudao.module.platform.command;

import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;

import java.util.List;
import java.util.Optional;

/**
 * 命令事件日志。业务域在执行命令的同一个事务里调用 {@link #append}，事务回滚则事件一并回滚。
 *
 * <p>典型用法（业务域命令处理）：
 * <ol>
 *   <li>带幂等键时先 {@link #findByIdempotencyKey}，已存在则直接返回上次结果；</li>
 *   <li>{@code UPDATE ... SET revision = revision + 1 WHERE id = ? AND revision = ?}，
 *       用 {@link Revisions#requireUpdated} 检查是否被并发修改；</li>
 *   <li>{@link #append} 记录本次命令，revision 为更新后的版本号。</li>
 * </ol>
 */
public interface CommandLog {

    /**
     * 追加一条命令事件，并计算 payloadHash、补齐发生时间。
     *
     * <ul>
     *   <li>幂等键已存在且 payload 相同：不重复写入，返回原事件；</li>
     *   <li>幂等键已存在但 payload 不同：抛 IDEMPOTENCY_KEY_REUSED；</li>
     *   <li>同一聚合同一 revision 已有事件（并发写入）：抛 STATE_VERSION_CONFLICT。</li>
     * </ul>
     */
    CommandEventDO append(CommandEventDO event);

    Optional<CommandEventDO> findByIdempotencyKey(String idempotencyKey);

    /** 按 revision 升序返回某个聚合的全部命令事件 */
    List<CommandEventDO> listByAggregate(String domain, String aggregateType, long aggregateId);

}
