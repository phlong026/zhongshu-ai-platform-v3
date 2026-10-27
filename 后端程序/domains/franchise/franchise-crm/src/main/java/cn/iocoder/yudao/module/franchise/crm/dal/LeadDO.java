package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 客资：每次咨询一条 */
@TableName("fr_lead")
@TenantIgnore
@Data
@Accessors(chain = true)
public class LeadDO {

    public static final String LINK_NEW_CUSTOMER = "NEW_CUSTOMER";
    public static final String LINK_EXISTING_CUSTOMER = "EXISTING_CUSTOMER";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long teamId;
    private Long registrarUserId;
    private Long registrarPostId;
    private Long customerId;
    private String linkKind;
    private String name;
    private String phone;
    private String need;
    private String source;
    private OffsetDateTime createTime;

}
