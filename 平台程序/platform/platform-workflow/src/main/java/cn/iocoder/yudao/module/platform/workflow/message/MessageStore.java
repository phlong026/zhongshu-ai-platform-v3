package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 消息存储边界：读侧服务与发送方都只依赖本接口，单测用内存 Fake 替换
 * （仿基-3 StaffProfileStore 模式，11 文档 §七）。
 */
public interface MessageStore {

    /** 幂等键查询：无则 null */
    MessageDO findByDedupe(String senderDomain, String dedupeKey);

    /** 插入（ASSIGN_ID 由 MP 填充）；唯一索引冲突由调用方捕获 DuplicateKeyException 处理 */
    void insert(MessageDO message);

    MessageDO findById(long id);

    /** 本人消息分页：id 倒序，beforeId 游标取更早一页 */
    List<MessageDO> findPage(long userId, Long beforeId, int limit);

    long countUnread(long userId);

    /** 仅当仍为本人未读时置已读，返回影响行数 */
    int markRead(long userId, long messageId, OffsetDateTime readTime);

    /** 本人全部未读置已读，返回更新行数 */
    int markAllRead(long userId, OffsetDateTime readTime);

}
