package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯单测（14 文档 D-基1-4a / AC5/AC11）：入口 audience 双向隔离。
 * 拒绝发生在任何依赖使用之前——依赖传 null 即可验证入口判定（无 DB/存储交互）。
 */
class AssetEntranceIsolationTest {

    private final AssetService service = new AssetService(
            nullDataSource(), null, null, null, null, null, null);

    /** JdbcTemplate 构造期要求 dataSource 非 null（仅此一项）；其余依赖在入口判定路径上不会被触碰 */
    private static javax.sql.DataSource nullDataSource() {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class[]{javax.sql.DataSource.class},
                (proxy, method, args) -> null);
    }

    private static final String SHA = "a".repeat(64);

    @Test
    void cEndEntranceRejectsBEndTypes() {
        for (String type : new String[]{"FIN_VOUCHER", "ENG_DRAWING", "ENG_SITE_PHOTO", "CONTRACT_ATTACHMENT"}) {
            assertThatThrownBy(() -> service.createUploadTicket(1L, type, "application/pdf", 100, SHA))
                    .as("C 端入口须拒 " + type)
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(1_071_000_001);
        }
    }

    @Test
    void bEndEntranceRejectsCEndTypes() {
        for (String type : new String[]{"USER_SKETCH", "DESIGN_REFERENCE", "CASE_PDF", "AI_OUTPUT"}) {
            assertThatThrownBy(() -> service.createServiceTicket(1L, type, "image/png", 100, SHA))
                    .as("B 端入口须拒 " + type)
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(1_071_000_001);
        }
    }

    @Test
    void bEndEntranceAcceptsOwnTypesBeyondIsolationCheck() {
        // 隔离检查通过后会继续走 MIME/大小校验与落库（依赖为 null 会在落库前 NPE）——
        // 这里断言「隔离检查本身放行」：失败原因不再是入口隔离，而是后续 MIME 不符等业务校验。
        assertThatCode(() -> service.createServiceTicket(1L, "ENG_SITE_PHOTO", "application/pdf", 100, SHA))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(1_071_000_001); // MIME 不符（照片类型拒 PDF），说明已越过 audience 门
        assertThatThrownBy(() -> service.createServiceTicket(1L, "ENG_SITE_PHOTO", "image/png", 100, SHA))
                .as("MIME/大小通过后进入落库，null 依赖在此暴露（隔离门已放行）")
                .isNotInstanceOf(ServiceException.class);
    }

    @Test
    void unknownTypeStillRejected() {
        assertThatThrownBy(() -> service.createUploadTicket(1L, "NOT_A_TYPE", "image/png", 100, SHA))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(1_071_000_001);
    }

}
