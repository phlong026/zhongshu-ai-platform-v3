package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class HeadquartersExportService {
    public static final int MAX_ROWS=50_000;
    private static final Map<String,List<String>> COLUMNS=Map.of(
        "customers",List.of("brandName","companyName","id","name","phone","status","ownerName","nextVisitDate","createTime"),
        "projects",List.of("brandName","companyName","id","name","customerName","phone","status","managerName","contractTotalCents","actualStartAt"),
        "receipts",List.of("brandName","companyName","id","name","amountCents","status","date","counterparty","accountName","transactionNo"),
        "payables",List.of("brandName","companyName","id","name","amountCents","status","date","dueDate","counterparty"),
        "payments",List.of("brandName","companyName","id","name","amountCents","status","date","counterparty","fundingType","accountName","transactionNo"),
        "refunds",List.of("brandName","companyName","id","name","amountCents","status","date","refundDirection","fundingType","transactionNo","replacesId","replacedById")
    );
    private static final Map<String,String> HEADERS=Map.ofEntries(
        Map.entry("brandName","品牌"),Map.entry("companyName","公司"),Map.entry("id","编号"),Map.entry("name","名称"),
        Map.entry("phone","联系电话"),Map.entry("status","状态"),Map.entry("ownerName","跟进人员"),Map.entry("managerName","工程负责人"),
        Map.entry("nextVisitDate","下次回访"),Map.entry("createTime","登记时间"),Map.entry("customerName","客户"),
        Map.entry("contractTotalCents","合同金额（元）"),Map.entry("actualStartAt","开工日期"),Map.entry("amountCents","金额（元）"),
        Map.entry("date","业务日期"),Map.entry("dueDate","到期日期"),Map.entry("counterparty","往来方"),Map.entry("accountName","账户"),
        Map.entry("transactionNo","流水号"),Map.entry("fundingType","资金来源"),Map.entry("refundDirection","退款方向"),
        Map.entry("replacesId","更正原单"),Map.entry("replacedById","后续更正单")
    );
    private final HeadquartersReadGuard guard;
    private final HeadquartersReadService reads;
    private final OperateLogCommonApi audit;
    public HeadquartersExportService(HeadquartersReadGuard guard,HeadquartersReadService reads,OperateLogCommonApi audit) {
        this.guard=guard;this.reads=reads;this.audit=audit;
    }

    /** Business rows remain read-only. Export audit is required before any bytes are released. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public byte[] export(String dataset,HeadquartersReadModel.Filter filter) {
        var actor=guard.require();
        var columns=COLUMNS.get(dataset);if(columns==null)throw new ServiceException(400,"不支持导出该业务类别");
        var query=reads.query(actor,dataset,filter);long count=reads.count(query);
        if(count>MAX_ROWS)throw new ServiceException(400,"导出最多50000行，请缩小品牌、公司或日期范围");
        var rows=reads.rows(query,MAX_ROWS,0);
        var companies=rows.stream().map(row->Long.parseLong(row.get("companyId").toString())).collect(Collectors.toSet());
        guard.requireCompanies(actor,companies);
        var csv=new StringBuilder("\uFEFF");append(csv,columns.stream().map(HEADERS::get).toList());
        for(var row:rows) {
            var values=new ArrayList<Object>();
            for(String column:columns) {
                Object value=row.get(column);
                if(column.endsWith("Cents")&&value instanceof Number) value=new BigDecimal(value.toString()).movePointLeft(2).toPlainString();
                values.add(value);
            }
            append(csv,values);
        }
        writeAudit(actor,dataset,filter,rows.size());
        guard.requireCompanies(actor,companies);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeAudit(HeadquartersReadGuard.Actor actor,String dataset,HeadquartersReadModel.Filter filter,int rows) {
        var detail=new LinkedHashMap<String,Object>();
        detail.put("tenantId",Long.toString(actor.tenantId()));detail.put("postId",Long.toString(actor.postId()));
        detail.put("dataset",dataset);detail.put("brandId",filter.brandId());detail.put("companyId",filter.companyId());
        detail.put("status",filter.status());detail.put("dateFrom",filter.dateFrom());detail.put("dateTo",filter.dateTo());
        detail.put("keywordSupplied",!filter.keyword().isBlank());detail.put("rows",rows);
        var request=new OperateLogCreateReqDTO();request.setUserId(actor.userId());request.setUserType(UserTypeEnum.ADMIN.getValue());
        request.setType("总部业务只读导出");request.setSubType(dataset);request.setBizId(actor.orgId());
        request.setAction("导出类别="+dataset+"；行数="+rows);request.setExtra(JsonUtils.toJsonString(detail));
        request.setRequestMethod("POST");request.setRequestUrl("/platform/headquarters/exports");
        String ip=ServletUtils.getClientIP(),agent=ServletUtils.getUserAgent();
        request.setUserIp(ip==null||ip.isBlank()?"unknown":ip);request.setUserAgent(agent==null||agent.isBlank()?"headquarters-export":agent);
        audit.createOperateLog(request);
    }

    static void append(StringBuilder out,List<?> cells) {
        for(int i=0;i<cells.size();i++) {
            if(i>0)out.append(',');String cell=Objects.toString(cells.get(i),"");
            String stripped=cell.stripLeading();
            if(!stripped.isEmpty()&&"=+-@\t\r".indexOf(stripped.charAt(0))>=0)cell="'"+cell;
            out.append('"').append(cell.replace("\"","\"\"")).append('"');
        }
        out.append("\r\n");
    }
}
