package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

/**
 * 报价（B3/B4 任务）：分项明细 JSONB，服务端计算金额（无审批——禁入清单）；
 * 抬头快照（客户名/电话/地址）与尺寸在创建时冻结；导出=规范票据 JSON。
 */
@Service
public class SaleQuoteService {

    public record QuoteCommand(long customerId, Long schemeId, Map<String, Object> items,
                               String priceVersion, String address,
                               BigDecimal width, BigDecimal depth, Integer floors, BigDecimal area) {
    }

    private final SaleQuoteMapper quotes;
    private final SaleRequirementService requirements;
    private final CustomerSchemePort customerSchemes;
    private final FranchiseCommands commands;
    private final SalesAccess access;

    public SaleQuoteService(SaleQuoteMapper quotes, SaleRequirementService requirements,
                            CustomerSchemePort customerSchemes, FranchiseCommands commands, SalesAccess access) {
        this.quotes = quotes;
        this.requirements = requirements;
        this.customerSchemes = customerSchemes;
        this.commands = commands;
        this.access = access;
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleQuoteDO create(PostSnapshot post, QuoteCommand cmd, String idempotencyKey) {
        access.requireOrgCustomer(post, cmd.customerId());
        // 方案只读快照（不隐式升级）：组织/客户一致性复核
        CustomerSchemePort.SchemeSnapshot snapshot = null;
        if (cmd.schemeId() != null) {
            snapshot = customerSchemes.getSchemeSnapshot(post, cmd.schemeId());
            if (snapshot == null || snapshot.companyId() != post.orgId()
                    || snapshot.customerId() != cmd.customerId()) {
                throw exception(REFERENCE_INVALID);
            }
        }
        // 服务端计算（覆盖客户端伪造值）。items 为显式透传契约（S1-20b）：行内展示字段（qtyText/unitText
        // 等 v3 附加键）原样落库/回显/export-sheet 返回，服务端只覆盖 own 计算字段——P4 导出依赖展示列。
        Map<String, Object> items = new LinkedHashMap<>(cmd.items() == null ? Map.of() : cmd.items());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> main = (List<Map<String, Object>>) (List<?>) items.getOrDefault("main", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decor = (List<Map<String, Object>>) (List<?>) items.getOrDefault("decor", List.of());
        long[] mainValues = SaleQuoteMath.normalizeRows("main", main);
        long[] decorValues = SaleQuoteMath.normalizeRows("decor", decor);
        long insurance = SaleQuoteMath.extraFee(items.get("insuranceCents"));
        long management = SaleQuoteMath.extraFee(items.get("managementCents"));
        long total = SaleQuoteMath.totalOf(SaleQuoteMath.sum(mainValues), SaleQuoteMath.sum(decorValues),
                insurance, management);

        // S1-23：指纹覆盖抬头与尺寸。漏 address/priceVersion/宽深层面积时，同键改这些字段会回放旧报价。
        var input = createFingerprint(cmd, items, total);
        var replayed = commands.replayed(post, idempotencyKey, input);
        if (replayed.isPresent()) {
            return quotes.selectById(replayed.get().getAggregateId());
        }
        // 抬头快照：客户名/电话取客户档案当时值（经 Port 校验后的受控读取——由 adapter 暴露当前客户名/电话）
        CustomerSchemePort.CustomerBrief brief = customerSchemes.getCustomerBrief(post, cmd.customerId());
        SaleQuoteDO quote = new SaleQuoteDO()
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
                .setSchemeId(cmd.schemeId())
                .setSchemeKind(snapshot == null ? null : snapshot.kind())
                .setSchemeVersion(snapshot == null ? null : snapshot.version())
                .setItems(JsonUtils.toJsonString(items)).setTotalCents(total)
                .setPriceVersion(trimmed(cmd.priceVersion()))
                .setCustomerName(brief.name()).setCustomerPhone(brief.phone()).setAddress(trimmed(cmd.address()))
                .setWidth(cmd.width()).setDepth(cmd.depth()).setFloors(cmd.floors()).setArea(cmd.area())
                .setAuthorUserId(post.userId());
        quotes.insert(quote);
        commands.record(post, "fr-sale-quote", quote.getId(), 1, "sale-quote-create", input, idempotencyKey);
        return quote;
    }

    public List<SaleQuoteDO> listByCustomer(PostSnapshot post, long customerId) {
        access.requireOrgCustomer(post, customerId);
        return quotes.selectByCustomer(customerId);
    }

    public SaleQuoteDO requireOfOrg(PostSnapshot post, long quoteId) {
        SaleQuoteDO quote = quotes.selectById(quoteId);
        if (quote == null || quote.getCompanyId() != post.orgId()) {
            throw exception(REFERENCE_INVALID);
        }
        return quote;
    }

    /** B4：规范票据 JSON（字段对齐 v3 quote-export sheet；全部取自创建时冻结快照） */
    public Map<String, Object> exportSheet(PostSnapshot post, long quoteId) {
        SaleQuoteDO quote = requireOfOrg(post, quoteId);
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("kind", "budget");
        sheet.put("version", quote.getId());
        sheet.put("date", quote.getCreateTime() == null ? null : quote.getCreateTime().toLocalDate().toString());
        sheet.put("name", quote.getCustomerName());
        sheet.put("phone", quote.getCustomerPhone());
        sheet.put("address", quote.getAddress());
        sheet.put("width", quote.getWidth());
        sheet.put("depth", quote.getDepth());
        sheet.put("area", quote.getArea());
        sheet.put("floors", quote.getFloors());
        @SuppressWarnings("unchecked")
        Map<String, Object> items = JsonUtils.parseObject(quote.getItems(), Map.class);
        // 行 Map 原样返回（含 qtyText/unitText 展示字段——S1-20b 显式透传契约，勿改为显式建模）
        sheet.put("main", items.get("main"));
        sheet.put("decor", items.get("decor"));
        sheet.put("mainTotal", sumGroup(items, "main"));
        sheet.put("decorTotal", sumGroup(items, "decor"));
        sheet.put("insurance", items.get("insuranceCents"));
        sheet.put("management", items.get("managementCents"));
        sheet.put("total", quote.getTotalCents());
        if (quote.getPriceVersion() != null) {
            sheet.put("priceVersion", quote.getPriceVersion());
        }
        sheet.put("basis", "前期预算，非施工结算依据");
        return sheet;
    }

    private static long sumGroup(Map<String, Object> items, String group) {
        Object rows = items.get(group);
        if (!(rows instanceof List<?> list)) {
            return 0;
        }
        return list.stream().mapToLong(r -> {
            Object v = ((Map<?, ?>) r).get("valueCents");
            return v instanceof Number n ? n.longValue() : 0;
        }).sum();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 报价创建幂等指纹（与 record 共用）。同键异地址/价目口径/尺寸必须冲突，不得回放旧单。 */
    static Map<String, Object> createFingerprint(QuoteCommand cmd, Map<String, Object> items, long totalCents) {
        var input = new LinkedHashMap<String, Object>();
        input.put("operation", "quote-create");
        input.put("customerId", cmd.customerId());
        input.put("schemeId", cmd.schemeId());
        input.put("items", items);
        input.put("totalCents", totalCents);
        input.put("priceVersion", trimmed(cmd.priceVersion()));
        input.put("address", trimmed(cmd.address()));
        input.put("width", cmd.width());
        input.put("depth", cmd.depth());
        input.put("floors", cmd.floors());
        input.put("area", cmd.area());
        return input;
    }
}
