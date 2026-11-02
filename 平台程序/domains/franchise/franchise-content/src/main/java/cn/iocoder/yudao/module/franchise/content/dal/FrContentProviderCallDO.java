package cn.iocoder.yudao.module.franchise.content.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 外呼日志（16 §四/A2.5）：每次 provider 调用一行（provider/endpoint/耗时/结果码/计量单位数）——成本审计。 */
@TableName("fr_content_provider_call")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrContentProviderCallDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long taskId;
    private String provider;
    private String endpoint;
    private Integer latencyMs;
    private String resultCode;
    private Integer unitsBilled;
    private OffsetDateTime createTime;

}
