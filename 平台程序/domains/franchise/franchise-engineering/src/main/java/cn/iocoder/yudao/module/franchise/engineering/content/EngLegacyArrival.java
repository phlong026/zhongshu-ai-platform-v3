package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyReceiptDO;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.ENGINEERING_INPUT_INVALID;

/** Recover the omitted facts only from the original, organization-scoped supply records. */
final class EngLegacyArrival {
    private EngLegacyArrival() {}

    static void verify(String command, EngMaterialService.ArrivalCommand cmd, Map<String, Object> input,
                       CommandEventDO event, List<FrEngSupplyReceiptDO> receipts) {
        if ("eng-supply-quality".equals(command)) {
            var row = byId(receipts, (long) input.get("receiptId"));
            if (row.getQualityResolution() == null) throw exception(IDEMPOTENCY_KEY_REUSED);
            var resolution = JsonUtils.parseTree(row.getQualityResolution());
            if (!"更换后合格".equals(resolution.path("result").asText())
                    || !Objects.equals(input.get("note"), resolution.path("note").asText())
                    || !Objects.equals(input.get("fileAssetId"), resolution.path("fileAssetId").asLong())) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return;
        }
        var row = cmd.parentReceiptId() == null ? originalReceipt(cmd, input, event, receipts)
                : byId(receipts, cmd.parentReceiptId());
        var date = cmd.occurredAt() != null ? cmd.occurredAt()
                : event.getOccurredAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        if (!Objects.equals(row.getQuality(), input.get("quality"))
                || !Objects.equals(row.getTransactionNo(), input.get("transactionNo"))
                || !Objects.equals(row.getFileAssetId(), cmd.fileAssetId())
                || !Objects.equals(row.getOccurredAt(), date)
                || !Objects.equals(Objects.toString(row.getNote(), ""), input.get("note"))) {
            throw exception(IDEMPOTENCY_KEY_REUSED);
        }
        if (cmd.expected() != null) {
            if (hasCorrection(row)) throw exception(ENGINEERING_INPUT_INVALID, "旧批次已更正，请核对原到货单上的应到数量");
            if (row.getExpectedQuantity() == null || row.getExpectedQuantity().compareTo(cmd.expected()) != 0) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
        }
    }

    private static FrEngSupplyReceiptDO originalReceipt(EngMaterialService.ArrivalCommand cmd, Map<String, Object> input,
                                                        CommandEventDO event, List<FrEngSupplyReceiptDO> receipts) {
        // The original event saved supply, actual and quality. A supplied transaction number alone
        // cannot identify the original event when two batches shared those facts.
        var matches = receipts.stream().filter(r -> FrEngSupplyReceiptDO.KIND_RECEIPT.equals(r.getKind())
                && Objects.equals(r.getAuthorUserId(), event.getActorUserId())
                && Objects.equals(r.getQuality(), input.get("quality"))
                && originallyReceived(r, cmd.actual())).toList();
        if (matches.size() != 1) throw exception(ENGINEERING_INPUT_INVALID, "旧版到货事件无法唯一定位批次，请核对原到货单");
        return matches.get(0);
    }

    private static FrEngSupplyReceiptDO byId(List<FrEngSupplyReceiptDO> receipts, long id) {
        return receipts.stream().filter(row -> row.getId() == id).findFirst()
                .orElseThrow(() -> exception(IDEMPOTENCY_KEY_REUSED));
    }

    private static boolean hasCorrection(FrEngSupplyReceiptDO row) {
        return row.getCorrections() != null && !JsonUtils.parseTree(row.getCorrections()).isEmpty();
    }

    private static boolean originallyReceived(FrEngSupplyReceiptDO row, BigDecimal requested) {
        if (!hasCorrection(row)) {
            return row.getActualQuantity() != null && row.getActualQuantity().compareTo(requested) == 0;
        }
        String first = JsonUtils.parseTree(row.getCorrections()).get(0).path("from").asText();
        return !first.isBlank() && new BigDecimal(first).compareTo(requested) == 0;
    }
}
