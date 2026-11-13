package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.ServiceAccountInactiveException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 Fake 单测（无 Spring，14 文档 AC11）：编排层服务账号换译、Port 委托、审计参数（仅两写操作）。
 */
class AssetAccessServiceTest {

    private static final PostSnapshot POST =
            new PostSnapshot(11L, 22L, 33L, "store", "finance", null, false, Set.of());

    /** Fake Port：记录调用参数；assetTypeOf 可配置（类型限制路径测试） */
    private record RecordingPort(long expectedAccount, List<String> calls,
                                 String assetTypeToReturn) implements AssetAccessPort {

        RecordingPort(long expectedAccount, List<String> calls) {
            this(expectedAccount, calls, "FIN_VOUCHER");
        }

        @Override
        public DesignServicePort.UploadTicketView createUploadTicket(long serviceAccountId, String assetType,
                                                                     String mimeType, long sizeBytes, String sha256) {
            calls.add("upload:" + serviceAccountId + ":" + assetType);
            assertThat(serviceAccountId).as("编排须以服务账号身份调 Port").isEqualTo(expectedAccount);
            return new DesignServicePort.UploadTicketView(501L, "local://stub");
        }

        @Override
        public DesignServicePort.AssetCompleted completeUpload(long serviceAccountId, long assetId) {
            calls.add("complete:" + serviceAccountId + ":" + assetId);
            return new DesignServicePort.AssetCompleted(assetId, "ACCEPTED", null);
        }

        @Override
        public DesignServicePort.DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId) {
            calls.add("download:" + serviceAccountId + ":" + assetId);
            return new DesignServicePort.DownloadUrlView("local://stub?expires=1", 1L);
        }

        @Override
        public boolean validateReference(long serviceAccountId, long assetId) {
            calls.add("validate:" + serviceAccountId + ":" + assetId);
            return true;
        }

