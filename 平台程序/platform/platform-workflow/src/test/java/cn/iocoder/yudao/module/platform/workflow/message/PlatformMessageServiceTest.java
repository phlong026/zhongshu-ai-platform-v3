package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.workflow.dal.MessageDO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.MESSAGE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 Fake 单测（无 Spring）：clamp 边界、游标透传、已读幂等与越权同码、read-all 行数。
 */
class PlatformMessageServiceTest {

    private final FakeMessageStore store = new FakeMessageStore();
    private final PlatformMessageService service = new PlatformMessageService(store);

    private MessageDO seed(long id, long userId, boolean read) {
        MessageDO message = new MessageDO().setUserId(userId).setOrgId(8L).setSenderDomain("e2e")
                .setType("BUSINESS").setRoute("customer").setTitle("标题" + id).setDescription("描述")
                .setDedupeKey("k" + id).setRead(read);
        message.setId(id);
        store.rows.put(id, message);
        return message;
    }

    @Test
    void limitClampsToBoundsAndDefaults() {
        assertThat(PlatformMessageService.clamp(null)).isEqualTo(20);
        assertThat(PlatformMessageService.clamp(0)).isEqualTo(1);
        assertThat(PlatformMessageService.clamp(-5)).isEqualTo(1);
        assertThat(PlatformMessageService.clamp(1)).isEqualTo(1);
        assertThat(PlatformMessageService.clamp(50)).isEqualTo(50);
        assertThat(PlatformMessageService.clamp(101)).isEqualTo(100);
        assertThat(PlatformMessageService.clamp(1000)).isEqualTo(100);
    }

    @Test
    void listPassesCursorThroughAndOrdersNewestFirst() {
        seed(1, 7, false);
        seed(2, 7, false);
        seed(3, 7, false);
        seed(4, 8, false); // 他人消息不出现

        List<MessageDO> firstPage = service.list(7L, null, 2);
        assertThat(firstPage).extracting(MessageDO::getId).containsExactly(3L, 2L);
        assertThat(store.lastFindPageUserId).isEqualTo(7L);
        assertThat(store.lastFindPageBeforeId).isNull();
        assertThat(store.lastFindPageLimit).isEqualTo(2);

        List<MessageDO> secondPage = service.list(7L, 2L, 2);
        assertThat(secondPage).extracting(MessageDO::getId).containsExactly(1L);
    }

    @Test
    void unreadCountCountsOnlyOwnerUnread() {
        seed(1, 7, false);
        seed(2, 7, true);
        seed(3, 8, false);

        assertThat(service.unreadCount(7L)).isEqualTo(1);
        assertThat(service.unreadCount(8L)).isEqualTo(1);
    }

    @Test
    void markReadReturnsUpdatedViewThenIdempotentReplayKeepsFirstReadTime() {
        MessageDO message = seed(1, 7, false);

        MessageDO first = service.markRead(7L, 1L);
        assertThat(first.getRead()).isTrue();
        assertThat(first.getReadTime()).isNotNull();
        OffsetDateTime firstTime = first.getReadTime();

        MessageDO replay = service.markRead(7L, 1L); // 二次 UPDATE 0 行 → 幂等返回现有行
        assertThat(replay.getRead()).isTrue();
        assertThat(replay.getReadTime()).isEqualTo(firstTime);
        assertThat(store.findById(1L).getReadTime()).isEqualTo(firstTime);
    }

    @Test
    void markReadThrowsNotFoundForMissingOrForeignMessage() {
        seed(1, 8, false);

        assertThatThrownBy(() -> service.markRead(7L, 404L))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(MESSAGE_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.markRead(7L, 1L)) // 他人消息同码，不泄露存在性
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(MESSAGE_NOT_FOUND.getCode()));
    }

    @Test
    void markAllReadUpdatesOnlyOwnerUnreadAndReturnsCount() {
        seed(1, 7, false);
        seed(2, 7, false);
        seed(3, 7, true);
        seed(4, 8, false);

        assertThat(service.markAllRead(7L)).isEqualTo(2);
        assertThat(service.unreadCount(7L)).isZero();
        assertThat(service.unreadCount(8L)).isEqualTo(1); // 只动本人
        assertThat(service.markAllRead(7L)).isZero(); // 幂等：无可读时 0
        assertThat(store.lastMarkAllReadTime).isNotNull();
    }

}
