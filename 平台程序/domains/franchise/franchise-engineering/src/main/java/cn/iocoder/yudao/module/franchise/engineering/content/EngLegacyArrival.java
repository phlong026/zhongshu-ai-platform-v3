package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyReceiptDO;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import org.springframework.jdbc.core.JdbcTemplate;

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
                       CommandEventDO event, List<FrEngSupplyReceiptDO> receipts, JdbcTemplate jdbc) {
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
        // Old reship confirmations consumed the pending batch's own quantity.
        if (cmd.expected() == null && cmd.parentReceiptId() != null) return;
        if (hasCorrection(row)) throw exception(ENGINEERING_INPUT_INVALID, "旧批次已更正，请核对原到货单上的应到数量");
        BigDecimal expected = cmd.expected() != null ? cmd.expected() : defaultAtRegistration(jdbc, event, row.getSupplyId());
        if (row.getExpectedQuantity() == null || row.getExpectedQuantity().compareTo(expected) != 0) {
            throw exception(IDEMPOTENCY_KEY_REUSED);
        }
    }

    /** The old default was the plan quantity less accepted receipts before this project's command revision. */
    private static BigDecimal defaultAtRegistration(JdbcTemplate jdbc, CommandEventDO event, long supplyId) {
        BigDecimal expected = jdbc.queryForObject("""
                WITH material AS (
                    SELECT m.id,m.quantity,s.id AS supply_id,s.company_id,s.project_id
                    FROM fr_eng_supply s JOIN fr_eng_material m
                      ON m.id=s.material_id AND m.company_id=s.company_id AND m.project_id=s.project_id
                    WHERE s.id=? AND s.company_id=? AND s.project_id=?
                ), history AS (
                    SELECT e.revision,e.revision < ? AS prior,e.command,e.payload::jsonb AS p
                    FROM pf_command_event e JOIN material m ON e.org_id=m.company_id AND e.aggregate_id=m.project_id
                    WHERE e.domain='franchise' AND e.aggregate_type='fr-eng-project' AND e.command IN
                        ('eng-supply-arrival','eng-reship-receive','eng-supply-quality','eng-arrival-correct','eng-material-adjust')
                ), batches AS (
                    SELECT b.* FROM fr_eng_supply_receipt b JOIN material m ON b.supply_id=m.supply_id AND b.company_id=m.company_id
                ), plans AS (
                    SELECT h.revision,h.prior,COALESCE(h.p->>'quantity',h.p->>'to')::numeric AS quantity
                    FROM history h JOIN material m ON h.p->>'materialId'=m.id::text WHERE h.command='eng-material-adjust'
                ), posted AS (
                    SELECT h.prior,(h.p->>'actual')::numeric AS actual,h.p->>'quality' AS quality
                    FROM history h JOIN material m ON h.p->>'supplyId'=m.supply_id::text WHERE h.command='eng-supply-arrival'
                    UNION ALL
                    SELECT h.prior,(h.p->>'actual')::numeric,b.quality FROM history h JOIN batches b ON h.p->>'receiptId'=b.id::text
                    WHERE h.command='eng-reship-receive'
                ), changes AS (
                    SELECT b.quality,b.quality_resolution,b.corrections,
                           COALESCE((b.corrections->0->>'from')::numeric,b.actual_quantity) AS original,
                           count(*) FILTER (WHERE h.command='eng-arrival-correct') AS total_changes,
                           count(*) FILTER (WHERE h.prior AND h.command='eng-arrival-correct')::int AS prior_changes,
                           bool_or(h.command='eng-supply-quality') AS replaced,
                           bool_or(h.prior AND h.command='eng-supply-quality') AS prior_replacement
                    FROM batches b LEFT JOIN history h ON h.p->>'receiptId'=b.id::text
                        AND h.command IN ('eng-arrival-correct','eng-supply-quality')
                    GROUP BY b.id,b.quality,b.quality_resolution,b.corrections,b.actual_quantity
                )
                SELECT (SELECT CASE WHEN (SELECT count(*) FROM posted)=(SELECT count(*) FROM batches WHERE actual_quantity IS NOT NULL)
                         AND NOT EXISTS (SELECT 1 FROM posted WHERE actual IS NULL OR quality IS NULL)
                         AND NOT EXISTS (SELECT 1 FROM changes WHERE COALESCE(jsonb_array_length(corrections),0)<>total_changes
                            OR COALESCE(quality_resolution->>'result'='更换后合格',false)<>COALESCE(replaced,false))
                    THEN (CASE WHEN EXISTS (SELECT 1 FROM plans WHERE NOT prior)
                         THEN (SELECT quantity FROM plans WHERE prior ORDER BY revision DESC LIMIT 1) ELSE m.quantity END)
                      - COALESCE((SELECT sum(actual) FROM posted WHERE prior AND quality='OK'),0)
                      - COALESCE((SELECT sum(
                            (CASE WHEN prior_replacement THEN original ELSE 0 END)
                            + (CASE WHEN prior_changes>0 AND (quality='OK' OR prior_replacement)
                                    THEN (corrections->(prior_changes-1)->>'to')::numeric-original ELSE 0 END))
                          FROM changes),0)
                    END FROM material m)
                """, BigDecimal.class, supplyId, event.getOrgId(), event.getAggregateId(), event.getRevision());
        if (expected == null) throw exception(ENGINEERING_INPUT_INVALID, "旧版到货缺少当时的需用量或验收记录，请核对原到货单");
        return expected.max(BigDecimal.ZERO);
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
