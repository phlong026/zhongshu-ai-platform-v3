package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LegacyAssetAccessAdapterTest {

    @Test
    void fiveParameterUploadKeepsBusinessChannelAndConvertsDto() {
        var design = mock(FranchiseDesignAdapter.class);
        when(design.createUploadTicket(7, "CONTRACT", "application/pdf", 123, "digest"))
                .thenReturn(new DesignServicePort.UploadTicketView(99, "local://business"));
        var bridge = new LegacyAssetAccessAdapter(design, mock(StoreServiceAccountPort.class));
        assertThat(bridge.createUploadTicket(7, "CONTRACT", "application/pdf", 123, "digest"))
                .isEqualTo(new AssetAccessPort.UploadTicketView(99, "local://business"));
        verify(design).createUploadTicket(7, "CONTRACT", "application/pdf", 123, "digest");
        verifyNoMoreInteractions(design);
    }

    @Test
    void completionDownloadAndReferencePreserveLegacyValues() {
        var design = mock(FranchiseDesignAdapter.class);
        when(design.completeUpload(7, 99))
                .thenReturn(new DesignServicePort.AssetCompleted(99, "REJECTED", "审核未通过"));
        when(design.createDownloadUrl(7, 99))
                .thenReturn(new DesignServicePort.DownloadUrlView("local://download", null));
        when(design.assetTypeOf(7, 99)).thenReturn("CONTRACT");
        when(design.validateReference(7, 99)).thenReturn(false);
        var bridge = new LegacyAssetAccessAdapter(design, mock(StoreServiceAccountPort.class));
        assertThat(bridge.completeUpload(7, 99))
                .isEqualTo(new AssetAccessPort.AssetCompleted(99, "REJECTED", "审核未通过"));
        assertThat(bridge.createDownloadUrl(7, 99))
                .isEqualTo(new AssetAccessPort.DownloadUrlView("local://download", null));
        assertThat(bridge.assetTypeOf(7, 99)).isEqualTo("CONTRACT");
        assertThat(bridge.validateReference(7, 99)).isFalse();
        verify(design).completeUpload(7, 99);
        verify(design).createDownloadUrl(7, 99);
        verify(design).assetTypeOf(7, 99);
        verify(design).validateReference(7, 99);
        verifyNoMoreInteractions(design);
    }

    @Test
    void delegateErrorsAreNotConvertedToSuccess() {
        var design = mock(FranchiseDesignAdapter.class);
        var error = new IllegalArgumentException("invalid business asset");
        when(design.completeUpload(7, 99)).thenThrow(error);
        var bridge = new LegacyAssetAccessAdapter(design, mock(StoreServiceAccountPort.class));
        assertThatThrownBy(() -> bridge.completeUpload(7, 99)).isSameAs(error);
    }

    @Test
    void taskDownloadResolvesLegacyAccountAndChecksAssetBeforeSigning() {
        var design = mock(FranchiseDesignAdapter.class);
        var accounts = mock(StoreServiceAccountPort.class);
        when(accounts.requireServiceAccount(23)).thenReturn(7L);
        when(design.assetTypeOf(7, 99)).thenReturn("CONTENT_MATERIAL");
        when(design.validateReference(7, 99)).thenReturn(true);
        when(design.createDownloadUrl(7, 99))
                .thenReturn(new DesignServicePort.DownloadUrlView("local://task", 123L));
        var bridge = new LegacyAssetAccessAdapter(design, accounts);
        assertThat(bridge.createTaskDownloadUrl(23, 99, "CONTENT_MATERIAL"))
                .isEqualTo(new AssetAccessPort.DownloadUrlView("local://task", 123L));
        var order = org.mockito.Mockito.inOrder(accounts, design);
        order.verify(accounts).requireServiceAccount(23);
        order.verify(design).assetTypeOf(7, 99);
        order.verify(design).validateReference(7, 99);
        order.verify(design).createDownloadUrl(7, 99);
        verifyNoMoreInteractions(accounts, design);
    }

    @Test
    void taskDownloadRejectsWrongTypeForeignAndUnacceptedAssets() {
        for (String type : new String[]{"CONTRACT", null, "CONTENT_MATERIAL"}) {
            var design = mock(FranchiseDesignAdapter.class);
            var accounts = mock(StoreServiceAccountPort.class);
            when(accounts.requireServiceAccount(23)).thenReturn(7L);
            when(design.assetTypeOf(7, 99)).thenReturn(type);
            when(design.validateReference(7, 99)).thenReturn(false);
            var bridge = new LegacyAssetAccessAdapter(design, accounts);
            assertThatThrownBy(() -> bridge.createTaskDownloadUrl(23, 99, "CONTENT_MATERIAL"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(accounts).requireServiceAccount(23);
            verify(design).assetTypeOf(7, 99);
            if ("CONTENT_MATERIAL".equals(type)) {
                verify(design).validateReference(7, 99);
            }
            verify(design, org.mockito.Mockito.never()).createDownloadUrl(7, 99);
        }
    }

    @Test
    void taskDownloadPropagatesInactiveServiceAccountWithoutReadingAssets() {
        var design = mock(FranchiseDesignAdapter.class);
        var accounts = mock(StoreServiceAccountPort.class);
        var inactive = new StoreServiceAccountPort.ServiceAccountInactiveException(23);
        when(accounts.requireServiceAccount(23)).thenThrow(inactive);
        var bridge = new LegacyAssetAccessAdapter(design, accounts);
        assertThatThrownBy(() -> bridge.createTaskDownloadUrl(23, 99, "CONTENT_MATERIAL"))
                .isSameAs(inactive);
        org.mockito.Mockito.verifyNoInteractions(design);
    }

    @Test
    void legacyBridgeIsEnabledByDefaultAndCanBeDisabled() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(DelegateConfiguration.class, LegacyAssetAccessAdapter.class);
        context.run(application -> assertThat(application).hasSingleBean(AssetAccessPort.class));
        context.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(application -> assertThat(application).hasSingleBean(AssetAccessPort.class));
        context.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .run(application -> assertThat(application).doesNotHaveBean(AssetAccessPort.class));
    }

    @Test
    void designAdapterOnlyImplementsDesignContractAndSharesBridgeSwitch() {
        assertThat(FranchiseDesignAdapter.class.getInterfaces()).containsExactly(DesignServicePort.class);
        var condition = FranchiseDesignAdapter.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("platform.legacy-design-bridge");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class DelegateConfiguration {
        @Bean
        FranchiseDesignAdapter designAdapter() {
            return mock(FranchiseDesignAdapter.class);
        }

        @Bean
        StoreServiceAccountPort serviceAccounts() {
            return mock(StoreServiceAccountPort.class);
        }
    }
}
