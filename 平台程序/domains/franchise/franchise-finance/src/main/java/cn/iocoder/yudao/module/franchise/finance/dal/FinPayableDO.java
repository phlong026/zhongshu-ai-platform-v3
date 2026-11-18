package cn.iocoder.yudao.module.franchise.finance.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 应付 */
@TableName("fr_fin_payable")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FinPayableDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long bookId;
    private String category;
    private LocalDate businessDate;
    private LocalDate dueDate;

    private Long projectId;
    private String stage;
    private Long totalCents;
    private String recipient;
    private String status;
    private String agreementRef;
    private String sourceType;
    private String sourceRef;
    private Long customerId;
    private Long designOrgId;
    private Long tenantId;
    private OffsetDateTime createTime;
}
