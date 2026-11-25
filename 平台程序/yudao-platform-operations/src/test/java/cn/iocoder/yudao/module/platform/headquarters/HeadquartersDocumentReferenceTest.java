package cn.iocoder.yudao.module.platform.headquarters;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class HeadquartersDocumentReferenceTest {
    @Test void acceptsOnlyPositiveAssetIdsOrCompleteDispatchReferences() {
        assertThat(HeadquartersDocumentReference.parse("123").assetId()).isEqualTo(123);
        var result = HeadquartersDocumentReference.parse("{\"assetId\":\"456\",\"commissionId\":\"10\",\"dispatchSeq\":2,\"slot\":\"DRAWING\",\"url\":\"https://ignored.invalid\"}");
        assertThat(result.assetId()).isEqualTo(456);
        assertThat(result.commissionId()).isEqualTo(10);
        assertThat(result.dispatchSeq()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("url", "https:");
        for (String input : new String[]{null, "", "-1", "0", "9223372036854775808", "https://private.invalid?token=secret",
                "{\"assetId\":123,\"url\":\"https://private.invalid\"}", "{\"assetId\":123,\"commissionId\":1,\"dispatchSeq\":0,\"slot\":\"DRAWING\"}"}) {
            assertThat(HeadquartersDocumentReference.parse(input)).as("untrusted reference").isNull();
        }
    }
}
