package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 客资：登记即建客户或关联原客户；原客户登记人无权看到时等负责人关联 */
@TableName("fr_lead")
@TenantIgnore
@Data
@Accessors(chain = true)
public class LeadDO {

    public static final String STATUS_LINK_REQUIRED = "LINK_REQUIRED";
    public static final String STATUS_CONVERTED = "CONVERTED";
    public static final String STATUS_INVALID = "INVALID";

    public static final String CONVERSION_DIRECT = "DIRECT";
    public static final String CONVERSION_EXISTING = "EXISTING";
    public static final String CONVERSION_LINKED = "LINKED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long teamId;
    private String name;
    private String phone;
    private String need;
    private String source;
    private String province;
    private String city;
    private String district;
    private String town;
    private String village;
    private String regionCode;
    private String addressDetail;
    private String status;
    private String conversion;
    private Long customerId;
    private Long registrarUserId;
    private Long registrarPostId;
    private String note;
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
