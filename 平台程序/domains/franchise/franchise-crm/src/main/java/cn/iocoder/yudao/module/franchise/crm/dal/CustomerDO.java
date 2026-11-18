package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 客户：一个门店一个手机号一个客户；归经办业务（owner）所在团队 */
@TableName("fr_customer")
@TenantIgnore
@Data
@Accessors(chain = true)
public class CustomerDO {

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_FOLLOWING = "FOLLOWING";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long teamId;
    private Long ownerUserId;
    private String name;
    private String phone;
    private String wechat;
    private String source;
    private String province;
    private String city;
    private String district;
    private String town;
    private String village;
    private String regionCode;
    private String addressDetail;
    private String summary;
    private String status;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String designStatus;
    private Long originLeadId;
    private LocalDate nextVisitDate;
    private OffsetDateTime lastFollowAt;
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
