package cn.iocoder.yudao.module.platform.workflow.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapperX<MessageDO> {

    /** 幂等键查询：同 (senderDomain, dedupeKey) 的既有消息，无则 null */
    default MessageDO selectByDedupe(String senderDomain, String dedupeKey) {
        return selectOne(new LambdaQueryWrapperX<MessageDO>()
                .eq(MessageDO::getSenderDomain, senderDomain)
                .eq(MessageDO::getDedupeKey, dedupeKey));
    }

    /** 本人消息分页：id 倒序（雪花单调递增，等价时间倒序），beforeId 游标取更早一页 */
    default List<MessageDO> selectPage(long userId, Long beforeId, int limit) {
        return selectList(new LambdaQueryWrapperX<MessageDO>()
                .eq(MessageDO::getUserId, userId)
                .lt(beforeId != null, MessageDO::getId, beforeId)
                .orderByDesc(MessageDO::getId)
                .last("LIMIT " + limit));
    }

    default long countUnread(long userId) {
        return selectCount(new LambdaQueryWrapperX<MessageDO>()
                .eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getRead, false));
    }

    /** 仅当仍为本人未读时置已读，返回影响行数（0 行=不存在/非本人/已读，由调用方判定） */
    default int updateIfUnread(long userId, long messageId, OffsetDateTime readTime) {
        return update(new MessageDO().setRead(true).setReadTime(readTime), new LambdaUpdateWrapper<MessageDO>()
                .eq(MessageDO::getId, messageId)
                .eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getRead, false));
    }

    /** 本人全部未读置已读，返回更新行数 */
    default int markAllRead(long userId, OffsetDateTime readTime) {
        return update(new MessageDO().setRead(true).setReadTime(readTime), new LambdaUpdateWrapper<MessageDO>()
                .eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getRead, false));
    }

}
