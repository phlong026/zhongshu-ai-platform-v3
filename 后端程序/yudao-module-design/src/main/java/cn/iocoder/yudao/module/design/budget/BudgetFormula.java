package cn.iocoder.yudao.module.design.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * EXCEL_V1 pricing rules from 2026预算公式培训(1)(2).xlsx.
 *
 * <p>The calculator itself stays a pure quantity × unit-price function. This class only answers
 * three workbook-specific questions:</p>
 * <ul>
 *   <li>Does the option multiply the quantity (框剪 1.1, 外墙漆按面数)?</li>
 *   <li>Does the foundation height add a per-square-metre surcharge?</li>
 *   <li>What is the effective unit price stored on the frozen line?</li>
 * </ul>
 *
 * <p>Keep every rule here; do not scatter coefficients through controllers or the UI. If the
 * regional price already bakes a coefficient in, publish the base price instead and keep this
 * rule active, or switch the budget to LEGACY explicitly.</p>
 */
public final class BudgetFormula {

    private static final BigDecimal STEP = new BigDecimal("0.3");
    /** Set false when regional prices already bake in 1.1 / face / height surcharges. */
    private static boolean coefficientsEnabled() {
        String value = System.getProperty("zhongshu.budget.excel-coefficients",
                System.getenv().getOrDefault("ZHONGSHU_BUDGET_EXCEL_COEFFICIENTS", "true"));
        return !"false".equalsIgnoreCase(value);
    }

    private BudgetFormula() { }

    /** 框剪：1100 × 建筑面积 × 1.1（1.1 为挑檐面积）。 */
    public static BigDecimal quantityCoefficient(BudgetInputs.Resolved inputs, String itemCode,
                                                 String optionCode, String optionQuantitySource,
                                                 String resolvedQuantitySource) {
        if (inputs == null || inputs.profile() != BudgetInputs.FormulaProfile.EXCEL_V1
                || !coefficientsEnabled()) {
            return BigDecimal.ONE;
        }
        if ("STRUCTURE".equals(itemCode) && "FRAME_SHEAR".equals(optionCode)) {
            return new BigDecimal("1.1");
        }
        if ("WALL_PAINT".equals(itemCode)) {
            // BUILDING_AREA（真石漆/黑线）和派生 WALL_PAINT_AREA（深槽）都只乘一次面数；
            // 用户明确填写的等效面积不再乘。
            boolean apply = "BUILDING_AREA".equals(optionQuantitySource)
                    || ("PROJECT_QUANTITY".equals(optionQuantitySource) && "DERIVED".equals(resolvedQuantitySource));
            if (apply) {
                Integer faces = intValue(inputs.values().get("wallFaces"));
                return BudgetInputs.wallFaceCoefficient(faces == null ? 4 : faces);
            }
        }
        return BigDecimal.ONE;
    }

    /**
     * 地基基础高度附加：超过阈值后每 30cm 加价，不足 30cm 按 30cm。
     * STRIP 100 元/㎡、INDEPENDENT 120、RAFT 150、PILE 120；单位换算为分。
     */
    public static long foundationSurchargeCents(String optionCode, BigDecimal height) {
        if (height == null || optionCode == null) return 0L;
        Rule rule = switch (optionCode) {
            case "STRIP" -> new Rule(new BigDecimal("1.5"), 100L);
            case "INDEPENDENT" -> new Rule(new BigDecimal("2.5"), 120L);
            case "RAFT" -> new Rule(new BigDecimal("1.8"), 150L);
            case "PILE" -> new Rule(new BigDecimal("8.0"), 120L);
            default -> null;
        };
        if (rule == null) return 0L;
        BigDecimal excess = height.subtract(rule.threshold());
        if (excess.signum() <= 0) return 0L;
        long steps = excess.divide(STEP, 0, RoundingMode.CEILING).longValueExact();
        return Math.multiplyExact(steps, rule.yuanPerStep() * 100L);
    }

    /** Effective unit price on the frozen line: base price + foundation height surcharge. */
    public static long effectiveUnitPriceCents(BudgetInputs.Resolved inputs, String itemCode,
                                               String optionCode, long basePriceCents) {
        if (inputs == null || inputs.profile() != BudgetInputs.FormulaProfile.EXCEL_V1
                || !coefficientsEnabled() || !"FOUNDATION".equals(itemCode)) {
            return basePriceCents;
        }
        BigDecimal height = decimal(inputs.values().get("foundationHeight"));
        long surcharge = foundationSurchargeCents(optionCode, height);
        return Math.addExact(basePriceCents, surcharge);
    }

    /** Quantity after the workbook coefficient, rounded to the same 4-decimal scale as the calculator. */
    public static String scaleQuantity(String quantity, BigDecimal coefficient) {
        if (quantity == null) return null;
        BigDecimal factor = coefficient == null ? BigDecimal.ONE : coefficient;
        if (factor.compareTo(BigDecimal.ONE) == 0) return quantity;
        return new BigDecimal(quantity).multiply(factor)
                .setScale(BudgetInputs.QUANTITY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private record Rule(BigDecimal threshold, long yuanPerStep) { }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }
}
