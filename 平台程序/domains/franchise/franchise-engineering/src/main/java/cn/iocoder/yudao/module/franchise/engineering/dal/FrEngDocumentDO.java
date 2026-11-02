package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 图纸与文档（移交包带入或手工补录；name=签约资料|交底记录|…） */
@TableName("fr_eng_document")
@TenantIgnore
@Data
@Accessors(chain = true)
public class FrEngDocumentDO {

    public static final String KIND_DRAWING = "DRAWING";
    public static final String KIND_DOCUMENT = "DOCUMENT";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long projectId;
    private String name;
    /** 文件引用 JSON（移交包 {name,url} 或基-1 资产 {assetId}） */
    private String fileRef;
    private String kind;
    private String version;
    private OffsetDateTime createTime;

}
