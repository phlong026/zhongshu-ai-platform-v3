package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.design.budget.BudgetFormula;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Excel 2026 workbook rules, isolated behind the EXCEL_V1 profile so historical budgets do not drift. */
class BudgetFormulaExcelV1Test {

    private static BudgetInputs.Resolved resolved(Map<String, Object> inputs) {
        return BudgetInputs.resolve(inputs, Map.of(), BudgetInputs.FormulaProfile.EXCEL_V1);
    }

    @Test
    void derivesNotchTerraceTopFloorPerimeterAndProfessionalQuantities() {
        var r = resolved(Map.of(
                "regionCode", "WUHAN",
                "faceWidth", "10", "depth", "12", "floorCount", 2,
                "notchArea", "10", "terraceArea", "20"));

        assertThat(r.values()).containsEntry("footprintArea", "110")
                .containsEntry("buildingArea", "200")
                .containsEntry("roofArea", "90")
                .containsEntry("buildingPerimeter", "44")
                .containsEntry("wallFaces", 4)
                .containsEntry("gutterWidth", "0.4");
        assertThat(r.missingFields()).isEmpty();
        assertThat(r.derivedQuantities())
                .containsEntry("WALL_PAINT_AREA", "200")
                .containsEntry("CULTURE_STONE_LENGTH", "44")
                .containsEntry("WATERPROOF_AREA", "37.6")
                .containsEntry("INSULATED_WATERPROOF_AREA", "90")
                .containsEntry("DOOR_HOUSEHOLDS", "1");
        assertThat(r.publicValues()).containsKeys("terraceArea", "notchArea", "wallFaces");
    }

    @Test
    void legacyProfileKeepsHistoricalDerivationExactly() {
        var r = BudgetInputs.resolve(Map.of(
                "regionCode", "WUHAN",
                "faceWidth", "10", "depth", "12", "floorCount", 2,
                "notchArea", "10", "terraceArea", "20"), Map.of());

        assertThat(r.profile()).isEqualTo(BudgetInputs.FormulaProfile.LEGACY);
        assertThat(r.values()).containsEntry("footprintArea", "120").containsEntry("buildingArea", "240");
        assertThat(r.values()).doesNotContainKeys("roofArea", "buildingPerimeter");
        assertThat(r.derivedQuantities()).isEmpty();
        assertThat(r.missingFields()).contains("roofArea");
    }

    @Test
    void explicitActualAreaWinsOverTerraceDerivationAndKeepsReviewWarning() {
        var r = resolved(Map.of(
                "regionCode", "WUHAN",
                "faceWidth", "10", "depth", "12", "floorCount", 2,
                "terraceArea", "20", "buildingArea", "210"));
        assertThat(r.values()).containsEntry("buildingArea", "210");
        assertThat(r.warnings()).contains("BUILDING_AREA_REVIEW_REQUIRED");
    }

    @Test
    void frameShearAndWallPaintApplyWorkbookCoefficients() {
        var r = resolved(Map.of(
                "regionCode", "WUHAN", "faceWidth", "10", "depth", "12",
                "floorCount", 2, "wallFaces", 4));

        assertThat(BudgetFormula.quantityCoefficient(r, "STRUCTURE", "FRAME_SHEAR", "BUILDING_AREA", "BUILDING_AREA"))
                .isEqualByComparingTo("1.1");
        assertThat(BudgetFormula.quantityCoefficient(r, "WALL_PAINT", "STONE_PAINT", "BUILDING_AREA", "PROJECT"))
                .isEqualByComparingTo("2.2");
        // 深槽的派生 WALL_PAINT_AREA 是基数，仍需乘一次面数。
        assertThat(BudgetFormula.quantityCoefficient(r, "WALL_PAINT", "SAND_GROOVE", "PROJECT_QUANTITY", "DERIVED"))
                .isEqualByComparingTo("2.2");
        // A user-measured equivalent area is authoritative; do not multiply by faces again.
        assertThat(BudgetFormula.quantityCoefficient(r, "WALL_PAINT", "SAND_GROOVE", "PROJECT_QUANTITY", "PROJECT"))
                .isEqualByComparingTo("1");
        assertThat(BudgetFormula.scaleQuantity("240", new BigDecimal("1.1"))).isEqualTo("264");
        assertThat(BudgetFormula.scaleQuantity("240", new BigDecimal("2.2"))).isEqualTo("528");
    }

