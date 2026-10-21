package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.DESIGN_REQUIREMENT_INPUT_INVALID;

/** T10-03: only input normalization/derivation; not a pricing engine or another persistence service. */
public final class BudgetInputs {

    public static final BigDecimal MAX_AREA = new BigDecimal("1000000");
    public static final BigDecimal MAX_LENGTH = new BigDecimal("1000");
    public static final BigDecimal MAX_HEIGHT = new BigDecimal("20");
    public static final int MAX_FLOORS = 20;
    public static final int QUANTITY_SCALE = 4;
    /** LEGACY keeps every historical snapshot and contract unchanged; EXCEL_V1 follows the 2026 price workbook. */
    public enum FormulaProfile { LEGACY, EXCEL_V1 }
    private static final Set<String> AREAS = Set.of("footprintArea", "buildingArea", "roofArea");
    /** Optional areas: zero is meaningful and must not become "missing". */
    private static final Set<String> OPTIONAL_AREAS = Set.of("terraceArea", "notchArea");
    private static final Set<String> LENGTHS = Set.of("faceWidth", "depth", "foundationHeight", "gutterWidth",
            "buildingPerimeter");
    private static final Set<String> TEXTS = Set.of("ownerName", "ownerPhone", "addressProvince", "addressCity",
            "addressCounty", "addressTown", "addressVillage");
    private static final Set<String> EAVES = Set.of("leftRightEaveMm", "frontBackEaveMm");
    private static final Set<String> STAIRCASE_TYPES = Set.of("NONE", "SPIRAL", "THREE_RUN");
    private static final Set<String> FIELDS = Set.of("regionCode", "footprintArea", "buildingArea", "roofArea",
            "faceWidth", "depth", "floorCount", "quantities",
            "terraceArea", "notchArea", "foundationHeight", "wallFaces", "gutterWidth", "buildingPerimeter",
            "ownerName", "ownerPhone", "addressProvince", "addressCity", "addressCounty", "addressTown", "addressVillage",
            "leftRightEaveMm", "frontBackEaveMm", "voidLivingRoom", "staircaseType");
    // 2026-10-11 优化建议②：quantities 允许用户覆盖——AI 设计需求不含工程量时，
    // 用户在预算参数页快捷录入（或按经验值预填），测算即可达 COMPLETE，不必恒为"待核定"。
    // 2026-10-13：独立快速预算页开放面宽/进深/建筑面积覆盖——面宽×进深可派生占地面积，
    // 用户实测建筑面积优先于占地×层数推算（不一致时 resolve 已给出 BUILDING_AREA_REVIEW_REQUIRED）。
    private static final Set<String> OVERRIDES = Set.of("regionCode", "footprintArea", "roofArea", "floorCount",
            "faceWidth", "depth", "buildingArea", "quantities",
            "terraceArea", "notchArea", "foundationHeight", "wallFaces", "gutterWidth", "buildingPerimeter",
            "ownerName", "ownerPhone", "addressProvince", "addressCity", "addressCounty", "addressTown", "addressVillage",
            "leftRightEaveMm", "frontBackEaveMm", "voidLivingRoom", "staircaseType");
    /** Required public fields: only these block COMPLETE. Optional site fields are informational. */
    private static final List<String> PUBLIC_FIELDS = List.of("regionCode", "footprintArea", "floorCount", "buildingArea", "roofArea");
    private static final List<String> PUBLIC_OPTIONAL_FIELDS = List.of("terraceArea", "notchArea", "foundationHeight",
            "wallFaces", "gutterWidth", "buildingPerimeter", "ownerName", "ownerPhone", "addressProvince", "addressCity",
            "addressCounty", "addressTown", "addressVillage", "leftRightEaveMm", "frontBackEaveMm", "voidLivingRoom",
            "staircaseType");
    private static final BigDecimal DEFAULT_GUTTER_WIDTH = new BigDecimal("0.4");
    private static final int DEFAULT_WALL_FACES = 4;
    private static final Set<String> QUANTITIES = Set.of("DOOR_HOUSEHOLDS", "WINDOW_AREA", "WALL_PAINT_AREA",
            "CULTURE_STONE_LENGTH", "LIGHTING_WASHER_LENGTH", "LIGHTING_STRIP_LENGTH", "LIGHTING_WALL_LAMP_COUNT",
            "WATERPROOF_AREA", "INSULATED_WATERPROOF_AREA");
    private static final Set<String> COUNTS = Set.of("DOOR_HOUSEHOLDS", "LIGHTING_WALL_LAMP_COUNT");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    // T15 设计生成输入：白名单收敛 runtime 提示词面；取值上限与小程序控件一致。
    // roofType/material/color/count 为立面任务配置键（elevationConfig 同经本白名单）。
    private static final Set<String> DESIGN_LENGTHS = Set.of("faceWidthM", "depthM");
    private static final Set<String> DESIGN_TEXTS = Set.of("floor", "family", "prompt", "note");
    private static final Set<String> DESIGN_ROOMS = Set.of("bedroom", "living", "bath", "kitchen");
    private static final Set<String> DESIGN_CODES = Set.of("styleCode", "roofType", "material", "color",
            "entranceOrientation", "courtyardLayout", "drawingType");
    private static final Set<String> DESIGN_KEYS = Set.of("faceWidthM", "depthM", "floor", "floorCount",
            "family", "rooms", "prompt", "note", "styleCode", "roofType", "material", "color", "count",
            "entranceOrientation", "courtyardLayout", "drawingType", "referenceAssetIds", "referenceCaseId");
    private static final int DESIGN_TEXT_MAX = 200;
    private static final int DESIGN_FLOOR_MAX = 4;
    private static final BigDecimal MIN_DESIGN_LENGTH = new BigDecimal("3");
    private static final BigDecimal MAX_DESIGN_LENGTH = new BigDecimal("40");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private BudgetInputs() { }

