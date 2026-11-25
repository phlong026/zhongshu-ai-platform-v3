package cn.iocoder.yudao.module.platform.wallet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class CompanyRechargeStateTest {
    @Test void refundFactsTakePrecedenceOverPreviouslyCreditedRecharge() {
        assertThat(CompanyRechargeCatalog.displayState("SUCCEEDED","CREDITED","PROCESSING","RESERVED")).isEqualTo("REFUNDING");
        assertThat(CompanyRechargeCatalog.displayState("SUCCEEDED","CREDITED","SUCCEEDED","REVERSED")).isEqualTo("REFUNDED");
        assertThat(CompanyRechargeCatalog.displayState("SUCCEEDED","CREDITED","SUCCEEDED","DEBT")).isEqualTo("REFUND_REVIEW");
        assertThat(CompanyRechargeCatalog.displayState("SUCCEEDED","CREDITED",null,null)).isEqualTo("CREDITED");
        assertThat(CompanyRechargeCatalog.displayState("SUCCEEDED","CREDITED","FAILED","RELEASED")).isEqualTo("CREDITED");
    }
}