    @Test
    void foundationHeightAddsPerStepSurchargeAndNeverChangesBasePriceForOtherItems() {
        assertThat(BudgetFormula.foundationSurchargeCents("STRIP", new BigDecimal("1.5"))).isZero();
        assertThat(BudgetFormula.foundationSurchargeCents("STRIP", new BigDecimal("1.51"))).isEqualTo(10_000L);
        assertThat(BudgetFormula.foundationSurchargeCents("STRIP", new BigDecimal("1.8"))).isEqualTo(10_000L);
        assertThat(BudgetFormula.foundationSurchargeCents("STRIP", new BigDecimal("2.0"))).isEqualTo(20_000L);
        assertThat(BudgetFormula.foundationSurchargeCents("RAFT", new BigDecimal("2.1"))).isEqualTo(15_000L);
        assertThat(BudgetFormula.foundationSurchargeCents("PILE", new BigDecimal("8.0"))).isZero();
        assertThat(BudgetFormula.foundationSurchargeCents("PILE", new BigDecimal("8.31"))).isEqualTo(24_000L);

        var r = resolved(Map.of("regionCode", "WUHAN", "foundationHeight", "1.8"));
        assertThat(BudgetFormula.effectiveUnitPriceCents(r, "FOUNDATION", "STRIP", 52_000L)).isEqualTo(62_000L);
        assertThat(BudgetFormula.effectiveUnitPriceCents(r, "STRUCTURE", "FRAME", 98_000L)).isEqualTo(98_000L);
    }

    @Test
    void wallFaceCoefficientIsDiscreteAndRejectsUnknownFaceCounts() {
        assertThat(BudgetInputs.wallFaceCoefficient(1)).isEqualByComparingTo("1.4");
        assertThat(BudgetInputs.wallFaceCoefficient(2)).isEqualByComparingTo("1.6");
        assertThat(BudgetInputs.wallFaceCoefficient(3)).isEqualByComparingTo("1.8");
        assertThat(BudgetInputs.wallFaceCoefficient(4)).isEqualByComparingTo("2.2");
    }
    @Test
    void addsEavesToRoofAndGutterPerimeter() {
        var r = resolved(Map.of(
                "regionCode", "WUHAN",
                "faceWidth", "10", "depth", "10", "floorCount", 1,
                "leftRightEaveMm", "500", "frontBackEaveMm", "500"));

        assertThat(r.values()).containsEntry("roofArea", "121");
        assertThat(r.derivedQuantities())
                .containsEntry("GUTTER_PERIMETER", "44")
                .containsEntry("WATERPROOF_AREA", "17.6");
    }

    @Test
    void exposesCustomerAndEaveFieldsOnlyAsPublicOptionalValues() {
        var r = resolved(Map.of(
                "regionCode", "WUHAN", "footprintArea", "120", "floorCount", 1,
                "ownerName", "张三", "ownerPhone", "13800000000",
                "leftRightEaveMm", "500", "frontBackEaveMm", "400",
                "voidLivingRoom", true, "staircaseType", "SPIRAL"));

        assertThat(r.values()).containsEntry("ownerName", "张三")
                .containsEntry("leftRightEaveMm", 500)
                .containsEntry("voidLivingRoom", true)
                .containsEntry("staircaseType", "SPIRAL");
        assertThat(r.publicValues()).containsKeys("ownerName", "ownerPhone", "leftRightEaveMm",
                "frontBackEaveMm", "voidLivingRoom", "staircaseType");
        assertThat(r.missingFields()).isEmpty();
    }

    @Test
    void rejectsInvalidEaveAndCustomerValues() {
        assertThatThrownBy(() -> resolved(Map.of("regionCode", "WUHAN", "footprintArea", "120",
                "floorCount", 1, "leftRightEaveMm", "5001")))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(
                                cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID.getCode()));
    }

}
