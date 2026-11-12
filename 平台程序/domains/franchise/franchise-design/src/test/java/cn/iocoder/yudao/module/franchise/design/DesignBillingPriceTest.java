package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.billing.DesignWalletChargeAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DesignBillingPriceTest {
    @Test void bPriceUsesUnitsAndNeverACostMicros() {
        var price=new PlatformPriceSnapshot("7",1,4,9,0,22,2,1,0);
        var facts=new Settlement(99,4,"PARTIALLY_SUCCEEDED",true,2,1,900_000,800_000,100_000,1,1,0);
        assertThat(DesignWalletChargeAdapter.retailPoints(price,facts)).isEqualTo(13);
    }

    @Test void independentPriceArithmeticRejectsOverflowAndOverspend() {
        var price=new PlatformPriceSnapshot("7",1,4,9,0,13,1,1,0);
        var excessive=new Settlement(99,4,"SUCCEEDED",true,2,2,1,1,0,1,2,0);
        assertThatThrownBy(() -> DesignWalletChargeAdapter.retailPoints(price,excessive)).isInstanceOf(IllegalArgumentException.class);
        var overflow=new PlatformPriceSnapshot("7",1,Long.MAX_VALUE,1,0,Long.MAX_VALUE,1,1,0);
        var facts=new Settlement(99,4,"SUCCEEDED",true,1,1,0,0,0,1,1,0);
        assertThatThrownBy(() -> DesignWalletChargeAdapter.retailPoints(overflow,facts)).isInstanceOf(ArithmeticException.class);
    }
}
