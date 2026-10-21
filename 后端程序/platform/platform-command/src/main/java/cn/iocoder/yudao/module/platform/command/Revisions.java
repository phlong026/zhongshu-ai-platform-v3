package cn.iocoder.yudao.module.platform.command;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 业务聚合的乐观锁检查。聚合表带 {@code revision BIGINT}，更新时用
 * {@code UPDATE ... SET revision = revision + 1 WHERE id = ? AND revision = ?}。
 */
public final class Revisions {

    private Revisions() {
    }

    /** 影响行数不是 1，说明记录已被别人改过（或不存在），抛「数据版本已过期，请刷新后重试」 */
    public static void requireUpdated(int affectedRows) {
        if (affectedRows != 1) {
            throw exception(STATE_VERSION_CONFLICT);
        }
    }

}
