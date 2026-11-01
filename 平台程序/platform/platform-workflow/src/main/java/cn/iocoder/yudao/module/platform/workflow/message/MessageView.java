package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;

import java.time.OffsetDateTime;

/**
 * 消息视图：列表元素与单条已读响应同构（11 文档 §四）。
 * id 为雪花 id，序列化超 JS 安全整数时由 NumberSerializer 落为字符串；objectId 可空。
 */
public record MessageView(long id, String type, String route, Long objectId,
                          String title, String description, boolean read, OffsetDateTime createTime) {

    public static MessageView of(MessageDO message) {
        return new MessageView(message.getId(), message.getType(), message.getRoute(), message.getObjectId(),
                message.getTitle(), message.getDescription(), Boolean.TRUE.equals(message.getRead()),
                message.getCreateTime());
    }

}
