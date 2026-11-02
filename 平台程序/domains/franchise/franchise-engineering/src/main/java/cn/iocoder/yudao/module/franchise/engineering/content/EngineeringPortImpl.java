package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.api.EngineeringPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 移交建档 Port 实现（D-4）：commission HandoverService.receive 成功路径同事务回调。
 * 无 HTTP 端点（纯内部 Port）；幂等=EngProjectService.receiveFromHandover 部分唯一兜底+刷新交底。
 */
@Component
public class EngineeringPortImpl implements EngineeringPort {

    private final EngProjectService projects;

    public EngineeringPortImpl(EngProjectService projects) {
        this.projects = projects;
    }

    @Override
    public long receiveFromHandover(PostSnapshot post, HandoverProject command) {
        List<Map<String, String>> documentRows = new ArrayList<>();
        for (HandoverDocument doc : command.documents()) {
            documentRows.add(Map.of(
                    "name", doc.name() == null ? "" : doc.name(),
                    "fileRef", doc.fileRef() == null ? "" : doc.fileRef(),
                    "kind", doc.kind() == null ? "DOCUMENT" : doc.kind(),
                    "version", doc.version() == null ? "" : doc.version()));
        }
        return projects.receiveFromHandover(post, command.commissionId(), command.customerId(),
                command.customerName(), command.city(), command.phone(), command.area(), command.floors(),
                command.managerUserId(), command.drawingVersion(), command.drawingFile(), documentRows);
    }
}
