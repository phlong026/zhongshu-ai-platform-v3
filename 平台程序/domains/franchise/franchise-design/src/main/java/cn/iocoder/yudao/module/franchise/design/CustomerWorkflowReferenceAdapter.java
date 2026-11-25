package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowReferencePort;
import cn.iocoder.yudao.module.franchise.design.publiccase.PublicCaseAccessService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Only the anonymous published catalog can supply a workflow reference; no private/local case fallback. */
@Component
public class CustomerWorkflowReferenceAdapter implements CustomerWorkflowReferencePort {
    private final ObjectProvider<PublicCaseAccessService> catalog;
    public CustomerWorkflowReferenceAdapter(ObjectProvider<PublicCaseAccessService> catalog) { this.catalog = catalog; }

    @Override
    public Reference requirePublishedCase(PostSnapshot post, long caseId) {
        var service = catalog.getIfAvailable();
        if (service == null) throw new ServiceException(503, "公开灵感库暂不可用");
        Object detail = service.getCaseDetail(post, caseId).fields();
        if (!(detail instanceof Map<?, ?> fields) || !Long.toString(caseId).equals(String.valueOf(fields.get("caseId")))
                || !(fields.get("title") instanceof String title) || title.isBlank())
            throw new ServiceException(400, "参考户型未公开或已下架");
        return new Reference(Long.toString(caseId), title.trim());
    }
}