    public record Resolved(Map<String, Object> values, Map<String, String> sources,
                           Map<String, String> quantities, Map<String, String> derivedQuantities,
                           List<String> missingFields, List<String> warnings, FormulaProfile profile) {
        public Resolved(Map<String, Object> values, Map<String, String> sources,
                        Map<String, String> quantities, List<String> missingFields, List<String> warnings) {
            this(values, sources, quantities, Map.of(), missingFields, warnings, FormulaProfile.LEGACY);
        }
        public Map<String, Object> publicValues() {
            var result = new LinkedHashMap<String, Object>();
            result.putAll(publicFields(values, PUBLIC_FIELDS));
            result.putAll(publicFields(values, PUBLIC_OPTIONAL_FIELDS));
            return Collections.unmodifiableMap(result);
        }
        public Map<String, String> publicSources() {
            var result = new LinkedHashMap<String, String>();
            result.putAll(publicFields(sources, PUBLIC_FIELDS));
            result.putAll(publicFields(sources, PUBLIC_OPTIONAL_FIELDS));
            return Collections.unmodifiableMap(result);
        }
    }

    /**
     * T15: 设计生成输入在 budgetInputs 之外新增受控键——未知键直接拒绝，保持 runtime 提示词面封闭；
     * budgetInputs 仍按 T10 契约归一化。两个命名空间互不透传。
     */
    public static Map<String, Object> validateRequirementInputs(Map<String, Object> inputs) {
        if (inputs == null) return null;
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : asMap(inputs).entrySet()) {
            if (!"budgetInputs".equals(entry.getKey()) && !DESIGN_KEYS.contains(entry.getKey())) {
                throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        if (copy.containsKey("budgetInputs")) {
            copy.put("budgetInputs", normalize(asMap(copy.get("budgetInputs")), true));
        }
        if (copy.containsKey("referenceAssetIds")) {
            // 需求五b：立面参考图资产 id 数组，0~3 个数字串，空列表表示明确清除本次上传参考；非法即拒（所有权在业务层校验）
            Object value = copy.get("referenceAssetIds");
            if (!(value instanceof List<?> refs) || refs.size() > 3
                    || refs.stream().anyMatch(r -> !String.valueOf(r).matches("\\d{1,19}"))) {
                throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
            }
            copy.put("referenceAssetIds", refs.stream().map(r -> String.valueOf(r)).toList());
        }
        if (copy.containsKey("referenceCaseId")) {
            Object id = copy.get("referenceCaseId");
            if (!(id instanceof String text) || !text.isEmpty() && !text.matches("[1-9][0-9]{0,18}")) {
                throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
            }
            try { if (!((String) id).isEmpty()) Long.parseLong((String) id); }
            catch (NumberFormatException invalid) { throw exception(DESIGN_REQUIREMENT_INPUT_INVALID); }
        }
        normalizeDesignInputs(copy);
        if (JsonUtils.toJsonString(copy).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
            throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
        }
        return copy;
    }

    private static void normalizeDesignInputs(LinkedHashMap<String, Object> inputs) {
        for (var entry : inputs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            try {
                if (DESIGN_LENGTHS.contains(key)) {
                    BigDecimal length = decimal(value, MAX_DESIGN_LENGTH, false);
                    if (length.compareTo(MIN_DESIGN_LENGTH) < 0) throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
                    entry.setValue(plain(length));
                } else if ("floorCount".equals(key) || "count".equals(key)) {
                    entry.setValue(decimal(value, BigDecimal.valueOf(DESIGN_FLOOR_MAX), true).intValueExact());
                } else if (DESIGN_TEXTS.contains(key)) {
                    entry.setValue(designText(value));
                } else if (DESIGN_CODES.contains(key)) {
                    entry.setValue(designCode(value));
                } else if ("rooms".equals(key)) {
                    entry.setValue(normalizeRooms(value));
                }
            } catch (ServiceException ex) {
                throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
            }
        }
    }

    private static String designText(Object input) {
        if (!(input instanceof String text)) throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
        // wxml textarea 允许换行等控制字符：按方案"剔除"而非整单拒绝，剔除后再校验长度
        String cleaned = text.trim();
        if (!cleaned.isEmpty()) {
            cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("").trim();
            if (cleaned.length() > DESIGN_TEXT_MAX) throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
        }
        return cleaned;
    }

    private static String designCode(Object input) {
        if (!(input instanceof String text) || !text.matches("[A-Za-z0-9_]{1,32}")) {
            throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
        }
        return text;
    }

    private static Map<String, Integer> normalizeRooms(Object input) {
        Map<String, Object> rooms = asMap(input);
        if (!DESIGN_ROOMS.containsAll(rooms.keySet())) throw exception(DESIGN_REQUIREMENT_INPUT_INVALID);
        var result = new LinkedHashMap<String, Integer>();
        for (var entry : rooms.entrySet()) {
            if (entry.getValue() == null) continue;
            result.put(entry.getKey(), decimal(entry.getValue(), new BigDecimal(20), true).intValueExact());
        }
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, Object> readJson(String json) {
        Map<String, Object> parsed = json == null || json.isBlank() ? null : JsonUtils.parseObject(json, MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    /** Read only explicit fields. Never extract quantities/area from free text, images or UI demo values. */
    public static Map<String, Object> fromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return Map.of();
        var fields = new LinkedHashMap<String, Object>();
        for (String field : FIELDS) {
            if (snapshot.containsKey(field)) fields.put(field, snapshot.get(field));
        }
        // T15 设计输入以 faceWidthM/depthM 落快照；预算派生读取 faceWidth/depth。仅补键名映射，值原样，
        // 让设计阶段已填的尺寸能派生出占地面积，否则预算页一片“待填写”。
        if (!fields.containsKey("faceWidth") && snapshot.get("faceWidthM") != null) {
            fields.put("faceWidth", snapshot.get("faceWidthM"));
        }
        if (!fields.containsKey("depth") && snapshot.get("depthM") != null) {
            fields.put("depth", snapshot.get("depthM"));
        }
        if (!fields.containsKey("floorCount") && "两层".equals(snapshot.get("floor"))) {
            fields.put("floorCount", 2); // The existing app's stored enum, not a guessed default.
        }
        if (snapshot.get("budgetInputs") instanceof Map<?, ?> nested) {
            for (String field : FIELDS) {
                if (nested.containsKey(field)) fields.put(field, nested.get(field));
            }
        }
        return normalize(fields, false);
    }

    /** Empty overrides restore imported values. Inputs are copied, never mutated or saved back to the project. */
    public static Resolved resolve(Map<String, Object> imported, Map<String, Object> overrides) {
        return resolve(imported, overrides, FormulaProfile.LEGACY);
    }

    /**
     * EXCEL_V1 follows the 2026 workbook: notch/terrace geometry, top-floor roof area, perimeter,
     * wall-face coefficient and derived professional quantities. LEGACY keeps every historical
     * snapshot and contract unchanged.
     */
    public static Resolved resolve(Map<String, Object> imported, Map<String, Object> overrides, FormulaProfile profile) {
        FormulaProfile effectiveProfile = profile == null ? FormulaProfile.LEGACY : profile;
        Map<String, Object> supplied = overrides == null ? Map.of() : overrides;
        if (!OVERRIDES.containsAll(supplied.keySet())) throw exception(BUDGET_INPUT_INVALID);
        var raw = new LinkedHashMap<>(normalize(imported, true));
        var suppliedNormalized = normalize(supplied, true);
        for (var entry : suppliedNormalized.entrySet()) {
            if (!"quantities".equals(entry.getKey())) {
                raw.put(entry.getKey(), entry.getValue());
                continue;
            }
            // 逐键合并工程量：只改一项时不清空其余导入值；显式 null 只删除该键。
            var merged = new LinkedHashMap<String, String>();
            if (raw.get("quantities") instanceof Map<?, ?> base) {
                base.forEach((key, value) -> merged.put(key.toString(), value == null ? null : value.toString()));
            }
            if (entry.getValue() instanceof Map<?, ?> updates) {
                updates.forEach((key, value) -> {
                    if (value == null) merged.remove(key.toString());
                    else merged.put(key.toString(), value.toString());
                });
            }
            raw.put("quantities", Collections.unmodifiableMap(merged));
        }
        var values = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        for (String field : FIELDS) {
            if (!"quantities".equals(field) && raw.get(field) != null) {
                values.put(field, raw.get(field));
                sources.put(field, supplied.containsKey(field) ? "USER_OVERRIDE" : "PROJECT");
            }
        }
        var warnings = new ArrayList<String>();
        BigDecimal notch = value(values, "notchArea");
        BigDecimal terrace = value(values, "terraceArea");
        if (effectiveProfile == FormulaProfile.EXCEL_V1) {
            if (values.get("wallFaces") == null) {
                values.put("wallFaces", DEFAULT_WALL_FACES);
                sources.put("wallFaces", "DERIVED");
            }
            if (notch == null) {
                values.put("notchArea", "0");
                sources.put("notchArea", "DERIVED");
                notch = BigDecimal.ZERO;
            }
            if (terrace == null) {
                values.put("terraceArea", "0");
                sources.put("terraceArea", "DERIVED");
                terrace = BigDecimal.ZERO;
            }
            if (values.get("gutterWidth") == null) {
                values.put("gutterWidth", plain(DEFAULT_GUTTER_WIDTH));
                sources.put("gutterWidth", "DERIVED");
            }
            if (values.get("voidLivingRoom") == null) {
                values.put("voidLivingRoom", false);
                sources.put("voidLivingRoom", "DERIVED");
            }
            if (values.get("staircaseType") == null) {
                values.put("staircaseType", "NONE");
                sources.put("staircaseType", "DERIVED");
            }
        }

        BigDecimal footprint = value(values, "footprintArea");
        if (footprint == null && value(values, "faceWidth") != null && value(values, "depth") != null
                && !supplied.containsKey("footprintArea")) {
            BigDecimal area = value(values, "faceWidth").multiply(value(values, "depth"));
            if (effectiveProfile == FormulaProfile.EXCEL_V1 && notch != null) area = area.subtract(notch);
            footprint = derivedArea(area, "footprintArea", warnings);
            if (footprint != null) {
                values.put("footprintArea", plain(footprint));
                sources.put("footprintArea", "DERIVED");
            }
        }
        BigDecimal expectedArea = null;
        if (footprint != null && values.get("floorCount") != null) {
            BigDecimal area = footprint.multiply(new BigDecimal(values.get("floorCount").toString()));
            if (effectiveProfile == FormulaProfile.EXCEL_V1 && terrace != null) area = area.subtract(terrace);
            expectedArea = derivedArea(area, "buildingArea", warnings);
        }
        BigDecimal actualArea = value(values, "buildingArea");
        if (actualArea == null && expectedArea != null) {
            values.put("buildingArea", plain(expectedArea));
            sources.put("buildingArea", "DERIVED");
        } else if (actualArea != null && expectedArea != null && actualArea.compareTo(expectedArea) != 0) {
            warnings.add("BUILDING_AREA_REVIEW_REQUIRED"); // Keep actual area; do not silently overwrite it.
        }

        BigDecimal eaveArea = null;
        if (effectiveProfile == FormulaProfile.EXCEL_V1) {
            if (value(values, "roofArea") == null && footprint != null) {
                BigDecimal topFloor = footprint.subtract(terrace == null ? BigDecimal.ZERO : terrace);
                BigDecimal roof = topFloor;
                BigDecimal width = value(values, "faceWidth");
                BigDecimal depth = value(values, "depth");
                BigDecimal sideEave = millimeters(values, "leftRightEaveMm");
                BigDecimal frontEave = millimeters(values, "frontBackEaveMm");
                if (width != null && depth != null && (sideEave.signum() > 0 || frontEave.signum() > 0)) {
                    // 屋檐按四面外扩计：左右各外扩 side、前后各外扩 front，檐口面积加在顶楼面积上。
                    BigDecimal extra = sideEave.multiply(depth).multiply(new BigDecimal("2"))
                            .add(frontEave.multiply(width).multiply(new BigDecimal("2")))
                            .add(sideEave.multiply(frontEave).multiply(new BigDecimal("4")));
                    eaveArea = derivedArea(extra, "eaveArea", warnings);
                    if (eaveArea != null) roof = roof.add(eaveArea);
                }
                BigDecimal derivedRoof = derivedArea(roof, "roofArea", warnings);
                if (derivedRoof != null) {
                    values.put("roofArea", plain(derivedRoof));
                    sources.put("roofArea", "DERIVED");
                }
            }
            if (value(values, "buildingPerimeter") == null
                    && value(values, "faceWidth") != null && value(values, "depth") != null) {
                BigDecimal perimeter = value(values, "faceWidth").add(value(values, "depth"))
                        .multiply(new BigDecimal("2"));
                if (perimeter.signum() > 0 && perimeter.compareTo(MAX_LENGTH) <= 0) {
                    values.put("buildingPerimeter", plain(perimeter));
                    sources.put("buildingPerimeter", "DERIVED");
                }
            }
        }

        var quantities = new LinkedHashMap<String, String>();
        if (raw.get("quantities") instanceof Map<?, ?> map) {
            map.forEach((key, quantity) -> { if (quantity != null) quantities.put(key.toString(), quantity.toString()); });
        }
        var derivedQuantities = new LinkedHashMap<String, String>();
        if (effectiveProfile == FormulaProfile.EXCEL_V1) {
            BigDecimal building = value(values, "buildingArea");
            BigDecimal perimeter = value(values, "buildingPerimeter");
            BigDecimal roof = value(values, "roofArea");
            BigDecimal gutter = value(values, "gutterWidth");
            BigDecimal gutterPerimeter = perimeter;
            BigDecimal width = value(values, "faceWidth");
            BigDecimal depth = value(values, "depth");
            BigDecimal sideEave = millimeters(values, "leftRightEaveMm");
            BigDecimal frontEave = millimeters(values, "frontBackEaveMm");
            if (width != null && depth != null && (sideEave.signum() > 0 || frontEave.signum() > 0)) {
                gutterPerimeter = width.add(depth).add(sideEave.multiply(new BigDecimal("2")))
                        .add(frontEave.multiply(new BigDecimal("2"))).multiply(new BigDecimal("2"));
            }
            Integer faces = intValue(values.get("wallFaces"));
            if (building != null && faces != null) {
                // Base area; the profile coefficient is applied once in BudgetFormula.
                derivedQuantities.put("WALL_PAINT_AREA", plain(building));
            }
            if (perimeter != null) derivedQuantities.put("CULTURE_STONE_LENGTH", plain(perimeter));
            if (gutterPerimeter != null && gutter != null) {
                BigDecimal area = gutterPerimeter.multiply(gutter).add(terrace == null ? BigDecimal.ZERO : terrace);
                derivedQuantities.put("WATERPROOF_AREA", plain(area));
            }
            if (gutterPerimeter != null) derivedQuantities.put("GUTTER_PERIMETER", plain(gutterPerimeter));
            if (eaveArea != null) derivedQuantities.put("EAVE_AREA", plain(eaveArea));
            if (roof != null) derivedQuantities.put("INSULATED_WATERPROOF_AREA", plain(roof));
            derivedQuantities.put("DOOR_HOUSEHOLDS", "1");
        }
        return new Resolved(Collections.unmodifiableMap(values), Collections.unmodifiableMap(sources),
                Collections.unmodifiableMap(quantities), Collections.unmodifiableMap(derivedQuantities),
                PUBLIC_FIELDS.stream().filter(f -> !values.containsKey(f)).toList(),
                List.copyOf(warnings), effectiveProfile);
    }

    public static BigDecimal wallFaceCoefficient(int faces) {
        return switch (faces) {
            case 1 -> new BigDecimal("1.4");
            case 2 -> new BigDecimal("1.6");
            case 3 -> new BigDecimal("1.8");
            case 4 -> new BigDecimal("2.2");
            default -> throw exception(BUDGET_INPUT_INVALID);
        };
    }

    /** Existing result-version API also returns config JSON: remove internal budget quantities there too. */
    public static String publicConfigJson(String json) {
        if (json == null) return null;
        var config = new LinkedHashMap<>(readJson(json));
        config.remove("quantities");
        if (config.containsKey("budgetInputs")) {
            config.put("budgetInputs", publicFields(fromSnapshot(Collections.singletonMap("budgetInputs", config.get("budgetInputs")))));
        }
        return JsonUtils.toJsonString(config);
    }

    public static long positiveId(String id) {
        try {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw exception(BUDGET_INPUT_INVALID);
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw exception(BUDGET_INPUT_INVALID);
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> inputs, boolean strict) {
        var result = new LinkedHashMap<String, Object>();
        if (inputs == null) return result;
        if (strict && !FIELDS.containsAll(inputs.keySet())) throw exception(BUDGET_INPUT_INVALID);
        for (String field : FIELDS) {
            if (!inputs.containsKey(field)) continue;
            Object input = inputs.get(field);
            try {
                if (input == null) { result.put(field, null); continue; }
                if (TEXTS.contains(field)) {
                    if (strict && !(input instanceof String)) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, textValue(input, "ownerPhone".equals(field) ? 30 : 60));
                } else if (EAVES.contains(field)) {
                    if (strict && !(input instanceof String)) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, decimal(input, new BigDecimal("5000"), true, true).intValueExact());
                } else if ("voidLivingRoom".equals(field)) {
                    if (strict && !(input instanceof Boolean)) throw exception(BUDGET_INPUT_INVALID);
                    if (!(input instanceof Boolean || "true".equals(input) || "false".equals(input))) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, input instanceof Boolean ? input : Boolean.valueOf(input.toString()));
                } else if ("staircaseType".equals(field)) {
                    if (!(input instanceof String text) || !STAIRCASE_TYPES.contains(text)) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, text);
                } else if (AREAS.contains(field) || OPTIONAL_AREAS.contains(field) || LENGTHS.contains(field)) {
                    if (strict && !(input instanceof String)) throw exception(BUDGET_INPUT_INVALID);
                    BigDecimal max = LENGTHS.contains(field)
                            ? ("foundationHeight".equals(field) ? MAX_HEIGHT
                                : "gutterWidth".equals(field) ? new BigDecimal("5") : MAX_LENGTH)
                            : MAX_AREA;
                    boolean allowZero = OPTIONAL_AREAS.contains(field) || "foundationHeight".equals(field);
                    result.put(field, plain(decimal(input, max, false, allowZero)));
                } else if ("floorCount".equals(field)) {
                    result.put(field, decimal(input, BigDecimal.valueOf(MAX_FLOORS), true).intValueExact());
                } else if ("wallFaces".equals(field)) {
                    result.put(field, decimal(input, BigDecimal.valueOf(4), true).intValueExact());
                } else if ("regionCode".equals(field)) {
                    if (!(input instanceof String text) || !text.matches("[A-Za-z0-9_-]{1,32}")) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, input);
                } else {
                    Map<String, Object> quantities = asMap(input);
                    if (!QUANTITIES.containsAll(quantities.keySet())) throw exception(BUDGET_INPUT_INVALID);
                    var canonical = new LinkedHashMap<String, String>();
                    for (var entry : quantities.entrySet()) {
                        boolean count = COUNTS.contains(entry.getKey());
                        if (strict && entry.getValue() != null && !(entry.getValue() instanceof String)) throw exception(BUDGET_INPUT_INVALID);
                        canonical.put(entry.getKey(), entry.getValue() == null ? null : plain(decimal(entry.getValue(),
                                count ? new BigDecimal("100000") : MAX_AREA, count)));
                    }
                    result.put(field, Collections.unmodifiableMap(canonical));
                }
            } catch (ServiceException ex) {
                if (strict) throw ex;
                result.put(field, null); // Invalid saved data is missing, never zero or a guessed fallback.
            }
        }
        return result;
    }

    private static String textValue(Object input, int max) {
        if (!(input instanceof String text)) throw exception(BUDGET_INPUT_INVALID);
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > max || CONTROL_CHARS.matcher(cleaned).find()) throw exception(BUDGET_INPUT_INVALID);
        return cleaned;
    }

    private static BigDecimal decimal(Object input, BigDecimal max, boolean integer) {
        return decimal(input, max, integer, false);
    }

    private static BigDecimal decimal(Object input, BigDecimal max, boolean integer, boolean allowZero) {
        if (!(input instanceof String || input instanceof Number)) throw exception(BUDGET_INPUT_INVALID);
        String text = input.toString();
        if (text.length() > 20 || !text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")) throw exception(BUDGET_INPUT_INVALID);
        BigDecimal value = new BigDecimal(text);
        if ((!allowZero && value.signum() <= 0) || (allowZero && value.signum() < 0)
                || value.compareTo(max) > 0 || (integer && value.stripTrailingZeros().scale() > 0)) {
            throw exception(BUDGET_INPUT_INVALID);
        }
        return value;
    }

    private static Integer intValue(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).intValueExact();
    }

    private static BigDecimal derivedArea(BigDecimal value, String field, List<String> warnings) {
        BigDecimal rounded = value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0 || rounded.compareTo(MAX_AREA) > 0) {
            warnings.add("DERIVED_" + field + "_OUT_OF_RANGE");
            return null;
        }
        return rounded;
    }

    private static BigDecimal millimeters(Map<String, Object> values, String field) {
        Object raw = values.get(field);
        return raw == null ? BigDecimal.ZERO : new BigDecimal(raw.toString()).divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal value(Map<String, Object> values, String field) {
        return values.get(field) == null ? null : new BigDecimal(values.get(field).toString());
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    private static Map<String, Object> asMap(Object input) {
        if (!(input instanceof Map<?, ?> map)) throw exception(BUDGET_INPUT_INVALID);
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> { if (!(key instanceof String)) throw exception(BUDGET_INPUT_INVALID); copy.put((String) key, value); });
        return copy;
    }

    private static <T> Map<String, T> publicFields(Map<String, T> values) {
        return publicFields(values, PUBLIC_FIELDS);
    }

    private static <T> Map<String, T> publicFields(Map<String, T> values, List<String> fields) {
        var result = new LinkedHashMap<String, T>();
        for (String field : fields) if (values.containsKey(field)) result.put(field, values.get(field));
        return Collections.unmodifiableMap(result);
    }
}
