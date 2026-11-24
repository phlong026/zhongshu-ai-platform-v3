package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class FranchiseMapService {
    public static final String READ_ALL="franchise:store-map:read-all";
    private static final List<String> COLORS=List.of("#965528","#197262","#4467A0","#934D78","#786322","#5351A2","#AE4138");
    private final CurrentPost current;
    private final JdbcTemplate jdbc;
    private final FranchiseDirectoryService directory;
    private final FranchiseRegionCatalog regions;
    public FranchiseMapService(CurrentPost current,JdbcTemplate jdbc,FranchiseDirectoryService directory,FranchiseRegionCatalog regions) {
        this.current=current;this.jdbc=jdbc;this.directory=directory;this.regions=regions;
    }
    public record Store(String id,String name,String brandId,String brandName,String brandColor,String provinceCode,String provinceName,
                        String cityCode,String cityName,String districtCode,String districtName,Double longitude,Double latitude,String locationPrecision) { }
    public record Brand(String id,String name,String color) { }
    public record MapRecord(String id,String name,String brandId,String brandName,String brandColor,String recordType,String originalRegionText,
                            String matchStatus,List<String> regionCodes,String provinceCode,String cityCode,String linkedOrgId) { }
    public record Point(String id,String recordId,String regionCode,double longitude,double latitude,String locationPrecision) { }

    public static String brandColor(long id) {
        return id>0&&id<=COLORS.size()?COLORS.get((int)id-1):String.format("#%06x",(id*2654435761L&0x7f7f7f)|0x303030);
    }
    public static String brandColor(long id,String name) {
        return switch(name==null?"":name.trim()) {
            case "别墅先生" -> "#C7564B";
            case "墅达人" -> "#537EB1";
            case "金宅子" -> "#35876E";
            case "合家美宅" -> "#9066AB";
            default -> brandColor(id);
        };
    }
    public List<FranchiseRegionCatalog.Region> regions(String parentCode) {
        requireReader();return regions.children(parentCode);
    }
    private void requireReader() {
        var post=current.require();
        if(!List.of("headquarters","store").contains(post.orgType()))throw new ServiceException(403,"当前工作身份无权查看加盟商地图");
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,readOnly=true)
    public Map<String,Object> snapshot() {
        requireReader();var post=current.require();long tenant=TenantContextHolder.getRequiredTenantId();
        boolean all="headquarters".equals(post.orgType());String brand="",brandName="";
        if(!all) {
            var owners=jdbc.queryForList("""
                SELECT DISTINCT b.id,b.name FROM pf_store_location l JOIN pf_map_brand b
                ON b.id=l.brand_id AND b.tenant_id=l.tenant_id WHERE l.tenant_id=? AND l.org_id=?
                """,tenant,post.orgId());
            if(owners.size()!=1)throw new ServiceException(403,"请先完善门店品牌资料");
            brand=owners.get(0).get("id").toString();brandName=(String)owners.get(0).get("name");
        }
        var items=directory.mapRecords(all?null:Long.parseLong(brand));
        Map<String,FranchiseRegionCatalog.Region> catalog=new HashMap<>();regions.all().forEach(r->catalog.put(r.code(),r));
        List<MapRecord> records=new ArrayList<>();List<Point> points=new ArrayList<>();List<Store> stores=new ArrayList<>();
        Object[] brandArgs=all?new Object[]{tenant}:new Object[]{tenant,Long.parseLong(brand)};
        var brands=jdbc.query("SELECT id,name FROM pf_map_brand WHERE tenant_id=?"+(all?"":" AND id=?")+" ORDER BY name,id",
                (r,i)->new Brand(r.getString("id"),r.getString("name"),brandColor(r.getLong("id"),r.getString("name"))),brandArgs);
        for(var item:items) {
            records.add(new MapRecord(item.id(),item.name(),item.brandId(),item.brandName(),item.brandColor(),item.recordType(),
                    item.originalRegionText(),item.matchStatus(),item.regionCodes(),item.provinceCode(),item.cityCode(),item.linkedOrgId()));
            FranchiseRegionCatalog.Region firstDistrict=null;
            for(String code:item.regionCodes()) {
                var region=catalog.get(code);
                if(region==null||!region.level().equals("district")||region.longitude()==null||region.latitude()==null)continue;
                points.add(new Point(item.id()+":"+code,item.id(),code,region.longitude(),region.latitude(),"district"));
                if(firstDistrict==null)firstDistrict=region;
            }
            // Older clients only understand stores: do not label coverage source rows as stores.
            if(item.recordType().equals("FRANCHISE")) {
                var r=firstDistrict;
                stores.add(new Store(item.linkedOrgId()==null?item.id():item.linkedOrgId(),item.name(),item.brandId(),item.brandName(),item.brandColor(),
                        item.provinceCode(),item.province(),item.cityCode(),item.city(),r==null?"":r.code(),r==null?"":r.name(),
                        r==null?null:r.longitude(),r==null?null:r.latitude(),r==null?"unknown":"district"));
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion",2);result.put("scope",all?"all":"brand");
        result.put("brandId",brand);result.put("brandName",brandName);result.put("brands",brands);
        result.put("records",records);result.put("points",points);result.put("stores",stores);return result;
    }
}