        @Override
        public String assetTypeOf(long serviceAccountId, long assetId) {
            calls.add("typeOf:" + serviceAccountId + ":" + assetId);
            return assetTypeToReturn;
        }
    }

    /** Recording CommandLog（动态代理）：拦截 append 捕获审计事件。append 须回传事件，供 FranchiseCommands 做聚合一致性检查。 */
    private CommandLog recordingCommandLog(List<String> aggregates) {
        return (CommandLog) java.lang.reflect.Proxy.newProxyInstance(
                CommandLog.class.getClassLoader(), new Class[]{CommandLog.class},
                (proxy, method, args) -> {
                    if ("append".equals(method.getName()) && args != null && args[0] != null) {
                        aggregates.add(((cn.iocoder.yudao.module.platform.command.dal.CommandEventDO) args[0])
                                .getAggregateType());
                        return args[0];
                    }
                    return null;
                });
    }

    private AssetAccessService service(AssetAccessPort port, FranchiseCommands commands) {
        StoreServiceAccountPort accounts = orgId -> {
            if (orgId == 999L) throw new ServiceAccountInactiveException(999L);
            return 777L;
        };
        CurrentPost currentPost = () -> java.util.Optional.of(POST);
        return new AssetAccessService(port, accounts, commands, currentPost);
    }

    @Test
    void uploadTicketDelegatesWithServiceAccountAndAudits() {
        List<String> calls = new ArrayList<>();
        List<String> aggregates = new ArrayList<>();
        AssetAccessService service = service(new RecordingPort(777L, calls), new FranchiseCommands(recordingCommandLog(aggregates)));

        DesignServicePort.UploadTicketView view = service.uploadTicket(POST, "FIN_VOUCHER",
                "application/pdf", 100, "a".repeat(64));

        assertThat(view.assetId()).isEqualTo(501L);
        assertThat(calls).containsExactly("upload:777:FIN_VOUCHER");
        assertThat(aggregates).containsExactly("fr-asset-upload-ticket"); // 写操作审计
    }

    @Test
    void completeDelegatesAndAuditsDownloadDoesNot() {
        List<String> calls = new ArrayList<>();
        List<String> aggregates = new ArrayList<>();
        AssetAccessService service = service(new RecordingPort(777L, calls), new FranchiseCommands(recordingCommandLog(aggregates)));

        var completed = service.completeUpload(POST, 501L);
        var download = service.createDownloadUrl(POST, 501L);

        assertThat(completed.uploadStatus()).isEqualTo("ACCEPTED");
        assertThat(download.downloadUrl()).contains("local://");
        assertThat(calls).containsExactly(
                "typeOf:777:501", "typeOf:777:501", "complete:777:501",
                "typeOf:777:501", "typeOf:777:501", "download:777:501"); // 权限分派与 B_END 白名单各查一次类型
        assertThat(aggregates).containsExactly("fr-asset-complete"); // download 不记审计（14 文档 §四）
    }

    @Test
    void completeAuditCarriesAssetTypeFromTrustedRecord() {
        List<String> aggregates = new ArrayList<>();
        // 用 Port 返回的类型入审计（B_END 白名单校验与审计同源，Codex 补审 P2）
        List<cn.iocoder.yudao.module.platform.command.dal.CommandEventDO> events = new ArrayList<>();
        CommandLog log = (CommandLog) java.lang.reflect.Proxy.newProxyInstance(
                CommandLog.class.getClassLoader(), new Class[]{CommandLog.class},
                (proxy, method, args) -> {
                    if ("append".equals(method.getName()) && args != null && args[0] != null) {
                        events.add((cn.iocoder.yudao.module.platform.command.dal.CommandEventDO) args[0]);
                        return args[0];
                    }
                    return null;
                });
        AssetAccessService service = service(new RecordingPort(777L, new ArrayList<>()),
                new FranchiseCommands(log));

        service.completeUpload(POST, 501L);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPayload()).contains("FIN_VOUCHER"); // payload 含 assetType
    }

    @Test
    void nonBEndOrMissingAssetIsForbiddenOnCompleteAndDownload() {
        // 类型限制（Codex 补审 P1）：C_END 类型/不存在/非属主一律 RESOURCE_FORBIDDEN，不透传能力调用
        for (String type : new String[]{"USER_SKETCH", null}) {
            List<String> calls = new ArrayList<>();
            AssetAccessService service = service(new RecordingPort(777L, calls, type),
                    new FranchiseCommands(recordingCommandLog(new ArrayList<>())));
            assertThatThrownBy(() -> service.completeUpload(POST, 501L))
                    .isInstanceOfSatisfying(ServiceException.class,
                            e -> assertThat(e.getCode()).isEqualTo(1_099_000_002));
            assertThatThrownBy(() -> service.createDownloadUrl(POST, 501L))
                    .isInstanceOfSatisfying(ServiceException.class,
                            e -> assertThat(e.getCode()).isEqualTo(1_099_000_002));
            // 未知类型先过权限分派（放行）再被 B_END 白名单拒绝；两次 typeOf，不透传 complete/download
            assertThat(calls).containsExactly("typeOf:777:501", "typeOf:777:501",
                    "typeOf:777:501", "typeOf:777:501");
        }
    }

    @Test
    void channelAllowedMatchesConsumptionMatrixAndLetsUnknownTypesThrough() {
        assertThat(AssetAccessService.channelAllowed("FIN_VOUCHER", "finance")).isTrue();
        assertThat(AssetAccessService.channelAllowed("FIN_VOUCHER", "finance-lead")).isTrue();
        assertThat(AssetAccessService.channelAllowed("FIN_VOUCHER", "boss")).isTrue();
        assertThat(AssetAccessService.channelAllowed("FIN_VOUCHER", "sales")).isFalse();
        assertThat(AssetAccessService.channelAllowed("FIN_VOUCHER", "hq-design-admin")).isFalse();
        assertThat(AssetAccessService.channelAllowed("FIN_VOUCHER", null)).isFalse();
        assertThat(AssetAccessService.channelAllowed("ENG_DRAWING", "engineer")).isTrue();
        assertThat(AssetAccessService.channelAllowed("ENG_DRAWING", "project-manager")).isTrue();
        assertThat(AssetAccessService.channelAllowed("ENG_DRAWING", "owner")).isTrue();
        assertThat(AssetAccessService.channelAllowed("ENG_SITE_PHOTO", "sales")).isFalse();
        assertThat(AssetAccessService.channelAllowed("CONTRACT_ATTACHMENT", "sales")).isTrue();
        assertThat(AssetAccessService.channelAllowed("CONTRACT_ATTACHMENT", "sales-lead")).isTrue();
        assertThat(AssetAccessService.channelAllowed("CONTENT_ASSET", "sales")).isTrue();
        assertThat(AssetAccessService.channelAllowed("CONTENT_ASSET", "finance")).isFalse();
        // 设计稿等未知类型保持放行，dev 字节通道不误伤 design:use 三岗
        assertThat(AssetAccessService.channelAllowed("USER_SKETCH", "sales")).isTrue();
        assertThat(AssetAccessService.channelAllowed(null, "sales")).isTrue();
    }

    @Test
    void crossChannelUploadIsForbiddenBeforePortCall() {
        List<String> calls = new ArrayList<>();
        AssetAccessService service = service(new RecordingPort(777L, calls),
                new FranchiseCommands(recordingCommandLog(new ArrayList<>())));
        PostSnapshot sales = new PostSnapshot(11L, 22L, 33L, "store", "sales", null, false, Set.of());

        assertThatThrownBy(() -> service.uploadTicket(sales, "FIN_VOUCHER",
                "application/pdf", 100, "a".repeat(64)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_099_000_002));
        assertThat(calls).isEmpty();
    }

    @Test
    void inactiveServiceAccountTranslatesToDesignErrorCode() {
        AssetAccessService service = service(new RecordingPort(777L, new ArrayList<>()),
                new FranchiseCommands(recordingCommandLog(new ArrayList<>())));

        PostSnapshot stopped = new PostSnapshot(11L, 22L, 999L, "store", "finance", null, false, Set.of());
        assertThatThrownBy(() -> service.uploadTicket(stopped, "FIN_VOUCHER", "application/pdf", 100, "a".repeat(64)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FranchiseDesignErrorCodes.SERVICE_ACCOUNT_INACTIVE.getCode()));
    }

}
