package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工程全周期移交建档端口（阶段 2 / D-4）：franchise-commission HandoverService.receive 成功路径
 * **同事务**回调建档（先例=franchise-sales→BusinessReceiptPort 的 api 接口+实现模式）。
 * franchise-engineering 以 EngineeringPortImpl 实现；纯内部 Port，**无 HTTP 端点**。
 *
 * <p>幂等双层：commission 侧 packet-receive 命令幂等（重放不进回调）+ 本端口按
 * (company_id, source_commission_id) 部分唯一兜底——命中即刷新业务交底资料（蓝本 merge 语义：
 * 工程经办人、已签施工合同和现场记录由工程维护，资料刷新只带入交底），返回既有工程 id。
 */
public interface EngineeringPort {

    long receiveFromHandover(PostSnapshot post, HandoverProject command);

    /**
     * @param sourceRef        finance 应付幂等源（engineering-payable:{projectId}:{supplyId}，F9）
     * @param projectId        关联业务侧项目 id（finance payable.project_id）
     * @param amountCents      应付总额=单价×数量+运费（分）
     */
    record HandoverProject(long commissionId, Long customerId, String customerName, String city, String phone,
                           BigDecimal area, Integer floors, long managerUserId,
                           String drawingVersion, String drawingFile, List<HandoverDocument> documents,
                           String note, Long saleContractId, String frozenSchemeVersion) {
    }

    /** fileRef=JSON 引用（{name,url}），由 commission 移交包原样带入 */
    record HandoverDocument(String name, String fileRef, String kind, String version) {
    }
}
