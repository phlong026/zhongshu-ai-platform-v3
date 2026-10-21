package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseDimensionRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseSummaryRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCaseControllerBatchAuthorizationTest {

    private CaseCatalogService catalog;
    private AppCaseController controller;
    private CaseCatalogService.CursorPage page;

    @BeforeEach
    void setUp() {
        catalog = mock(CaseCatalogService.class);
        controller = new AppCaseController();
        ReflectionTestUtils.setField(controller, "caseCatalogService", catalog);
        IdentitySessionPort sessions = token -> Optional.of(
                new IdentitySessionPort.SessionContext(901L, "test", "openid", false));
        ReflectionTestUtils.setField(controller, "identitySessionPort", sessions);
        page = new CaseCatalogService.CursorPage(List.of(summary(11L), summary(12L)), null);
        when(catalog.findGenerationAllowedCaseIds(org.mockito.ArgumentMatchers.<Long>anyList()))
                .thenReturn(Set.of(11L));
    }

    @Test
    void publicListsResolveGenerationActionsWithOneBatchQueryPerResponse() {
        when(catalog.listPublishedCases(null, null, null, null, null,
                null, null, null, null, 20)).thenReturn(page);
        assertActions(controller.getCasePage(null, null, null, null, null,
                null, null, null, null, 20).getData().getList());
        verify(catalog).findGenerationAllowedCaseIds(List.of(11L, 12L));
        verify(catalog, never()).canUseForGeneration(org.mockito.ArgumentMatchers.anyLong());

        clearInvocations(catalog);
        var dimensionPage = new CaseCatalogService.DimensionPage(List.of(
                dimensionCase(11L, true), dimensionCase(12L, false)), null);
        when(catalog.listCasesByDimension(new BigDecimal("12"), new BigDecimal("10"),
                "BUILDING_OUTLINE", 50)).thenReturn(dimensionPage);
        assertDimensionActions(controller.getCasesByDimension(new BigDecimal("12"), new BigDecimal("10"),
                        "BUILDING_OUTLINE", 50)
                .getData().getList());
        verify(catalog, never()).findGenerationAllowedCaseIds(anyList());
        verify(catalog, never()).canUseForGeneration(org.mockito.ArgumentMatchers.anyLong());

        clearInvocations(catalog);
        when(catalog.listFavorites(901L, null, 20)).thenReturn(page);
        assertActions(controller.getFavorites(null, 20, "Bearer token").getData().getList());
        verify(catalog).findGenerationAllowedCaseIds(List.of(11L, 12L));
        verify(catalog, never()).canUseForGeneration(org.mockito.ArgumentMatchers.anyLong());
    }

    private static void assertActions(List<AppCaseSummaryRespVO> items) {
        assertThat(items.get(0).getAllowedActions()).containsExactly("FAVORITE", "DESIGN_WITH");
        assertThat(items.get(1).getAllowedActions()).containsExactly("FAVORITE");
    }

    private static void assertDimensionActions(List<AppCaseDimensionRespVO> items) {
        assertThat(items.get(0).getAllowedActions()).containsExactly("FAVORITE", "DESIGN_WITH");
        assertThat(items.get(1).getAllowedActions()).containsExactly("FAVORITE");
    }

    private static CaseCatalogService.CaseSummary summary(long id) {
        return new CaseCatalogService.CaseSummary(id, "案例 " + id, "COMPANY", "MODERN",
                2, 120, null, 1L, "PUBLISHED", null,
                null, null, false, new BigDecimal("12"), new BigDecimal("10"));
    }

    private static CaseCatalogService.DimensionCase dimensionCase(long id, boolean generationAllowed) {
        return new CaseCatalogService.DimensionCase(id, id + 100, "案例 " + id, "COMPANY",
                new BigDecimal("12"), new BigDecimal("10"), "BUILDING_OUTLINE",
                2, 120, null, "plan-" + id,
                List.of(new CaseCatalogService.FloorPlan("plan-" + id, 1)), generationAllowed);
    }
}
