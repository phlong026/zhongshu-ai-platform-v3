package cn.iocoder.yudao.module.franchise.commission.controller.admin.vo;

import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M05-T04/M06-T03 前端版本展示的数据源契约：详情响应必须携带冻结版本链——
 * 委托行=确认下发的方案快照（dispatchedSchemeVersion）+ 下发冻结的实际提交版本（dispatchedSubmissionVersion）；
 * 移交包行=提交时冻结的方案版本（frozenSchemeVersion，变更留版本不覆盖）。
 */
class CommissionRespVosTest {

    @Test
    void commissionCarriesDispatchedVersionSnapshot() {
        CommissionDO c = base().setDispatchSeq(2)
                .setDispatchedSchemeVersion("P1/B1/F1").setDispatchedSubmissionVersion(2);
        CommissionRespVos.Commission vo = CommissionRespVos.Commission.of(c);
        assertThat(vo.dispatchedSchemeVersion()).isEqualTo("P1/B1/F1");
        assertThat(vo.dispatchedSubmissionVersion()).isEqualTo(2);
        assertThat(vo.dispatchSeq()).isEqualTo(2);
    }

    @Test
    void commissionCarriesNullVersionsBeforeDispatch() {
        CommissionDO c = base().setDispatchSeq(null)
                .setDispatchedSchemeVersion(null).setDispatchedSubmissionVersion(null);
        CommissionRespVos.Commission vo = CommissionRespVos.Commission.of(c);
        assertThat(vo.dispatchedSchemeVersion()).isNull();
        assertThat(vo.dispatchedSubmissionVersion()).isNull();
        assertThat(vo.dispatchSeq()).isNull();
    }

    /** record 含原始类型（customerId/submissionVersion/revision）——fixture 补齐非版本字段的非空基线 */
    private CommissionDO base() {
        return new CommissionDO().setId(9L).setCustomerId(3L).setCustomerName("客户甲")
                .setStatus(CommissionDO.STATUS_DESIGNING).setSubmissionVersion(1).setRevision(0L);
    }

    @Test
    void handoverCarriesFrozenSchemeVersion() {
        CommissionHandoverDO h = new CommissionHandoverDO().setId(7L).setCommissionId(9L)
                .setVersion(2).setDispatchSeq(1).setManagerUserId(5L).setSubmittedByUserId(5L)
                .setFrozenSchemeVersion("P2/B1/F1");
        CommissionRespVos.Handover vo = CommissionRespVos.Handover.of(h);
        assertThat(vo.frozenSchemeVersion()).isEqualTo("P2/B1/F1");
        assertThat(vo.version()).isEqualTo(2);
    }

}
