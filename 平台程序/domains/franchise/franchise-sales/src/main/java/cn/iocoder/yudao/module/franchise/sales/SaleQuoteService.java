package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.hutool.core.util.IdUtil;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public SaleQuoteService(SaleQuoteMapper quotes, SaleRequirementService requirements,
                            CustomerSchemePort customerSchemes, FranchiseCommands commands, SalesAccess access, JdbcTemplate jdbc) {
        this.quotes = quotes;
        this.requirements = requirements;
        this.customerSchemes = customerSchemes;
        this.commands = commands;
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional(rollbackFor = Exception.class)
    public SaleQuoteDO create(PostSnapshot post, QuoteCommand cmd, String idempotencyKey) {
        access.requireOrgCustomer(post, cmd.customerId());
        String key = commands.requireIdempotencyKey(idempotencyKey);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s", Integer.class,
                FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope(FranchiseRoles.DOMAIN, post.userId()), key));
        BigDecimal width = dimension(cmd.width());
        BigDecimal depth = dimension(cmd.depth());
        if (cmd.floors() == null || cmd.floors() < 1 || cmd.floors() > 20) throw new IllegalArgumentException("请填写1至20层");
        // 对照版允许按实际建筑面积核定（如中空/退台），不能用外轮廓乘层数覆盖人工确认值。
        BigDecimal area = cmd.area() == null ? width.multiply(depth).multiply(BigDecimal.valueOf(cmd.floors())).setScale(2, java.math.RoundingMode.HALF_UP) : cmd.area();
        if (area.signum() <= 0 || area.compareTo(new BigDecimal("99999.99")) > 0) throw new IllegalArgumentException("请填写有效的实际建筑面积");
        try { area = area.setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("建筑面积最多保留两位小数"); }
        String priceVersion = trimmed(cmd.priceVersion());
        String address = trimmed(cmd.address());
        if (priceVersion != null && priceVersion.length() > 64 || address != null && address.length() > 256)
            throw new IllegalArgumentException("报价版本或地址长度超出限制");
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

        var input = new LinkedHashMap<String, Object>();
        input.put("operation", "quote-create");
        input.put("customerId", cmd.customerId());
        input.put("schemeId", cmd.schemeId());
        input.put("items", items);
        input.put("totalCents", total);
        input.put("width", width); input.put("depth", depth); input.put("floors", cmd.floors()); input.put("area", area);
        input.put("priceVersion", priceVersion); input.put("address", address);
        var replayed = commands.replayed(post, key, input);
        if (replayed.isPresent()) {
            return quotes.selectById(replayed.get().getAggregateId());
        }
        // 与委托提交共用客户锁：预算落库与委托精确版本冻结不能交错。
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",
                Integer.class, "commission-customer:" + cmd.customerId());
        long quoteId = IdUtil.getSnowflakeNextId();
        var budgetPayload = new LinkedHashMap<String,Object>(input);
        budgetPayload.put("quoteId", quoteId);
        var budget = customerSchemes.saveScheme(post, cmd.customerId(), new CustomerSchemePort.SchemeSave(
                "BUDGET", "建房预算", JsonUtils.toJsonString(budgetPayload), width, depth, cmd.floors(), area, total, null));
        // 抬头快照：客户名/电话取客户档案当时值（经 Port 校验后的受控读取——由 adapter 暴露当前客户名/电话）
        CustomerSchemePort.CustomerBrief brief = customerSchemes.getCustomerBrief(post, cmd.customerId());
        SaleQuoteDO quote = new SaleQuoteDO().setId(quoteId)
                .setBudgetSchemeId(budget.schemeId()).setBudgetVersion(budget.version())
                .setCompanyId(post.orgId()).setCustomerId(cmd.customerId())
                .setSchemeId(cmd.schemeId())
                .setSchemeKind(snapshot == null ? null : snapshot.kind())
                .setSchemeVersion(snapshot == null ? null : snapshot.version())
                .setItems(JsonUtils.toJsonString(items)).setTotalCents(total)
                .setPriceVersion(priceVersion)
                .setCustomerName(brief.name()).setCustomerPhone(brief.phone()).setAddress(address)
                .setWidth(width).setDepth(depth).setFloors(cmd.floors()).setArea(area)
                .setAuthorUserId(post.userId());
        quotes.insert(quote);
        commands.record(post, "fr-sale-quote", quote.getId(), 1, "sale-quote-create", input, key);
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

    private static BigDecimal dimension(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("999.99")) > 0)
            throw new IllegalArgumentException("请填写有效的开间和进深");
        try { return value.setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("开间和进深最多保留两位小数"); }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
