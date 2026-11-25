package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** Only the published inspiration catalog may provide references for a customer's workflow. */
public interface CustomerWorkflowReferencePort {
    record Reference(String id, String name) { }
    Reference requirePublishedCase(PostSnapshot post, long caseId);
}
