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

    /** Fake Port：记录调用参数 */
    private record RecordingPort(long expectedAccount, List<String> calls) implements AssetAccessPort {

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
    }

    /** Recording CommandLog（动态代理）：拦截 append 捕获审计事件，其余方法返回 null */
    private CommandLog recordingCommandLog(List<String> aggregates) {
        return (CommandLog) java.lang.reflect.Proxy.newProxyInstance(
                CommandLog.class.getClassLoader(), new Class[]{CommandLog.class},
                (proxy, method, args) -> {
                    if ("append".equals(method.getName()) && args[0] != null) {
                        aggregates.add(((cn.iocoder.yudao.module.platform.command.dal.CommandEventDO) args[0])
                                .getAggregateType());
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
        assertThat(calls).containsExactly("complete:777:501", "download:777:501");
        assertThat(aggregates).containsExactly("fr-asset-complete"); // download 不记审计（14 文档 §四）
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
