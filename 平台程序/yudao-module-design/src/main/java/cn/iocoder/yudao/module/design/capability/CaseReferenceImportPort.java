package cn.iocoder.yudao.module.design.capability;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
public interface CaseReferenceImportPort {
    DesignFlowCall prepare(long owner,DesignFlowCall call);
    DesignFlowCall mapped(long owner,DesignFlowCall call);
    void refreshProject(long owner,long projectId);
    Object publicResult(long owner,Object result);
}
