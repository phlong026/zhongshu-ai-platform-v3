package cn.iocoder.yudao.server;

import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.design.FranchiseDesignAdapter;
import cn.iocoder.yudao.module.design.ai.NotePolishService;
import cn.iocoder.yudao.module.design.asset.*;
import cn.iocoder.yudao.module.design.budget.BudgetDraftService;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.capability.*;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.identity.account.JdbcAccountStateAdapter;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Enabled bean graph with real consumer and service guards. Channel dependencies are fixtures, not live integrations. */
class CapabilityAccountGuardWiringTest {
    @Test void enabledProviderDoesNotReplaceOrAmbiguouslyWireConsumerAccountAuthorization() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("zhongshu.design.capability.enabled", true)));
            DataSource source = mock(DataSource.class);
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(ObjectStoragePort.class, () -> mock(ObjectStoragePort.class));
            context.registerBean(AssetContentScanner.class, AssetContentScanner::new);
            context.registerBean(ContentModerationPort.class, () -> mock(ContentModerationPort.class));
            context.registerBean(DeliveryPort.class, () -> mock(DeliveryPort.class));
            context.registerBean(RightsGrantService.class, () -> new RightsGrantService(source));
            context.registerBean(AiJobPort.class, () -> mock(AiJobPort.class));
            context.registerBean(QuarantineObjectPort.class, () -> mock(QuarantineObjectPort.class));
            context.registerBean(PricingPort.class, () -> mock(PricingPort.class));
            context.registerBean(UsagePricingPort.class, () -> mock(UsagePricingPort.class));
            context.registerBean(NotePolishService.class, () -> mock(NotePolishService.class));
            context.registerBean(ServiceDesignBillingPort.class, () -> mock(ServiceDesignBillingPort.class));
            context.register(JdbcAccountStateAdapter.class, AssetService.class, DesignProjectService.class,
                    BudgetService.class, CaseCatalogService.class, FranchiseDesignAdapter.class,
                    CapabilityOrganizationMapping.class, DesignCapabilityConfiguration.class,
                    BudgetDraftService.class, PointAccountService.class);
            context.refresh();
            var consumer = context.getBean(JdbcAccountStateAdapter.class);
            var service = context.getBean(CapabilityServiceAccountGuard.class);
            assertThat(context.getBean(AccountStatePort.class)).isSameAs(consumer);
            assertThat(ReflectionTestUtils.getField(context.getBean("assetService"), "accountStatePort")).isSameAs(consumer);
            assertThat(ReflectionTestUtils.getField(context.getBean("designProjectService"), "accountStatePort")).isSameAs(consumer);
            assertThat(ReflectionTestUtils.getField(context.getBean("capabilityAssetService"), "accountStatePort")).isSameAs(service);
            assertThat(ReflectionTestUtils.getField(context.getBean("capabilityDesignProjectService"), "accountStatePort")).isSameAs(service);
            assertThat(ReflectionTestUtils.getField(context.getBean("capabilityDesignProjectService"), "assetService"))
                    .isSameAs(context.getBean("capabilityAssetService"));
            assertThat(ReflectionTestUtils.getField(context.getBean("capabilityBudgetService"), "projectService"))
                    .isSameAs(context.getBean("capabilityDesignProjectService"));
            assertThat(ReflectionTestUtils.getField(context.getBean(BudgetDraftService.class), "accounts")).isSameAs(consumer);
            assertThat(ReflectionTestUtils.getField(context.getBean(PointAccountService.class), "accountStatePort")).isSameAs(consumer);
        }
    }
}
