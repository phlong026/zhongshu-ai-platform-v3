package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** One imported source row remains one record; coverage is not counted as a franchise. */
@Service
public class FranchiseDirectoryService {
    public record Item(String id,String name,String brandId,String brandName,String brandColor,String recordType,
                       String originalRegionText,String matchStatus,List<String> regionCodes,String provinceCode,
                       String cityCode,String linkedOrgId,String sourceKey,Integer sourceRow,String sourceSheet,
                       String sourceCell,String brand,String province,String city,String area,String districtCode,long version) { }
    public record RecordRequest(String name,String brand,String recordType,String originalRegionText,List<String> regionCodes,
                                String provinceCode,String cityCode,Long expectedVersion) { }
    public record FromDirectoryRequest(String recordId,Long expectedVersion,String adminName,String adminMobile,
                                       String areaPinyin,String ownerPinyin,String remark,String districtCode) { }
    public record ImportRow(String sourceKey,int sourceRow,String sourceSheet,String sourceCell,String brand,String name,
                            String recordType,String originalRegionText,String provinceCode,String cityCode,List<String> regionCodes,
                            String matchStatus,String status,String message,String recordId) { }
    public record ImportResult(boolean dryRun,int total,int created,int updated,int skipped,int failed,int unresolved,List<ImportRow> rows) { }
    private record Prepared(FranchiseDirectoryWorkbook.Row row,FranchiseRegionCatalog.Resolution resolution,
                            List<FranchiseRegionCatalog.Region> catalog) { }
    private final JdbcTemplate jdbc;
    private final FranchiseRegionCatalog regions;
    private final FranchiseOnboardService onboard;
    private final TransactionTemplate transactions;
    public FranchiseDirectoryService(JdbcTemplate jdbc,FranchiseRegionCatalog regions,FranchiseOnboardService onboard,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.regions=regions;this.onboard=onboard;this.transactions=new TransactionTemplate(manager);
    }

    private static final String SELECT="""
        SELECT d.*,b.name AS brand_name,
          ARRAY(SELECT region_code FROM pf_franchise_directory_region r WHERE r.tenant_id=d.tenant_id AND r.record_id=d.id ORDER BY region_code) AS regions
        FROM pf_franchise_directory d JOIN pf_map_brand b ON b.id=d.brand_id AND b.tenant_id=d.tenant_id
        WHERE d.tenant_id=?
        """;
    private List<Item> query(String suffix,Object... extra) {
        return query(regions.all(),suffix,extra);
    }
    private List<Item> query(List<FranchiseRegionCatalog.Region> knownRegions,String suffix,Object... extra) {
        List<Object> args=new ArrayList<>();args.add(tenant());args.addAll(Arrays.asList(extra));
        Map<String,FranchiseRegionCatalog.Region> catalog=new HashMap<>();knownRegions.forEach(r->catalog.put(r.code(),r));
        return jdbc.query(SELECT+suffix,(r,i)->item(r,catalog),args.toArray());
    }
    private Item item(ResultSet r,Map<String,FranchiseRegionCatalog.Region> catalog) throws SQLException {
        String[] codes=(String[])r.getArray("regions").getArray();String pc=text(r.getString("province_code")),cc=text(r.getString("city_code"));
        var districts=Arrays.stream(codes).map(catalog::get).filter(Objects::nonNull).filter(x->x.level().equals("district")).toList();
        var district=districts.size()==1?districts.get(0):null;
        String province=catalog.containsKey(pc)?catalog.get(pc).name():"";
        String city=catalog.containsKey(cc)?catalog.get(cc).name():district==null?"":district.cityName();
        long brandId=r.getLong("brand_id");
        return new Item(r.getString("id"),r.getString("name"),Long.toString(brandId),r.getString("brand_name"),
            FranchiseMapService.brandColor(brandId,r.getString("brand_name")),r.getString("record_type"),r.getString("original_region_text"),
            r.getString("match_status"),List.of(codes),pc,cc,r.getString("org_id"),r.getString("source_key"),
            r.getObject("source_row",Integer.class),text(r.getString("source_sheet")),text(r.getString("source_cell")),
            r.getString("brand_name"),province,city,district==null?r.getString("original_region_text"):district.name(),
            district==null?"":district.code(),r.getLong("version"));
    }
    public Item get(String id) {
        var rows=query(" AND d.id=?",id);if(rows.isEmpty())throw new ServiceException(404,"加盟商名录记录不存在");return rows.get(0);
    }
    private Item bySource(String source,List<FranchiseRegionCatalog.Region> catalog) {
        var rows=query(catalog," AND d.source_key=?",source);return rows.isEmpty()?null:rows.get(0);
    }

