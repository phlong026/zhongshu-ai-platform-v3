package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class HeadquartersReadModel {
    private HeadquartersReadModel() { }
    public record Filter(Long brandId, Long companyId, String keyword, String status,
                         LocalDate dateFrom, LocalDate dateTo, int pageNo, int pageSize) {
        public Filter {
            keyword = keyword == null ? "" : keyword.trim();
            status = status == null ? "" : status.trim();
            if (pageNo < 1 || pageNo > 1_000_000 || pageSize < 1 || pageSize > 100
                    || keyword.length() > 100 || status.length() > 40
                    || brandId != null && brandId <= 0 || companyId != null && companyId <= 0
                    || dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
                throw new ServiceException(400, "查询或分页参数无效");
            }
        }
        public static Filter all() { return new Filter(null,null,"","",null,null,1,20); }
    }
    public record Page(List<Map<String,Object>> list, long total, int pageNo, int pageSize, boolean readOnly) { }
    public record Context(String tenantId, String headquartersOrgId, String postId, String roleCode,
                          boolean readOnly, List<Map<String,Object>> brands, List<Map<String,Object>> companies) { }
}
