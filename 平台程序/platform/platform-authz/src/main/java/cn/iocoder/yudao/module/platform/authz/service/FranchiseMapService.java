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
                        String cityCode,String cityName,String districtCode,String districtName,Double longitude,Double latitude,String locationPrecision,String publicAddress) { }
    public record Brand(String id,String name,String color) { }
    public record MapRecord(String id,String name,String brandId,String brandName,String brandColor,String recordType,
                            String matchStatus,List<String> regionCodes,String provinceCode,String cityCode,String publicAddress,boolean wholeAreaCoverage) { }
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
        if(!"store".equals(post.orgType())&&!("headquarters".equals(post.orgType())
                &&List.of("hq-admin","finance","finance-lead").contains(post.roleCode())))throw new ServiceException(403,"当前工作身份无权查看加盟商地图");
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
            String publicName=publicName(item,catalog);
            // Never read StoreProfile.address: only a separately maintained, explicitly authorized
            // address on an actual franchise record may enter the same-brand public projection.
            String publicAddress="FRANCHISE".equals(item.recordType())&&item.publicAddressAuthorized()
                    ?item.publicAddress():"";
            records.add(new MapRecord(item.id(),publicName,item.brandId(),item.brandName(),item.brandColor(),item.recordType(),
                    item.matchStatus(),item.regionCodes(),item.provinceCode(),item.cityCode(),publicAddress,item.wholeAreaCoverage()));
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
                stores.add(new Store(item.id(),publicName,item.brandId(),item.brandName(),item.brandColor(),
                        item.provinceCode(),item.province(),item.cityCode(),item.city(),r==null?"":r.code(),r==null?"":r.name(),
                        r==null?null:r.longitude(),r==null?null:r.latitude(),r==null?"unknown":"district",publicAddress));
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion",2);result.put("scope",all?"all":"brand");
        result.put("publicProjectionVersion",1);
        result.put("brandId",brand);result.put("brandName",brandName);result.put("brands",brands);
        result.put("records",records);result.put("points",points);result.put("stores",stores);return result;
    }

    private static String publicName(FranchiseDirectoryService.Item item,Map<String,FranchiseRegionCatalog.Region> catalog) {
        if(item.publicName()!=null&&!item.publicName().isBlank())return item.publicName();
        LinkedHashSet<String> location=new LinkedHashSet<>();
        if(catalog.containsKey(item.provinceCode()))location.add(catalog.get(item.provinceCode()).name());
        if(catalog.containsKey(item.cityCode()))location.add(catalog.get(item.cityCode()).name());
        for(String code:item.regionCodes())if(catalog.containsKey(code))location.add(catalog.get(code).name());
        String region=location.isEmpty()?"地区待完善":String.join("·",location);
        return item.brandName()+" · "+region+("FRANCHISE".equals(item.recordType())?"门店":"覆盖记录");
    }
}
