package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class FranchiseMapService {
    public static final String READ_ALL = "franchise:store-map:read-all";
    private final CurrentPost current;
    private final SystemDirectory directory;
    private final JdbcTemplate jdbc;

    public FranchiseMapService(CurrentPost current, SystemDirectory directory, JdbcTemplate jdbc) {
        this.current = current;
        this.directory = directory;
        this.jdbc = jdbc;
    }

    public record Store(String id, String name, String brandId, String brandName, String brandColor,
                        String provinceCode, String provinceName, String cityCode, String cityName,
                        String districtCode, String districtName, Double longitude, Double latitude, String locationPrecision) {}
    private static final List<String> COLORS = List.of("#965528","#197262","#4467A0","#934D78","#786322","#5351A2","#AE4138");

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, readOnly = true)
    public Map<String,Object> snapshot() {
        var post = current.require();
        long tenant = TenantContextHolder.getRequiredTenantId();
        boolean all = "headquarters".equals(post.orgType()) && directory.roleHasPermission(post.roleCode(),READ_ALL);
        String brand = "", name = "";
        if (!all) {
            if (!"store".equals(post.orgType())) throw new ServiceException(403,"当前工作身份无权查看门店地图");
            var owners = jdbc.queryForList("""
                    SELECT DISTINCT b.id,b.name FROM pf_store_location l JOIN pf_map_brand b
                      ON b.id=l.brand_id AND b.tenant_id=l.tenant_id WHERE l.tenant_id=? AND l.org_id=?
                    """,tenant,post.orgId());
            if (owners.size()!=1) throw new ServiceException(403,"请先完善门店品牌资料");
            brand = owners.get(0).get("id").toString();
            name = (String)owners.get(0).get("name");
        }
        String sql = """
                SELECT DISTINCT ON(l.org_id) l.org_id,d.name,l.brand_id,b.name AS brand,
                  coalesce(r.province_code,a.province) AS pc,coalesce(r.province_name,a.province) AS pn,
                  coalesce(r.city_code,a.city) AS cc,coalesce(r.city_name,a.city) AS cn,
                  coalesce(r.code,a.area) AS dc,coalesce(r.district_name,a.area) AS dn,
                  coalesce(l.longitude,r.longitude) AS lng,coalesce(l.latitude,r.latitude) AS lat,
                  CASE WHEN l.longitude IS NOT NULL THEN 'store'
                       WHEN r.code=r.city_code AND right(r.code,2)='00' THEN 'city'
                       WHEN r.code IS NOT NULL THEN 'district' ELSE 'unknown' END AS precision
                FROM pf_store_location l JOIN pf_franchise_account a ON a.account_id=l.account_id
                JOIN pf_map_brand b ON b.id=l.brand_id AND b.tenant_id=l.tenant_id
                JOIN pf_org_unit u ON u.dept_id=l.org_id AND u.org_type='store' AND u.kind='ORG' AND u.status='ACTIVE'
                JOIN system_dept d ON d.id=l.org_id AND d.tenant_id=l.tenant_id AND d.status=0 AND d.deleted=0
                LEFT JOIN pf_map_region r ON r.code=l.region_code
                WHERE l.tenant_id=?
                """;
        Object[] args = all ? new Object[]{tenant} : new Object[]{tenant,Long.parseLong(brand)};
        var stores = jdbc.query(sql + (all ? "" : " AND l.brand_id=?") + " ORDER BY l.org_id,l.account_id", (rs,row) -> {
            long id = rs.getLong("brand_id");
            String color = id <= COLORS.size() ? COLORS.get((int)id-1)
                    : String.format("#%06x", (id * 2654435761L & 0x7f7f7f) | 0x303030);
            return new Store(rs.getString("org_id"),rs.getString("name"),Long.toString(id),rs.getString("brand"),color,
                    rs.getString("pc"),rs.getString("pn"),rs.getString("cc"),rs.getString("cn"),
                    rs.getString("dc"),rs.getString("dn"),rs.getObject("lng",Double.class),rs.getObject("lat",Double.class),rs.getString("precision"));
        },args);
        return Map.of("scope",all?"all":"brand","brandId",brand,"brandName",name,"stores",stores);
    }
}
