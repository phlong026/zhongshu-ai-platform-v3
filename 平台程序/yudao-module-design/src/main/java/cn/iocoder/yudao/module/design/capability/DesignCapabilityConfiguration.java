package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.FranchiseDesignAdapter;
import cn.iocoder.yudao.module.design.ai.NotePolishService;
import cn.iocoder.yudao.module.design.asset.*;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.design.capability.security.CapabilityProviderReadiness;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "zhongshu.design.capability.enabled", havingValue = "true")
public class DesignCapabilityConfiguration {
    @Bean
    CapabilityServiceAccountGuard capabilityServiceAccountGuard(DataSource source) {
        return new CapabilityServiceAccountGuard(source);
    }

    @Bean("capabilityAssetService")
    AssetService capabilityAssetService(DataSource source, PlatformTransactionManager transactions, ObjectStoragePort storage,
                                        AssetContentScanner scanner, ContentModerationPort moderation, DeliveryPort delivery,
                                        RightsGrantService rights, CapabilityServiceAccountGuard guard) {
        return new AssetService(source, transactions, storage, scanner, moderation, delivery, rights, guard);
    }

    @Bean("capabilityDesignProjectService")
    DesignProjectService capabilityDesignProjectService(DataSource source, PlatformTransactionManager transactions,
                                                        AiJobPort jobs, @Qualifier("capabilityAssetService") AssetService assets,
                                                        RightsGrantService rights, QuarantineObjectPort quarantine,
                                                        CapabilityServiceAccountGuard guard) {
        return new DesignProjectService(source, transactions, jobs, assets, rights, quarantine, guard);
    }

    @Bean("capabilityBudgetService")
    BudgetService capabilityBudgetService(DataSource source, @Qualifier("capabilityDesignProjectService") DesignProjectService projects,
                                          PlatformTransactionManager transactions, UsagePricingPort usage) {
        return new BudgetService(source, projects, transactions, usage);
    }

    @Bean("capabilityDesignOperations")
    FranchiseDesignAdapter capabilityDesignOperations(@Qualifier("capabilityDesignProjectService") DesignProjectService projects,
                                                      CaseCatalogService catalog, @Qualifier("capabilityAssetService") AssetService assets,
                                                      @Qualifier("capabilityBudgetService") BudgetService budgets,
                                                      AiJobPort jobs, NotePolishService polish, PricingPort prices,
                                                      ObjectProvider<UsagePricingPort> usage, PlatformTransactionManager transactions,
                                                      DataSource source) {
        return new FranchiseDesignAdapter(projects, catalog, assets, budgets, jobs, polish, prices, usage, transactions, source);
    }

    @Bean
    CapabilityReceiptStore capabilityReceiptStore(DataSource source, PlatformTransactionManager transactions, ObjectMapper mapper) {
        return new CapabilityReceiptStore(source, transactions, mapper);
    }

    @Bean
    DesignCapabilityProvider designCapabilityProvider(@Qualifier("capabilityDesignOperations") FranchiseDesignAdapter operations,
                                                      CapabilityOrganizationMapping organizations, CapabilityReceiptStore receipts,
                                                      ServiceDesignBillingPort billing, DataSource source,
                                                      @Qualifier("capabilityAssetService") AssetService assets) {
        return new DesignCapabilityProvider(operations, organizations, receipts, billing, source, assets);
    }

    @Bean
    CapabilityProviderReadiness capabilityProviderReadiness(ServiceDesignBillingPort billing, DataSource source) {
        return () -> {
            billing.requireReady();
            var jdbc = new JdbcTemplate(source);
            jdbc.queryForObject("SELECT count(*) FROM design_capability_receipt WHERE false", Long.class);
            Long protectedConsumerIds = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='account'::regclass "
                    + "AND conname='ck_account_consumer_namespace' AND convalidated=true", Long.class);
            if (protectedConsumerIds == null || protectedConsumerIds != 1) {
                throw new IllegalStateException("Validated consumer/service account ID isolation is required");
            }
            Long collisions = jdbc.queryForObject("SELECT count(*) FROM design_capability_organization o JOIN account a ON a.id=o.service_account_id", Long.class);
            if (collisions == null || collisions != 0) throw new IllegalStateException("Service resource IDs overlap consumer accounts");
        };
    }
}
