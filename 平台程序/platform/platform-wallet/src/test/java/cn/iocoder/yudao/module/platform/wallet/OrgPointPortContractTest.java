package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the cross-module company-point contract before the wallet implementation is moved.
 */
class OrgPointPortContractTest {

    @Test
    void exposesServerPricedReserveAndMutuallyExclusiveTerminalOperations() {
        Set<String> methods = Arrays.stream(OrgPointPort.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methods).contains("reserve", "settle", "release", "settleByBizKey", "releaseByBizKey");
        assertThat(OrgPointPort.ReserveCommand.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("bizKey", "orgId", "actorUserId", "actorPostId",
                        "subject", "quantity", "refType", "refId");
        assertThat(OrgPointPort.Settlement.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("operationId", "charged", "refunded", "replayed");
    }

    @Test
    void exposesQuotaPermissionAndDesignRecoveryContracts() {
        Set<String> methods = Arrays.stream(OrgPointPort.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methods).contains(
                "memberQuotaOf", "setMemberQuota", "clearMemberQuota",
                "memberPermissions", "setMemberPermission", "clearMemberPermission", "assertSubjectAllowed",
                "bindOperation", "findOperationByRef", "findOperationByBizKey", "findBinding",
                "promptOperations", "findPromptReceipt", "savePromptReceipt");
    }

    @Test
    void productionServiceRequiresPriceServiceInItsConstructor() {
        assertThat(cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService.class));
    }
}
