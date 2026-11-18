package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.design.controller.app.vo.AppItemizedBudgetRespVO;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/** T10-06: snapshot/persistence around the existing input resolver and exact calculator, not another engine. */
@Service
public class ItemizedBudgetService {
    private static final String MODEL = "ITEMIZED_V1";
    private static final String DISCLAIMER = "仅供参考，不构成报价或结算依据；未计价项目须补充核定后另行确认";
    private static final List<String> STANDARD_CODES = List.of("FOUNDATION", "STRUCTURE", "ROOF", "DECORATION",
            "DOORS_WINDOWS", "WALL_PAINT", "CULTURE_STONE", "LIGHTING", "WATERPROOF_LIGHTNING", "INSURANCE");
    private static final Map<String, List<String>> REQUIRED_GROUPS = Map.of(
            "DOORS_WINDOWS", List.of("DOOR", "WINDOW"), "WATERPROOF_LIGHTNING", List.of("WATERPROOF", "LIGHTNING"));
    private static final Map<String, String> GROUP_LABELS = Map.of("DOOR", "门", "WINDOW", "窗", "WATERPROOF", "防水", "LIGHTNING", "防雷");
    private static final String PUBLIC_RESULT_VERSION = " AND (model = 'LEGACY_RANGE' OR result_version_id IS NULL OR EXISTS (SELECT 1 FROM design_result_version v "
            + "WHERE v.tenant_id = 0 AND v.id = budget_estimate.result_version_id AND v.project_id = budget_estimate.project_id AND v.deleted = FALSE))";
    @jakarta.annotation.Resource
    private BudgetDraftService drafts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DesignProjectService projects;
    private final AuditPort audit;
    private final BudgetPriceResolver prices;
    private final cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricing;
    private boolean originalDesignRules;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBaselineSource(AiDesignBaselineSource source) {
        prices.setBaselineSource(source);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ItemizedBudgetService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                DesignProjectService projects, AuditPort audit,
                                cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricing) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.projects = projects;
        this.audit = audit;
        this.usagePricing = usagePricing;
        this.prices = new BudgetPriceResolver(jdbc);
    }

    public ItemizedBudgetService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                DesignProjectService projects, AuditPort audit) {
        this(dataSource, transactionManager, projects, audit, null);
    }

    public record SaveResult(String budgetId, boolean saved) {}
    public ItemizedBudgetService(DataSource source,PlatformTransactionManager manager,DesignProjectService projects,AuditPort audit,boolean originalDesignRules) {
        this(source,manager,projects,audit,null);this.originalDesignRules=originalDesignRules;
    }
    private BudgetInputs.Resolved resolveInputs(Map<String,Object> imported,Map<String,Object> overrides,BudgetInputs.FormulaProfile profile) {
        return originalDesignRules?OriginalBudgetRules.resolve(imported,overrides):BudgetInputs.resolve(imported,overrides,profile);
    }
    /** Internal paging references: the controller expands them through the old/new explicit public projections. */
    public record HistoryRef(String budgetId, String model) {}
    private record Request(Long resultVersionId, Map<String, Object> overrides, List<Long> optionIds,
                           cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation usageConfirmation,
                           List<String> requirementSnapshotIds, BudgetInputs.FormulaProfile profile,
                           Long expectedPriceVersion) {}
    private record FrozenLine(BudgetCalculator.LineInput input, Long priceVersionId, Map<String, Object> pricingSnapshot) {}
    /** T14: the caller's ACTIVE budget_account_price rows, keyed by option id. */
    private record AccountOverride(long accountPriceId, long version, long unitPriceCents) {}

    private Map<Long, AccountOverride> activePriceOverrides(long accountId) {
        Map<Long, AccountOverride> result = new HashMap<>();
        for (var row : jdbc.queryForList("SELECT id, version, unit_price_cents, option_id FROM budget_account_price "
                + "WHERE tenant_id = 0 AND account_id = ? AND status = 'ACTIVE' AND deleted = FALSE", accountId)) {
            result.put(number(row, "option_id"), new AccountOverride(number(row, "id"), number(row, "version"), number(row, "unit_price_cents")));
        }
        return result;
    }

    public AppItemizedBudgetRespVO create(long userId, long projectId, Map<String, Object> body, String key) {
        Request request = parseRequest(body);
        String hash = hash(JsonUtils.toJsonString(Map.of("projectId", String.valueOf(projectId), "body", canonical(body))));
        return command(userId, "CREATE", key, hash, AppItemizedBudgetRespVO.class,
                () -> requireProject(userId, projectId, request.resultVersionId(), true),
                () -> createSnapshot(userId, projectId, request, key, hash, null));
    }

    public AppItemizedBudgetRespVO createIndependent(long userId,long draftId,Map<String,Object> body,String key) {
        if (body==null || !Set.of("expectedVersion","usageConfirmation","expectedPriceVersion").containsAll(body.keySet())
                || !(body.get("expectedVersion") instanceof Number expected) || expected.longValue()<1 || expected.doubleValue()!=expected.longValue()) throw exception(BUDGET_INPUT_INVALID);
        String fingerprint=hash(JsonUtils.toJsonString(Map.of("draftId",String.valueOf(draftId),"body",canonical(body))));
        return command(userId,"CREATE",key,fingerprint,AppItemizedBudgetRespVO.class,()-> {
            drafts.requireActive(userId); drafts.require(userId,draftId,true);
        },()-> {
            var draft=drafts.require(userId,draftId,true);
            if (((Number)draft.get("version")).longValue()!=expected.longValue()) throw exception(STATE_VERSION_CONFLICT);
            var requestBody=new LinkedHashMap<String,Object>(BudgetInputs.readJson(draft.get("state").toString()));
            requestBody.put("usageConfirmation",body.get("usageConfirmation"));
            if (body.containsKey("expectedPriceVersion")) requestBody.put("expectedPriceVersion", body.get("expectedPriceVersion"));
            // New independent drafts use the workbook formula; historical project budgets stay LEGACY unless explicit.
            requestBody.put("calculationProfile",originalDesignRules?"LEGACY":"EXCEL_V1");
            return createSnapshot(userId,null,parseRequest(requestBody),key,fingerprint,draft);
        });
    }

    public CursorPageResult<HistoryRef> pageIndependent(long userId,Long draftId,boolean savedOnly,Long cursor,int limit) {
        if (limit<1 || limit>100 || (cursor!=null && cursor<=0)) throw exception(BUDGET_INPUT_INVALID);
        return transaction(()-> {
            if(draftId!=null) drafts.require(userId,draftId,false);
            var rows=jdbc.query("SELECT b.id,b.model FROM budget_estimate b JOIN budget_draft d ON d.id=b.draft_id AND d.user_id=b.user_id AND d.tenant_id=0 AND d.deleted=FALSE "
                    + "WHERE b.tenant_id=0 AND b.user_id=? AND b.project_id IS NULL AND b.deleted=FALSE AND b.model='ITEMIZED_V1' "
                    + "AND (?::bigint IS NULL OR b.draft_id=?) AND (?::bigint IS NULL OR b.id<?) "
                    + (savedOnly?"AND b.saved=TRUE ":"") + "ORDER BY b.id DESC LIMIT ?",
                    (rs,index)->new HistoryRef(rs.getString("id"),rs.getString("model")),userId,draftId,draftId,cursor,cursor,limit+1);
            boolean more=rows.size()>limit;var page=List.copyOf(more?rows.subList(0,limit):rows);
            return new CursorPageResult<>(page,more?page.get(page.size()-1).budgetId():null);
        });
    }

    public Optional<AppItemizedBudgetRespVO> get(long userId, long budgetId, Long revisionId) {
        return transaction(() -> {
            Map<String, Object> budget = requireBudget(userId, budgetId, false);
            if (!MODEL.equals(budget.get("model"))) {
                if (revisionId != null) throw exception(RESOURCE_FORBIDDEN);
                return Optional.empty();
            }
            return Optional.of(publicSnapshot(budgetId, revisionId, (Boolean) budget.get("saved")));
        });
    }

    public SaveResult save(long userId, long budgetId, String key) {
        String hash = hash(JsonUtils.toJsonString(Map.of("budgetId", String.valueOf(budgetId))));
        return command(userId, "SAVE", key, hash, SaveResult.class, () -> requireBudget(userId, budgetId, true), () -> {
            Map<String, Object> budget = requireBudget(userId, budgetId, true);
            if (!MODEL.equals(budget.get("model"))) throw exception(BUDGET_INPUT_INVALID);
            // Verifies that an authorized public initial revision exists; never recomputes from today's catalog.
            publicSnapshot(budgetId, null, (Boolean) budget.get("saved"));
            if (!Boolean.TRUE.equals(budget.get("saved"))) {
                jdbc.update("UPDATE budget_estimate SET saved = TRUE, updater = ?, update_time = now() WHERE tenant_id = 0 AND id = ?", String.valueOf(userId), budgetId);
                audit(userId, "SAVE", budgetId, Map.of("saved", true));
            }
            return new SaveResult(String.valueOf(budgetId), true);
        });
    }

    public List<AppItemizedBudgetRespVO> listByProject(long userId, long projectId) {
        return transaction(() -> {
            requireProject(userId, projectId, null, false);
            return jdbc.queryForList("SELECT id, saved FROM budget_estimate WHERE tenant_id = 0 AND user_id = ? AND project_id = ? AND model = 'ITEMIZED_V1' AND deleted = FALSE "
                            + "AND EXISTS (SELECT 1 FROM budget_revision r WHERE r.estimate_id = budget_estimate.id AND r.tenant_id = 0 AND r.revision_no = 1 AND r.actor_type = 'USER' AND r.actor_id = budget_estimate.user_id AND r.deleted = FALSE)"
                            + PUBLIC_RESULT_VERSION + " ORDER BY id DESC",
                    userId, projectId).stream().map(row -> publicSnapshot(number(row, "id"), null, (Boolean) row.get("saved"))).toList();
        });
    }

    public CursorPageResult<HistoryRef> pageByProject(long userId, long projectId, boolean savedOnly, Long cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor <= 0)) throw exception(BUDGET_INPUT_INVALID);
        return transaction(() -> {
            requireProject(userId, projectId, null, false);
            List<Object> args = new ArrayList<>(List.of(userId, projectId));
            String before = cursor == null ? "" : " AND id < ?";
            if (cursor != null) args.add(cursor);
            args.add(limit + 1);
            List<HistoryRef> rows = jdbc.query("SELECT id, model FROM budget_estimate WHERE tenant_id = 0 AND user_id = ? AND project_id = ? AND deleted = FALSE"
                            + (savedOnly ? " AND saved = TRUE" : "") + before
                            + " AND (model = 'LEGACY_RANGE' OR EXISTS (SELECT 1 FROM budget_revision r WHERE r.estimate_id = budget_estimate.id AND r.tenant_id = 0 AND r.revision_no = 1 AND r.actor_type = 'USER' AND r.actor_id = budget_estimate.user_id AND r.deleted = FALSE))"
                            + PUBLIC_RESULT_VERSION + " ORDER BY id DESC LIMIT ?",
                    (rs, index) -> new HistoryRef(rs.getString("id"), rs.getString("model")), args.toArray());
            boolean more = rows.size() > limit;
            List<HistoryRef> page = List.copyOf(more ? rows.subList(0, limit) : rows);
            return new CursorPageResult<>(page, more ? page.get(page.size() - 1).budgetId() : null);
        });
    }

    private AppItemizedBudgetRespVO createSnapshot(long userId, Long projectId, Request request, String key, String requestHash, Map<String,Object> draft) {
        // Every read below sees one database snapshot; row locks keep selected configuration stable until commit.
        var imported = draft == null ? projects.getBudgetInputSnapshot(userId, projectId, request.resultVersionId())
                : new DesignProjectService.BudgetInputSnapshot(BudgetInputs.readJson(draft.get("imported_inputs").toString()),
                    JsonUtils.parseObject(draft.get("source_snapshot_ids").toString(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        if (request.requirementSnapshotIds() != null && !request.requirementSnapshotIds().equals(imported.requirementSnapshotIds())) throw exception(STATE_VERSION_CONFLICT);
        BudgetInputs.Resolved original = resolveInputs(imported.inputs(), Map.of(), request.profile());
        BudgetInputs.Resolved current = resolveInputs(imported.inputs(), request.overrides(), request.profile());
        Object regionCode = current.values().get("regionCode");
        if (!(regionCode instanceof String selectedRegion) || selectedRegion.isBlank()) throw exception(BUDGET_INPUT_INVALID);
        // 统一取价解析器：平台地区基准/覆盖价；自定义地区配置价，未保存配置时武汉兜底（显式标记）。
        BudgetPriceResolver.Region region = prices.region(userId, selectedRegion);
        if (region.custom() && request.expectedPriceVersion() != null
                && (region.priceVersion() == null || !region.priceVersion().equals(request.expectedPriceVersion().intValue()))) {
            throw exception(BUDGET_PRICE_PROFILE_STALE);
        }
        Timestamp measuredAt = jdbc.queryForObject("SELECT now()", Timestamp.class);
        List<Map<String, Object>> allItems = jdbc.queryForList("SELECT * FROM budget_item WHERE tenant_id = 0 AND deleted = FALSE ORDER BY id FOR SHARE");
        Map<Long, Map<String, Object>> itemIndex = new LinkedHashMap<>();
        Map<String, Map<String, Object>> standard = new LinkedHashMap<>();
        for (var item : allItems) {
            itemIndex.put(number(item, "id"), item);
            if ("STANDARD".equals(item.get("source"))) standard.put((String) item.get("code"), item);
        }
        // The protected 3+7 identities are required even when not yet enabled/configured.
        if (!standard.keySet().containsAll(STANDARD_CODES)) throw exception(BUDGET_INPUT_INVALID);

        List<FrozenLine> frozen = new ArrayList<>();
        Set<String> selectedGroups = new HashSet<>();
        Set<Long> selectedItemIds = new HashSet<>();
        boolean userPriced = region.custom();
        for (long optionId : request.optionIds()) {
            var optionRows = jdbc.queryForList("SELECT * FROM budget_option WHERE tenant_id = 0 AND id = ? AND enabled = TRUE AND deleted = FALSE FOR SHARE", optionId);
            if (optionRows.isEmpty()) throw exception(BUDGET_INPUT_INVALID);
            Map<String, Object> option = optionRows.get(0), item = itemIndex.get(number(option, "item_id"));
            if (item == null || !Boolean.TRUE.equals(item.get("enabled")) || !Boolean.TRUE.equals(item.get("public_selectable"))) throw exception(BUDGET_INPUT_INVALID);
            long itemId = number(item, "id");
            String group = itemId + ":" + option.get("selection_group");
            if (!selectedGroups.add(group)) throw exception(BUDGET_INPUT_INVALID);
            selectedItemIds.add(itemId);
            BudgetPriceResolver.Price effective = prices.price(userId, region, optionId, measuredAt.toInstant());
            if (!region.custom() && effective.userPriced()) userPriced = true;
            if (effective.unitPriceCents() == null && "CUSTOM_TEMPLATE".equals(item.get("source"))) throw exception(BUDGET_INPUT_INVALID);
            Long baseUnitPriceCents = effective.unitPriceCents();
            var quantity = originalDesignRules?OriginalBudgetRules.resolveQuantity(current,(String)option.get("unit"),(String)option.get("quantity_source"),(String)option.get("quantity_key")):BudgetCalculator.resolveQuantity(current, (String) option.get("unit"), (String) option.get("quantity_source"), (String) option.get("quantity_key"));
            var coefficient = BudgetFormula.quantityCoefficient(current, (String) item.get("code"), (String) option.get("code"), (String) option.get("quantity_source"), quantity.source());
            String effectiveQuantity = BudgetFormula.scaleQuantity(quantity.value(), coefficient);
            // 用户价（覆盖/配置）视为该选项的最终单价，不再叠加公式系数；平台基准价仍按公式规则计算。
            Long effectiveUnitPriceCents = baseUnitPriceCents == null ? null
                    : (effective.userPriced() ? baseUnitPriceCents
                        : BudgetFormula.effectiveUnitPriceCents(current, (String) item.get("code"), (String) option.get("code"), baseUnitPriceCents));
            var input = new BudgetCalculator.LineInput(String.valueOf(IdWorker.getId()), String.valueOf(itemId), (String) item.get("code"), (String) item.get("category"),
                    (String) item.get("name"), String.valueOf(optionId), (String) option.get("label"), (String) option.get("unit"), effectiveQuantity,
                    effectiveUnitPriceCents, effective.freeReason(), null, (String) item.get("source"));
            Map<String, Object> pricingSnapshot = new LinkedHashMap<>();
            pricingSnapshot.put("regionScope", region.custom() ? "USER" : "PLATFORM");
            pricingSnapshot.put("regionId", String.valueOf(region.id()));
            pricingSnapshot.put("regionVersion", region.version());
            pricingSnapshot.put("itemVersion", item.get("version"));
            pricingSnapshot.put("optionCode", option.get("code"));
            pricingSnapshot.put("optionVersion", option.get("version"));
            pricingSnapshot.put("selectionGroup", option.get("selection_group"));
            pricingSnapshot.put("quantitySource", option.get("quantity_source"));
            pricingSnapshot.put("quantityKey", option.get("quantity_key"));
            pricingSnapshot.put("resolvedQuantitySource", quantity.source());
            pricingSnapshot.put("baseQuantity", quantity.value());
            pricingSnapshot.put("quantityCoefficient", coefficient.toPlainString());
            pricingSnapshot.put("baseUnitPriceCents", baseUnitPriceCents);
            pricingSnapshot.put("effectiveUnitPriceCents", effectiveUnitPriceCents);
            pricingSnapshot.put("foundationSurchargeCents", baseUnitPriceCents == null || effectiveUnitPriceCents == null
                    ? 0L : Math.max(0L, effectiveUnitPriceCents - baseUnitPriceCents));
            pricingSnapshot.put("foundationHeight", current.values().get("foundationHeight"));
            pricingSnapshot.put("wallFaces", current.values().get("wallFaces"));
            pricingSnapshot.put("formulaProfile", current.profile().name());
            pricingSnapshot.put("sourceReference", option.get("source_reference"));
            pricingSnapshot.put("priceVersion", effective.priceVersion());
            pricingSnapshot.put("effectiveAt", effective.effectiveAt());
            pricingSnapshot.put("expiresAt", effective.expiresAt());
            pricingSnapshot.put("priceSource", effective.userPriced() ? "ACCOUNT_OVERRIDE" : "DEFAULT");
            pricingSnapshot.put("pricingSource", effective.source());
            pricingSnapshot.put("basePriceCents", effective.basePriceCents());
            pricingSnapshot.put("basePriceVersion", effective.basePriceVersion());
            pricingSnapshot.put("coefficient", effective.coefficient());
            pricingSnapshot.put("profileCode", region.custom() ? region.code() : null);
            pricingSnapshot.put("profileVersion", region.custom() ? region.priceVersion() : null);
            if (effective.accountPriceId() != null) {
                pricingSnapshot.put("accountPriceId", effective.accountPriceId());
                pricingSnapshot.put("accountPriceVersion", effective.accountPriceVersion());
            }
            frozen.add(new FrozenLine(input, effective.priceVersionId(), pricingSnapshot));
        }
        for (String code : STANDARD_CODES) {
            var item = standard.get(code);
            long itemId = number(item, "id");
            if (REQUIRED_GROUPS.containsKey(code)) {
                for (String group : REQUIRED_GROUPS.get(code)) if (!selectedGroups.contains(itemId + ":" + group)) {
                    frozen.add(placeholder(item, group, GROUP_LABELS.get(group) + "待选择"));
                }
            } else if (!selectedItemIds.contains(itemId)) {
                frozen.add(placeholder(item, "ITEM", "待选择"));
            }
        }
        // Persist deterministic item and line order independently of client selection array order.
        frozen.sort(Comparator.comparingInt((FrozenLine line) -> ((Number) itemIndex.get(Long.parseLong(line.input().itemId())).get("sort_order")).intValue())
                .thenComparing(line -> Long.parseLong(line.input().itemId())).thenComparing(line -> line.input().lineId()));
        BudgetCalculator.Result calculated = originalDesignRules?OriginalBudgetRules.calculate(current,frozen.stream().map(FrozenLine::input).toList()):BudgetCalculator.calculate(current, frozen.stream().map(FrozenLine::input).toList());
        long budgetId = IdWorker.getId(), revisionId = IdWorker.getId();
        String createdAt = measuredAt.toInstant().toString();
        Map<String, Object> header = draft == null ? projectHeader(projectId, request.resultVersionId()) : Map.of("projectName",draft.get("name"),"schemeName",draft.get("source_project_id")==null?"独立填写参数":"导入本人项目参数副本");
        List<AppItemizedBudgetRespVO.Item> publicItems = calculated.items().stream().map(item -> new AppItemizedBudgetRespVO.Item(
                item.itemId(), item.itemCode(), item.category(), item.publicName(), item.source(), item.completeness(), item.pricedSubtotalCents(), item.amountCents(),
                item.lines().stream().map(line -> new AppItemizedBudgetRespVO.Line(line.input().lineId(), line.input().optionLabel(),
                        line.status(), line.amountCents(), line.input().quantity(), line.input().unit())).toList())).toList();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        calculated.items().stream().filter(item -> "INCOMPLETE".equals(item.completeness())).forEach(item -> missing.add(item.publicName()));
        Map<String, String> inputNames = Map.of("regionCode", "所在地区", "footprintArea", "占地面积", "buildingArea", "建筑面积", "floorCount", "建筑层数", "roofArea", "屋顶面积");
        calculated.missingFields().forEach(field -> missing.add(inputNames.getOrDefault(field, "预算参数")));
        int profileItemCount = 0, manualCount = 0;
        if (region.custom()) {
            var counts = jdbc.queryForMap("SELECT count(*) AS total, count(*) FILTER (WHERE price_source = 'MANUAL') AS manual "
                    + "FROM budget_account_price WHERE tenant_id = 0 AND account_id = ? AND region_scope = 'USER' "
                    + "AND region_code = ? AND status = 'ACTIVE' AND deleted = FALSE", userId, region.code());
            profileItemCount = ((Number) counts.get("total")).intValue();
            manualCount = ((Number) counts.get("manual")).intValue();
        }
        int totalStandard = Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM budget_item i JOIN budget_option o "
                + "ON o.tenant_id = i.tenant_id AND o.item_id = i.id AND o.enabled = TRUE AND o.deleted = FALSE "
                + "WHERE i.tenant_id = 0 AND i.source = 'STANDARD' AND i.enabled = TRUE AND i.public_selectable = TRUE "
                + "AND i.deleted = FALSE", Integer.class)).orElse(0);
        String pricingBasis = userPriced ? "USER_PROFILE" : "PLATFORM";
        Map<String, Object> pricingProfile = new LinkedHashMap<>();
        pricingProfile.put("regionCode", region.code());
        pricingProfile.put("regionName", region.name());
        pricingProfile.put("custom", region.custom());
        if (region.custom()) {
            pricingProfile.put("baseRegionCode", BudgetPriceResolver.BASELINE_REGION_CODE);
            pricingProfile.put("coefficient", region.coefficient() == null ? null : region.coefficient().toPlainString());
            pricingProfile.put("priceVersion", region.priceVersion());
            pricingProfile.put("configuredAt", region.configuredAt() == null ? null : region.configuredAt().toString());
            pricingProfile.put("itemCount", profileItemCount);
            pricingProfile.put("manualCount", manualCount);
            pricingProfile.put("totalCount", totalStandard);
            pricingProfile.put("missingCount", Math.max(0, totalStandard - profileItemCount));
            pricingProfile.put("status", region.status());
        }
        pricingProfile.put("pricingBasis", pricingBasis);
        pricingProfile.put("source", region.custom() ? "USER_PROFILE" : (userPriced ? "USER_OVERRIDE" : "PLATFORM"));
        var inputSummary = new LinkedHashMap<String, Object>(current.publicValues());
        // Preserve the validated site dimensions for the downstream customer/commission snapshot.
        for (String dimension : List.of("faceWidth", "depth")) {
            if (current.values().get(dimension) != null) inputSummary.put(dimension, current.values().get(dimension));
        }
        inputSummary.put("pricingProfile", pricingProfile);
        if (!originalDesignRules) {
        inputSummary.put("calculationProfile", current.profile().name());
        var quote = BudgetCalculator.quoteSummary(calculated);
        var quoteSummary = new LinkedHashMap<String, Object>();
        quoteSummary.put("bodySubtotalCents", quote.bodySubtotalCents());
        quoteSummary.put("exteriorSubtotalCents", quote.exteriorSubtotalCents());
        quoteSummary.put("constructionSubtotalCents", quote.constructionSubtotalCents());
        quoteSummary.put("insuranceCents", quote.insuranceCents());
        quoteSummary.put("managementFeeCents", quote.managementFeeCents());
        quoteSummary.put("managementFeeRate", quote.managementFeeRate().toPlainString());
        quoteSummary.put("grandTotalCents", quote.grandTotalCents());
        inputSummary.put("quoteSummary", quoteSummary);
        }
        AppItemizedBudgetRespVO response = new AppItemizedBudgetRespVO(String.valueOf(budgetId), String.valueOf(revisionId), MODEL,
                projectId == null ? null : String.valueOf(projectId), (String) header.get("projectName"), request.resultVersionId() == null ? null : request.resultVersionId().toString(),
                (String) header.get("schemeName"), region.name(), inputSummary, calculated.completeness(), calculated.pricedSubtotalCents(),
                calculated.totalCents(), calculated.categoryTotals(), publicItems, List.copyOf(missing), calculated.warnings(), DISCLAIMER, createdAt, false).withDraftId(draft==null?null:draft.get("id").toString());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (draft!=null) {
            snapshot.put("draftId",draft.get("id").toString());
            snapshot.put("draftVersion",draft.get("version"));
            snapshot.put("sourceProjectId",draft.get("source_project_id"));
            snapshot.put("sourceVersionId",draft.get("source_version_id"));
        }
        snapshot.put("importedInputs", imported.inputs());
        snapshot.put("importedValues", original.values());
        snapshot.put("importedSources", original.sources());
        snapshot.put("inputOverrides", request.overrides());
        snapshot.put("currentValues", current.values());
        snapshot.put("currentSources", current.sources());
        snapshot.put("quantities", current.quantities());
        snapshot.put("requirementSnapshotIds", imported.requirementSnapshotIds());
        snapshot.put("selectedOptionIds", request.optionIds().stream().map(String::valueOf).toList());
        snapshot.put("regionCode", region.code());
        snapshot.put("pricingProfile", pricingProfile);
        snapshot.put("pricingBasis", pricingBasis);
        snapshot.put("publicResult", response);
        String snapshotJson = JsonUtils.toJsonString(canonical(snapshot));
        jdbc.update("INSERT INTO budget_estimate (id, project_id, user_id, result_version_id, input_snapshot, model, current_revision, idempotency_key, request_hash, tenant_id, creator, updater, create_time, draft_id) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'ITEMIZED_V1', 1, ?, ?, 0, ?, ?, ?, ?)", budgetId, projectId, userId, request.resultVersionId(), snapshotJson, key, requestHash, String.valueOf(userId), String.valueOf(userId), measuredAt, draft==null?null:draft.get("id"));
        jdbc.update("INSERT INTO budget_revision (id, estimate_id, revision_no, input_snapshot, completeness, body_subtotal_cents, exterior_subtotal_cents, priced_subtotal_cents, total_cents, pricing_basis, "
                        + "actor_type, actor_id, change_reason, idempotency_key, request_hash, tenant_id, creator, updater, create_time) VALUES (?, ?, 1, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, 'USER', ?, '用户生成预算测算', ?, ?, 0, ?, ?, ?)",
                revisionId, budgetId, snapshotJson, calculated.completeness(), calculated.categoryTotals().get("BODY"), calculated.categoryTotals().get("EXTERIOR"), calculated.pricedSubtotalCents(), calculated.totalCents(), pricingBasis,
                userId, key, requestHash, String.valueOf(userId), String.valueOf(userId), measuredAt);
        Map<String, FrozenLine> frozenIndex = new HashMap<>(); frozen.forEach(line -> frozenIndex.put(line.input().lineId(), line));
        int sort = 0;
        for (var result : calculated.lines()) {
            var line = result.input(); var source = frozenIndex.get(line.lineId());
            jdbc.update("INSERT INTO budget_line (id, revision_id, line_key, item_id, option_id, price_version_id, item_code, public_name, option_label, category, source, unit, quantity, unit_price_cents, amount_cents, "
                            + "status, free_reason, pricing_snapshot, sort_order, tenant_id, creator, updater, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 0, ?, ?, ?)",
                    Long.parseLong(line.lineId()), revisionId, line.optionId() == null ? "MISSING_" + line.itemId() + "_" + source.pricingSnapshot().get("selectionGroup") : "OPTION_" + line.optionId(),
                    Long.parseLong(line.itemId()), line.optionId() == null ? null : Long.parseLong(line.optionId()), source.priceVersionId(),
                    line.itemCode(), line.publicName(), line.optionLabel(), line.category(), line.source(), line.unit(), line.quantity() == null ? null : new BigDecimal(line.quantity()), line.unitPriceCents(), result.amountCents(),
                    result.status(), line.freeReason(), JsonUtils.toJsonString(canonical(source.pricingSnapshot())), sort++, String.valueOf(userId), String.valueOf(userId), measuredAt);
        }
        audit(userId, "CREATE", budgetId, Map.of("revisionId", String.valueOf(revisionId), "completeness", calculated.completeness()));
        if (usagePricing != null) {
            var usageSnapshot = usagePricing.quote("BUDGET_ESTIMATE");
            cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.requireConfirmed(usageSnapshot, request.usageConfirmation());
            usagePricing.prepareCharge(userId, usageSnapshot, "budget_estimate", String.valueOf(budgetId));
            usagePricing.chargePrepared(userId, "BUDGET_ESTIMATE", "budget_estimate", String.valueOf(budgetId));
        }
        return response;
    }

    private FrozenLine placeholder(Map<String, Object> item, String group, String label) {
        String id = String.valueOf(number(item, "id"));
        var input = new BudgetCalculator.LineInput(String.valueOf(IdWorker.getId()), id, (String) item.get("code"), (String) item.get("category"), (String) item.get("name"),
                null, label, "ITEM", null, null, null, null, "STANDARD");
        return new FrozenLine(input, null, Map.of("selectionGroup", group, "missingSelection", true, "itemVersion", item.get("version")));
    }

    private AppItemizedBudgetRespVO publicSnapshot(long budgetId, Long revisionId, boolean saved) {
        // Admin revisions are not implicitly public. T10-07/08 must add a separate, explicit publication rule.
        var rows = jdbc.queryForList("SELECT r.id, r.input_snapshot->'publicResult' AS public_result FROM budget_revision r JOIN budget_estimate b ON b.id = r.estimate_id "
                + "WHERE r.tenant_id = 0 AND b.tenant_id = 0 AND r.estimate_id = ? AND r.revision_no = 1 AND r.actor_type = 'USER' AND r.actor_id = b.user_id AND r.deleted = FALSE", budgetId);
        if (rows.isEmpty() || (revisionId != null && number(rows.get(0), "id") != revisionId) || rows.get(0).get("public_result") == null) throw exception(RESOURCE_FORBIDDEN);
        var response = JsonUtils.parseObject(rows.get(0).get("public_result").toString(), AppItemizedBudgetRespVO.class);
        return response.withSaved(saved);
    }

    private Map<String, Object> requireBudget(long userId, long budgetId, boolean lock) {
        if (userId == 0 || budgetId <= 0) throw exception(RESOURCE_FORBIDDEN);
        if (lock && drafts != null) drafts.requireActive(userId);
        var rows = jdbc.queryForList("SELECT id, project_id, draft_id, result_version_id, model, saved FROM budget_estimate WHERE tenant_id = 0 AND id = ? AND user_id = ? AND deleted = FALSE" + (lock ? " FOR UPDATE" : ""), budgetId, userId);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        Map<String, Object> budget = rows.get(0);
        if (budget.get("project_id")==null) {
            if (!MODEL.equals(budget.get("model")) || budget.get("draft_id")==null || budget.get("result_version_id")!=null) throw exception(RESOURCE_FORBIDDEN);
            drafts.require(userId,number(budget,"draft_id"),false);
            return budget;
        }
        requireProject(userId, number(budget, "project_id"), !MODEL.equals(budget.get("model")) || budget.get("result_version_id") == null ? null : number(budget, "result_version_id"), lock);
        return budget;
    }

    private void requireProject(long userId, long projectId, Long versionId, boolean lock) {
        if (userId == 0 || projectId <= 0) throw exception(RESOURCE_FORBIDDEN);
        projects.getProject(projectId, userId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        var rows = jdbc.queryForList("SELECT id FROM design_project WHERE tenant_id = 0 AND id = ? AND user_id = ? AND deleted = FALSE" + (lock ? " FOR SHARE" : ""), projectId, userId);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        if (versionId != null && jdbc.queryForList("SELECT id FROM design_result_version WHERE tenant_id = 0 AND id = ? AND project_id = ? AND deleted = FALSE" + (lock ? " FOR SHARE" : ""), versionId, projectId).isEmpty()) throw exception(RESOURCE_FORBIDDEN);
    }

    private Map<String, Object> projectHeader(long projectId, Long versionId) {
        // 方案重命名（2026-10-05）：用户自定义名称优先于来源案例标题/编号展示
        var rows = jdbc.queryForList("SELECT p.name AS project_name, v.title AS case_title FROM design_project p "
                + "LEFT JOIN design_case_version v ON v.id = p.ref_version_id WHERE p.tenant_id = 0 AND p.id = ?", projectId);
        String projectName = "设计项目 " + projectId;
        if (!rows.isEmpty()) {
            Object named = rows.get(0).get("project_name");
            Object caseTitle = rows.get(0).get("case_title");
            if (named != null && !String.valueOf(named).isBlank()) projectName = String.valueOf(named);
            else if (caseTitle != null) projectName = (String) caseTitle;
        }
        String schemeName = versionId == null ? "项目参数测算" : "方案版本 " + jdbc.queryForObject("SELECT version FROM design_result_version WHERE tenant_id = 0 AND id = ?", String.class, versionId);
        return Map.of("projectName", projectName, "schemeName", schemeName);
    }

    private <T> T command(long userId, String operation, String key, String fingerprint, Class<T> responseType, Runnable authorize, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 64 || !key.equals(key.trim())) throw exception(BUDGET_INPUT_INVALID);
        return transaction(() -> {
            authorize.run(); // Replayed commands must still belong to an existing, accessible project/version.
            jdbc.update("INSERT INTO budget_app_command (user_id, operation, idempotency_key, request_hash) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", userId, operation, key, fingerprint);
            var receipt = jdbc.queryForMap("SELECT request_hash, response::text FROM budget_app_command WHERE user_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE", userId, operation, key);
            if (!fingerprint.equals(receipt.get("request_hash"))) throw exception(IDEMPOTENCY_KEY_REUSED);
            if (receipt.get("response") != null) return JsonUtils.parseObject((String) receipt.get("response"), responseType);
            T response = action.get();
            jdbc.update("UPDATE budget_app_command SET response = CAST(? AS jsonb) WHERE user_id = ? AND operation = ? AND idempotency_key = ?", JsonUtils.toJsonString(response), userId, operation, key);
            return response;
        });
    }

    private <T> T transaction(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return tx.execute(status -> action.get()); }
            catch (ConcurrencyFailureException concurrent) {
                // PostgreSQL repeatable-read can abort a waiter after another transaction commits. Retry the entire atomic command.
                if (attempt >= 2) throw exception(STATE_VERSION_CONFLICT);
            }
        }
    }

    private void audit(long userId, String action, long budgetId, Map<String, Object> detail) {
        audit.record(AuditEventMessage.builder().eventType("BUDGET_ESTIMATE_CHANGED").actorType(AuditEventMessage.ActorType.USER)
                .actorId(String.valueOf(userId)).action(action).bizType("budget_estimate").bizId(String.valueOf(budgetId))
                .result(AuditEventMessage.AuditResult.SUCCESS).tenantId(0L).detail(detail).build());
    }

    private Request parseRequest(Map<String, Object> body) {
        if (body == null || !Set.of("resultVersionId", "inputOverrides", "optionIds", "requirementSnapshotIds", "usageConfirmation", "calculationProfile", "expectedPriceVersion").containsAll(body.keySet())) throw exception(BUDGET_INPUT_INVALID);
        Long resultVersionId = body.get("resultVersionId") == null ? null : BudgetInputs.positiveId(string(body.get("resultVersionId")));
        Map<String, Object> overrides = body.get("inputOverrides") == null ? Map.of() : object(body.get("inputOverrides"));
        BudgetInputs.FormulaProfile profile = originalDesignRules?BudgetInputs.FormulaProfile.LEGACY:parseProfile(body.get("calculationProfile"));
        // Validate override whitelist/decimal representation independently from persisted project input.
        resolveInputs(Map.of(), overrides, profile);
        if (!(body.get("optionIds") instanceof List<?> options) || options.size() > 100) throw exception(BUDGET_INPUT_INVALID);
        List<Long> ids = new ArrayList<>();
        for (Object id : options) ids.add(BudgetInputs.positiveId(string(id)));
        if (new HashSet<>(ids).size() != ids.size()) throw exception(BUDGET_INPUT_INVALID);
        // Canonical lock order removes deadlocks caused by two callers ordering the same options differently.
        ids.sort(Long::compareTo);
        List<String> snapshotIds = null;
        if (body.containsKey("requirementSnapshotIds")) {
            if (!(body.get("requirementSnapshotIds") instanceof List<?> supplied) || supplied.size() > 10000) throw exception(BUDGET_INPUT_INVALID);
            snapshotIds = new ArrayList<>();
            for (Object id : supplied) { String value = string(id); BudgetInputs.positiveId(value); snapshotIds.add(value); }
            if (new HashSet<>(snapshotIds).size() != snapshotIds.size()) throw exception(BUDGET_INPUT_INVALID);
        }
        cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation confirmation = null;
        if (body.get("usageConfirmation") instanceof Map<?, ?> raw) {
            confirmation = new cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation(
                    string(raw.get("product")), string(raw.get("ruleId")), Long.parseLong(String.valueOf(raw.get("ruleVersion"))));
        }
        // 客户端把版本号作为 JSON number 发送（配置接口返回的 priceVersion 是 int）；
        // 这里同时接受 number/string，不能用 string() 强制 String，否则会误报 BUDGET_INPUT_INVALID。
        Long expectedPriceVersion = body.get("expectedPriceVersion") == null ? null
                : BudgetInputs.positiveId(String.valueOf(body.get("expectedPriceVersion")));
        return new Request(resultVersionId, Collections.unmodifiableMap(overrides), List.copyOf(ids), confirmation,
                snapshotIds == null ? null : List.copyOf(snapshotIds), profile, expectedPriceVersion);
    }

    private static BudgetInputs.FormulaProfile parseProfile(Object value) {
        if (value == null) return BudgetInputs.FormulaProfile.LEGACY;
        String text = string(value);
        return switch (text) {
            case "LEGACY" -> BudgetInputs.FormulaProfile.LEGACY;
            case "EXCEL_V1" -> BudgetInputs.FormulaProfile.EXCEL_V1;
            default -> throw exception(BUDGET_INPUT_INVALID);
        };
    }

    private static String string(Object value) { if (!(value instanceof String text)) throw exception(BUDGET_INPUT_INVALID); return text; }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw exception(BUDGET_INPUT_INVALID);
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, entry) -> result.put(string(key), entry));
        return result;
    }
    private static Object canonical(Object value) {
        // JsonUtils omits Java null map entries by default. NullNode preserves explicit clears in hashes and frozen inputs.
        if (value == null) return NullNode.getInstance();
        if (value instanceof Map<?, ?> map) { Map<String, Object> sorted = new TreeMap<>(); map.forEach((key, entry) -> sorted.put(string(key), canonical(entry))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(ItemizedBudgetService::canonical).toList();
        return value;
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String instant(Object value) { return value == null ? null : ((Timestamp) value).toInstant().toString(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
