package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class FranchiseAssertions {

    private FranchiseAssertions() {
    }

    /** 断言抛出指定错误码的业务异常 */
    public static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }

}
