package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.*;

/** 将B已审核图片导入隔离设计服务；B资产编号绝不直接跨库传递。 */
public final class RemoteReferenceReader {
    // 三张20MB图片同时保留用于全量授权；限制整个读取/同步过程，防并发耗尽堆。
    private final RemoteDesignAdmission admission = new RemoteDesignAdmission(4, 1);
    private final String caller;
    private final RemoteDesignGateway remote;
    private final ReferenceMirrorStore mirrors;
    private final ReferenceByteUploader uploader;

    public RemoteReferenceReader(
            String caller,
            RemoteDesignGateway remote,
            ReferenceMirrorStore mirrors,
            ReferenceByteUploader uploader) {
        this.caller = caller;
        this.remote = remote;
        this.mirrors = mirrors;
        this.uploader = uploader;
    }

    public DesignServicePort.DesignProjectCreate translate(
            PostSnapshot post,
            DesignServicePort.DesignProjectCreate command,
            AssetAccessPort assets,
            AssetAccessPort.Actor actor) {
        try (var permit = admission.enter(post.orgId())) {
            return translateAdmitted(post, command, assets, actor);
        } catch (RemoteDesignAdmission.BusyException busy) {
            throw new ServiceException(429, "参考图同步繁忙，请稍后重试");
        }
    }

    private DesignServicePort.DesignProjectCreate translateAdmitted(
            PostSnapshot post,
            DesignServicePort.DesignProjectCreate command,
            AssetAccessPort assets,
            AssetAccessPort.Actor actor) {
        List<Long> ids = referenceIds(command.requirementInputs());
        if (ids.isEmpty()) return command;
        if (!"ELEVATION".equals(command.line())
                || !Set.of("SELF_UPLOAD", "CASE_REFERENCE").contains(command.sourceType())
                || command.sketchAssetId() != null) throw failure("参考图仅支持自传立面项目");
        uploader.requireReady();
        // 全部图片先授权及校验，再开始任何远程操作；最多3张，与现有需求验证一致。
        var content = new ArrayList<AssetAccessPort.ReferenceContent>();
        for (long id : ids) content.add(assets.readDesignReference(actor, id));
        var mapped = new ArrayList<String>();
        for (int i = 0; i < ids.size(); i++)
            mapped.add(String.valueOf(mirror(post, ids.get(i), content.get(i))));
        var inputs = new LinkedHashMap<>(command.requirementInputs());
        inputs.put("referenceAssetIds", List.copyOf(mapped));
        return new DesignServicePort.DesignProjectCreate(
                command.sourceType(),
                command.refCaseId(),
                null,
                inputs,
                command.line(),
                command.idempotencyKey());
    }

    private long mirror(PostSnapshot post, long id, AssetAccessPort.ReferenceContent content) {
        long tenant = remote.tenant(post);
        var lease =
                mirrors.claim(
                        new ReferenceMirrorStore.Scope(
                                caller,
                                tenant,
                                post.orgId(),
                                post.userId(),
                                post.postId(),
                                id,
                                content.sha256()));
        if (lease.acceptedId() != null) return lease.acceptedId();
        try {
            var ticket = ticket(post, lease, content);
            if (lease.recovery()) {
                var completed = complete(post, lease, ticket.assetId());
                if (accepted(completed, ticket.assetId())) {
                    mirrors.accept(lease, ticket.assetId());
                    return ticket.assetId();
                }
                if (!"REJECTED".equals(completed.uploadStatus()))
                    throw failure("参考图校验中，请稍后使用原业务键重试");
                lease = mirrors.restart(lease);
                ticket = ticket(post, lease, content);
            }
            mirrors.renew(lease);
            uploader.put(ticket.uploadUrl(), content.bytes(), content.mimeType());
            mirrors.renew(lease);
            var completed = complete(post, lease, ticket.assetId());
            if (!accepted(completed, ticket.assetId())) throw failure("参考图尚未通过设计服务校验，请重试");
            mirrors.accept(lease, ticket.assetId());
            return ticket.assetId();
        } finally {
            mirrors.release(lease);
        }
    }

    private DesignServicePort.UploadTicketView ticket(
            PostSnapshot p, ReferenceMirrorStore.Lease l, AssetAccessPort.ReferenceContent c) {
        var ticket =
                remote.<DesignServicePort.UploadTicketView>call(
                        p,
                        Operation.CREATE_UPLOAD_TICKET,
                        "reference:" + l.attempt() + ":ticket",
                        new DesignServicePort.UploadTicketCreate(
                                "DESIGN_REFERENCE", c.mimeType(), c.bytes().length, c.sha256()));
        if (ticket == null || ticket.assetId() <= 0) throw failure("设计服务上传票据无效");
        return ticket;
    }

    private DesignServicePort.AssetCompleted complete(
            PostSnapshot p, ReferenceMirrorStore.Lease l, long id) {
        return remote.call(
                p,
                Operation.COMPLETE_UPLOAD,
                "reference:" + l.attempt() + ":complete",
                new Asset(id));
    }

    private boolean accepted(DesignServicePort.AssetCompleted c, long id) {
        if (c == null || c.assetId() != id) throw failure("设计服务素材校验响应不匹配");
        return "ACCEPTED".equals(c.uploadStatus());
    }

    public static List<Long> referenceIds(Map<String, Object> inputs) {
        if (inputs == null || !inputs.containsKey("referenceAssetIds")) return List.of();
        Object raw = inputs.get("referenceAssetIds");
        if (!(raw instanceof List<?> refs) || refs.size() > 3) throw failure("参考图必须是最多3张的编号列表");
        var ids = new ArrayList<Long>();
        for (Object ref : refs) {
            try {
                long id = Long.parseLong(String.valueOf(ref));
                if (id <= 0 || !String.valueOf(id).equals(String.valueOf(ref)))
                    throw new NumberFormatException();
                ids.add(id);
            } catch (NumberFormatException e) {
                throw failure("参考图编号无效");
            }
        }
        if (new HashSet<>(ids).size() != ids.size()) throw failure("参考图不能重复");
        return ids;
    }

    private static ServiceException failure(String message) {
        return new ServiceException(1_112_100_003, message);
    }
}