    public PageResult<Item> page(String keyword,String brandId,String status,int pageNo,int pageSize) {
        if(pageNo<1||pageNo>100000||pageSize<1||pageSize>100)throw invalid("分页参数无效");
        List<Object> args=new ArrayList<>();String filter=" AND d.active=true";
        if(!text(keyword).isEmpty()){if(keyword.length()>100)throw invalid("搜索内容最多100字");filter+=" AND CONCAT_WS(' ',d.name,b.name,d.original_region_text) ILIKE ? ESCAPE '!'";args.add("%"+like(keyword.trim())+"%");}
        if(!text(brandId).isEmpty()){filter+=" AND d.brand_id=?";args.add(parseBrand(brandId));}
        if(!text(status).isEmpty()){if(!List.of("RESOLVED","PARTIAL","UNRESOLVED").contains(status))throw invalid("匹配状态无效");filter+=" AND d.match_status=?";args.add(status);}
        List<Object> countArgs=new ArrayList<>();countArgs.add(tenant());countArgs.addAll(args);
        Long total=jdbc.queryForObject("SELECT count(*) FROM pf_franchise_directory d JOIN pf_map_brand b ON b.id=d.brand_id AND b.tenant_id=d.tenant_id WHERE d.tenant_id=?"+filter,Long.class,countArgs.toArray());
        args.add(pageSize);args.add((pageNo-1)*pageSize);
        return new PageResult<>(query(filter+" ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",args.toArray()),total==null?0:total);
    }
    public List<Item> candidates(String keyword) {
        if(text(keyword).length()>100)throw invalid("搜索内容最多100字");
        return query(" AND d.active=true AND d.org_id IS NULL AND CONCAT_WS(' ',d.name,b.name,d.original_region_text) ILIKE ? ESCAPE '!' ORDER BY d.name,d.id LIMIT 50","%"+like(text(keyword))+"%");
    }
    public List<Item> mapRecords(Long brandId) {
        String filter="""
            AND d.active=true AND (d.org_id IS NULL OR EXISTS(
              SELECT 1 FROM pf_org_unit o JOIN system_dept dep ON dep.id=o.dept_id
              WHERE o.dept_id=d.org_id AND o.kind='ORG' AND o.org_type='store' AND o.status='ACTIVE'
                AND dep.tenant_id=d.tenant_id AND dep.deleted=0 AND dep.status=0))
            """;
        return brandId==null?query(filter+" ORDER BY d.id"):query(filter+" AND d.brand_id=? ORDER BY d.id",brandId);
    }

