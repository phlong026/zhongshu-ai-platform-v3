package cn.iocoder.yudao.module.franchise.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-23：报价/交付创建的幂等指纹必须覆盖前端会改、但原先未入哈希的字段。
 * 同键重试若只比客户与分项，改地址、价目口径、尺寸或需求编号会回放旧单。
 */
class SaleCommandFingerprintTest {

    @Test
    void quoteFingerprintDiffersWhenAddressPriceVersionOrSizeChanges() {
        Map<String, Object> items = Map.of("main", List.of());
        var base = quote("湖北武汉", "v1", new BigDecimal("12.0"));
        var basePrint = SaleQuoteService.createFingerprint(base, items, 100L);

        assertThat(SaleQuoteService.createFingerprint(quote("湖南长沙", "v1", new BigDecimal("12.0")), items, 100L))
                .isNotEqualTo(basePrint);
        assertThat(SaleQuoteService.createFingerprint(quote("湖北武汉", "v2", new BigDecimal("12.0")), items, 100L))
                .isNotEqualTo(basePrint);
        assertThat(SaleQuoteService.createFingerprint(quote("湖北武汉", "v1", new BigDecimal("13.0")), items, 100L))
                .isNotEqualTo(basePrint);
        assertThat(basePrint)
                .containsEntry("address", "湖北武汉")
                .containsEntry("priceVersion", "v1")
                .containsEntry("width", new BigDecimal("12.0"))
                .containsEntry("operation", "quote-create");
    }

    @Test
    void quoteFingerprintTrimsBlankAddressAndPriceVersionToNull() {
        Map<String, Object> print = SaleQuoteService.createFingerprint(
                quote("  ", " \t ", null), Map.of(), 1L);
        assertThat(print).containsEntry("address", null).containsEntry("priceVersion", null);
    }

    @Test
    void deliveryFingerprintIncludesRequirementId() {
        var withReq = SaleDeliveryService.createFingerprint(new SaleDeliveryService.DeliveryCommand(9L, 1L));
        var otherReq = SaleDeliveryService.createFingerprint(new SaleDeliveryService.DeliveryCommand(9L, 2L));
        var noReq = SaleDeliveryService.createFingerprint(new SaleDeliveryService.DeliveryCommand(9L, null));

        assertThat(withReq).isNotEqualTo(otherReq);
        assertThat(withReq).containsEntry("contractId", 9L).containsEntry("requirementId", 1L)
                .containsEntry("operation", "delivery-create");
        assertThat(noReq).containsEntry("requirementId", null);
    }

    private static SaleQuoteService.QuoteCommand quote(String address, String priceVersion, BigDecimal width) {
        return new SaleQuoteService.QuoteCommand(7L, 8L, Map.of(), priceVersion, address,
                width, new BigDecimal("9"), 2, new BigDecimal("108"));
    }
}
