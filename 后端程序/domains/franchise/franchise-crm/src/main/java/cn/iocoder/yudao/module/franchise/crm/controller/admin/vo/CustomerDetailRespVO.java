package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "客户详情 Response VO")
@Data
public class CustomerDetailRespVO {

    private CustomerRespVO customer;
    private List<Lead> leads;
    private List<FollowUp> followUps;

    @Data
    public static class Lead {
        private Long id;
        private Long registrarUserId;
        private String linkKind;
        private String need;
        private String source;
        private OffsetDateTime createTime;
    }

    @Data
    public static class FollowUp {
        private Long id;
        private Long authorUserId;
        private String note;
        private LocalDate nextVisitDate;
        private OffsetDateTime createTime;
    }

}