    public ImportResult importWorkbook(byte[] bytes,boolean dryRun,long actor) {
        final List<FranchiseDirectoryWorkbook.Row> parsed;
        try{parsed=new FranchiseDirectoryWorkbook().parse(bytes);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        return importRows(parsed,dryRun,actor);
    }
    ImportResult importRows(List<FranchiseDirectoryWorkbook.Row> rows,boolean dryRun,long actor) {
        if(rows==null||rows.isEmpty()||rows.size()>2000)throw invalid("每次导入须为1至2000条来源记录");
        var catalog=regions.all();Set<String> seen=new HashSet<>();List<ImportRow> results=new ArrayList<>();
        for(var row:rows) {
            try {
                if(row==null)throw invalid("来源记录为空");
                if(!seen.add(text(row.sourceKey())))throw invalid("同一文件中来源编号重复");
                Prepared prepared=prepare(row,catalog);
                ImportRow result=dryRun?persist(prepared,actor,false):transactions.execute(tx->{
                    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",tenant()+":franchise-import:"+row.sourceKey());
                    return persist(prepared,actor,true);
                });
                results.add(result);
            } catch(ServiceException|IllegalArgumentException e) { results.add(failure(row,e.getMessage())); }
            catch(org.springframework.dao.DataAccessException e) { results.add(failure(row,"该行保存失败，请重试；来源编号可防止重复导入")); }
        }
        return new ImportResult(dryRun,results.size(),count(results,"CREATED"),count(results,"UPDATED"),count(results,"SKIPPED"),count(results,"FAILED"),
                (int)results.stream().filter(r->!r.status().equals("FAILED")&&!r.matchStatus().equals("RESOLVED")).count(),List.copyOf(results));
    }
    private Prepared prepare(FranchiseDirectoryWorkbook.Row row,List<FranchiseRegionCatalog.Region> catalog) {
        if(!text(row.issue()).isEmpty())throw invalid(row.issue());
        required(row.sourceKey(),180,"来源编号");required(row.brand(),40,"品牌");required(row.name(),128,"名称");
        if(text(row.originalRegionText()).length()>500||(text(row.originalRegionText()).isEmpty()&&(row.regionCodes()==null||row.regionCodes().isEmpty())))throw invalid("请填写地区原文或标准地区代码，原文最多500字");
        if(!List.of("FRANCHISE","COVERAGE").contains(row.recordType()))throw invalid("记录类型须为FRANCHISE或COVERAGE");
        if(text(row.sourceSheet()).length()>80||text(row.sourceCell()).length()>20)throw invalid("来源位置过长");
        if(row.regionCodes()!=null&&row.regionCodes().size()>100)throw invalid("单条记录最多关联100个行政区域");
        var resolution=FranchiseRegionCatalog.resolve(catalog,row.province(),row.city(),row.originalRegionText(),row.regionCodes());
        return new Prepared(row,resolution,catalog);
    }
    private ImportRow persist(Prepared prepared,long actor,boolean write) {
        var row=prepared.row();var resolution=prepared.resolution();
        if(write)jdbc.queryForList("SELECT id FROM pf_franchise_directory WHERE tenant_id=? AND source_key=? FOR UPDATE",tenant(),row.sourceKey());
        Item before=bySource(row.sourceKey(),prepared.catalog());
        String fingerprint=fingerprint(row);
        String previousFingerprint=before==null?null:jdbc.queryForObject("SELECT source_fingerprint FROM pf_franchise_directory WHERE tenant_id=? AND id=?",String.class,tenant(),before.id());
        String status=before==null?"CREATED":fingerprint.equals(previousFingerprint)||same(before,prepared)?"SKIPPED":"UPDATED";
        if(before!=null&&before.linkedOrgId()!=null&&!status.equals("SKIPPED"))throw invalid("该来源记录已开户，请从名录维护资料，导入不会覆盖已开户档案");
        String id=before==null?null:before.id();
        if(write&&!status.equals("SKIPPED")) {
            long brand=brand(row.brand());
            if(before==null) {
                id=UUID.randomUUID().toString();
                jdbc.update("""
                    INSERT INTO pf_franchise_directory(id,tenant_id,brand_id,name,record_type,original_region_text,match_status,
                        province_code,city_code,source_key,source_row,source_sheet,source_cell,created_by,source_fingerprint)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,tenant(),brand,row.name().trim(),row.recordType(),row.originalRegionText().trim(),resolution.status(),
                        emptyNull(resolution.provinceCode()),emptyNull(resolution.cityCode()),row.sourceKey(),row.sourceRow(),row.sourceSheet(),row.sourceCell(),actor,fingerprint);
            } else {
                jdbc.update("""
                    UPDATE pf_franchise_directory SET brand_id=?,name=?,record_type=?,original_region_text=?,match_status=?,
                      province_code=?,city_code=?,source_fingerprint=?,version=version+1,updated_at=now() WHERE tenant_id=? AND id=?
                    """,brand,row.name().trim(),row.recordType(),row.originalRegionText().trim(),resolution.status(),emptyNull(resolution.provinceCode()),emptyNull(resolution.cityCode()),fingerprint,tenant(),id);
            }
            replaceRegions(id,resolution.codes());
        }
        if(status.equals("SKIPPED")) {
            return new ImportRow(row.sourceKey(),row.sourceRow(),row.sourceSheet(),row.sourceCell(),before.brand(),before.name(),before.recordType(),
                    before.originalRegionText(),before.provinceCode(),before.cityCode(),before.regionCodes(),before.matchStatus(),status,"来源未变化，保留当前名录资料",before.id());
        }
        return new ImportRow(row.sourceKey(),row.sourceRow(),row.sourceSheet(),row.sourceCell(),row.brand(),row.name(),row.recordType(),
                row.originalRegionText(),resolution.provinceCode(),resolution.cityCode(),resolution.codes(),resolution.status(),status,resolution.message(),id);
    }
    private static boolean same(Item before,Prepared after) {
        var row=after.row();var r=after.resolution();
        return before.name().equals(row.name().trim())&&before.brand().equals(row.brand().trim())&&before.recordType().equals(row.recordType())
                &&before.originalRegionText().equals(row.originalRegionText().trim())&&before.matchStatus().equals(r.status())
                &&new HashSet<>(before.regionCodes()).equals(new HashSet<>(r.codes()))&&before.provinceCode().equals(r.provinceCode())&&before.cityCode().equals(r.cityCode());
    }
    private static String fingerprint(FranchiseDirectoryWorkbook.Row row) {
        String input=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(List.of(text(row.brand()),text(row.name()),text(row.recordType()),
                text(row.province()),text(row.city()),text(row.originalRegionText()),row.regionCodes()==null?List.of():row.regionCodes().stream().sorted().toList()));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private long brand(String name) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO pf_map_brand(tenant_id,name) VALUES(?,?) ON CONFLICT(tenant_id,name)
            DO UPDATE SET name=excluded.name RETURNING id
            """,Long.class,tenant(),name.trim()));
    }
    private void replaceRegions(String id,List<String> codes) {
        jdbc.update("DELETE FROM pf_franchise_directory_region WHERE tenant_id=? AND record_id=?",tenant(),id);
        for(String code:codes)jdbc.update("INSERT INTO pf_franchise_directory_region(tenant_id,record_id,region_code) VALUES(?,?,?)",tenant(),id,code);
    }

    @Transactional(rollbackFor=Exception.class)
    public Item create(RecordRequest input,long actor) {
        var row=requestRow(input,"manual:"+UUID.randomUUID());
        ImportRow result=persist(prepare(row,regions.all()),actor,true);return get(result.recordId());
    }
    @Transactional(rollbackFor=Exception.class)
    public Item update(String id,RecordRequest input,long actor) {
        lock(id);Item before=get(id);
        if(input==null||input.expectedVersion()==null||input.expectedVersion()!=before.version())throw new ServiceException(409,"名录资料已变化，请重新载入");
        Prepared after=prepare(requestRow(input,before.sourceKey()),regions.all());
        var row=after.row();
        if(before.linkedOrgId()!=null) {
            if(!"FRANCHISE".equals(row.recordType())||!"RESOLVED".equals(after.resolution().status()))throw invalid("已开户加盟商须保留明确区县和加盟商档案类型");
            var accounts=jdbc.queryForList("""
                SELECT a.account_id,COALESCE(a.map_district_code,location.region_code) AS map_district_code FROM pf_franchise_account a
                LEFT JOIN pf_store_location location ON location.account_id=a.account_id AND location.tenant_id=a.tenant_id
                JOIN pf_org_unit o ON o.dept_id=a.org_id AND o.kind='ORG' AND o.org_type='store'
                JOIN system_dept dep ON dep.id=o.dept_id AND dep.tenant_id=a.tenant_id AND dep.deleted=0
                JOIN pf_franchise_directory d ON d.account_id=a.account_id AND d.tenant_id=a.tenant_id AND d.org_id=a.org_id
                WHERE a.tenant_id=? AND a.org_id=? AND d.id=? AND a.account_type='BOSS' FOR UPDATE OF a
                """,tenant(),Long.parseLong(before.linkedOrgId()),id);
            if(accounts.size()!=1)throw new ServiceException(409,"关联组织已变化，请先核对开户资料");
            String primary=text((String)accounts.get(0).get("map_district_code"));
            if(!after.resolution().codes().contains(primary))primary="";
            var district=chooseDistrict(after.resolution().codes(),primary);
            jdbc.update("""
                UPDATE pf_franchise_account SET org_name=?,brand=?,province=?,city=?,area=?,map_district_code=?
                WHERE tenant_id=? AND org_id=? AND account_id=? AND account_type='BOSS'
                """,row.name(),row.brand(),district.provinceName(),district.cityName(),district.name(),district.code(),tenant(),Long.parseLong(before.linkedOrgId()),accounts.get(0).get("account_id"));
            jdbc.update("UPDATE system_dept SET name=? WHERE tenant_id=? AND id=?",row.name(),tenant(),Long.parseLong(before.linkedOrgId()));
        }
        jdbc.update("""
            UPDATE pf_franchise_directory SET name=?,brand_id=?,record_type=?,original_region_text=?,match_status=?,province_code=?,city_code=?,
              version=version+1,updated_at=now() WHERE tenant_id=? AND id=?
            """,row.name(),brand(row.brand()),row.recordType(),row.originalRegionText(),after.resolution().status(),
                emptyNull(after.resolution().provinceCode()),emptyNull(after.resolution().cityCode()),tenant(),id);
        replaceRegions(id,after.resolution().codes());return get(id);
    }
    private static FranchiseDirectoryWorkbook.Row requestRow(RecordRequest input,String source) {
        if(input==null)throw invalid("请填写名录资料");
        String type=text(input.recordType()).isEmpty()?"COVERAGE":input.recordType();
        String name=text(input.name());
        if(type.equals("COVERAGE")&&name.isEmpty())name=text(input.brand())+" · "+text(input.originalRegionText());
        return new FranchiseDirectoryWorkbook.Row(source,0,"","",text(input.brand()),name,text(input.provinceCode()),text(input.cityCode()),text(input.originalRegionText()),
                type,input.regionCodes(),"");
    }

    @Transactional(rollbackFor=Exception.class)
    public FranchiseOnboardService.Result onboard(FromDirectoryRequest input,long actor) {
        if(input==null||text(input.recordId()).isEmpty())throw invalid("请选择加盟商名录记录");
        lock(input.recordId());Item entry=get(input.recordId());
        if(input.expectedVersion()==null||input.expectedVersion()!=entry.version())throw new ServiceException(409,"所选名录已修改，请重新选择");
        if(entry.linkedOrgId()!=null)throw new ServiceException(409,"该名录已开户，请从组织目录管理");
        if(!entry.matchStatus().equals("RESOLVED"))throw invalid("请先修订并确认名录的具体区县，再开通账号");
        var district=chooseDistrict(entry.regionCodes(),input.districtCode());
        var result=onboard.onboardWithDistrict(entry.brand(),district.provinceName(),district.cityName(),district.name(),input.adminName(),input.adminMobile(),
                input.areaPinyin(),input.ownerPinyin(),input.remark(),actor,district.code());
        if(result.reusedOrg())throw new ServiceException(409,"该加盟商已开通，请核对已有组织后关联");
        // The legacy account trigger makes a transitional row; bind the selected source row instead.
        jdbc.update("DELETE FROM pf_franchise_directory WHERE tenant_id=? AND account_id=? AND id<>? AND source_key=?",tenant(),result.accountId(),entry.id(),"account:"+result.accountId());
        jdbc.update("""
            UPDATE pf_franchise_directory SET account_id=?,org_id=?,record_type='FRANCHISE',name=?,version=version+1,updated_at=now()
            WHERE tenant_id=? AND id=? AND org_id IS NULL
            """,result.accountId(),result.orgId(),result.orgName(),tenant(),entry.id());
        return result;
    }
    private FranchiseRegionCatalog.Region chooseDistrict(List<String> codes,String chosen) {
        var candidates=regions.all().stream().filter(r->codes.contains(r.code())&&r.level().equals("district")).toList();
        if(candidates.size()!=codes.size()||candidates.isEmpty())throw invalid("请先确认具体县级行政区，不能按大区或城市资料直接开户");
        if(!text(chosen).isEmpty())return candidates.stream().filter(r->r.code().equals(chosen)).findFirst().orElseThrow(()->invalid("所选区县不属于该名录记录"));
        if(candidates.size()!=1)throw invalid("该资料覆盖多个区县，请明确选择本次开户区县");
        return candidates.get(0);
    }
    private void lock(String id) { jdbc.queryForList("SELECT id FROM pf_franchise_directory WHERE tenant_id=? AND id=? FOR UPDATE",tenant(),id); }
    private static ImportRow failure(FranchiseDirectoryWorkbook.Row row,String message) {
        return new ImportRow(row==null?"":row.sourceKey(),row==null?0:row.sourceRow(),row==null?"":row.sourceSheet(),row==null?"":row.sourceCell(),
                row==null?"":row.brand(),row==null?"":row.name(),row==null?"":row.recordType(),row==null?"":row.originalRegionText(),"","",List.of(),"UNRESOLVED","FAILED",message,null);
    }
    private static int count(List<ImportRow> rows,String status){return (int)rows.stream().filter(r->r.status().equals(status)).count();}
    private static long tenant(){return TenantContextHolder.getRequiredTenantId();}
    private static String text(String value){return value==null?"":value.trim();}
    private static Object emptyNull(String value){return text(value).isEmpty()?null:value;}
    private static String like(String value){return value.replace("!","!!").replace("%","!%").replace("_","!_");}
    private static void required(String value,int max,String label){if(text(value).isEmpty()||value.length()>max)throw invalid(label+"不能为空且最多"+max+"字");}
    private static long parseBrand(String value){try{long id=Long.parseLong(value);if(id>0)return id;}catch(NumberFormatException ignored){}throw invalid("品牌编号无效");}
    private static ServiceException invalid(String message){return new ServiceException(400,message);}
}
