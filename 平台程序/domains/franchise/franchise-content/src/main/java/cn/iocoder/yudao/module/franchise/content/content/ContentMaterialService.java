package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;

/** Library entries never replace or delete the asset bytes used by existing content tasks. */
@Service
public class ContentMaterialService {
    private final JdbcTemplate jdbc;
    private final AssetAccessPort assets;
    private final StoreServiceAccountPort accounts;
    private static final Set<String> MIMES = Set.of("image/jpeg", "image/png", "video/mp4", "audio/mpeg");
    public ContentMaterialService(DataSource ds, AssetAccessPort assets, StoreServiceAccountPort accounts) {
        this.jdbc = new JdbcTemplate(ds); this.assets = assets; this.accounts = accounts;
    }
    private long tenant() {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null) throw exception(RESOURCE_FORBIDDEN);
        return tenant;
    }
    private AssetAccessPort.Actor actor(PostSnapshot p) {
        return new AssetAccessPort.Actor(p.orgId(), accounts.requireServiceAccount(p.orgId()), p.userId(), p.postId());
    }
    private RuntimeException invalid(String message) {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(
                cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID.getCode(), message);
    }
    private String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private String name(Object value) {
        String name = text(value);
        if (name.isEmpty() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl))
            throw invalid("素材名称须为 1–100 个字符");
        return name;
    }
    private String visibility(Object value) {
        String scope = text(value);
        if (!Set.of("PERSONAL", "COMPANY").contains(scope)) throw invalid("请选择素材范围");
        return scope;
    }
    private long number(Object value, long min, long max) {
        String s = text(value);
        if (!s.matches("[0-9]{1,19}")) throw invalid("素材参数无效");
        long n;
        try { n=Long.parseLong(s); } catch(NumberFormatException e) { throw invalid("素材参数无效"); }
        if (n < min || n > max) throw invalid("素材参数超出范围");
        return n;
    }
    public Map<String,Object> upload(PostSnapshot p, Map<String,Object> body) {
        long tenant = tenant();
        String name = name(body.get("name")), mime = text(body.get("mimeType"));
        String scope = visibility(body.get("visibility")), sha = text(body.get("sha256"));
        long size = number(body.get("sizeBytes"),1,50L<<20);
        long duration = number(body.getOrDefault("durationSec",0),0,86400);
        if (!MIMES.contains(mime) || !sha.matches("[0-9a-fA-F]{64}"))
            throw invalid("支持 JPG、PNG、MP4、MP3，单个文件不超过 50MB");
        Long parent = body.get("coverOf") == null ? null : number(body.get("coverOf"),1,Long.MAX_VALUE);
        if (parent != null) {
            var row = require(p, parent, true);
            if (!mime.startsWith("image/") || !"video/mp4".equals(row.get("mime_type")) || row.get("parent_asset_id") != null)
                throw invalid("仅视频可上传图片封面");
            scope = (String) row.get("visibility");
        }
        var ticket = assets.createUploadTicket(actor(p),"CONTENT_ASSET",mime,size,sha);
        jdbc.update("INSERT INTO fr_content_material(asset_id,tenant_id,company_id,author_user_id,name,visibility,mime_type,size_bytes,duration_sec,parent_asset_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                ticket.assetId(),tenant,p.orgId(),p.userId(),name,scope,mime,size,duration,parent);
        return Map.of("assetId",Long.toString(ticket.assetId()),"uploadUrl",ticket.uploadUrl());
    }
    private Map<String,Object> require(PostSnapshot p, long id, boolean write) {
        var rows = jdbc.queryForList("SELECT * FROM fr_content_material WHERE asset_id=? AND tenant_id=? AND company_id=? AND removed=FALSE", id,tenant(),p.orgId());
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        var row=rows.get(0);
        boolean owner=((Number)row.get("author_user_id")).longValue()==p.userId();
        if (!write && row.get("parent_asset_id") instanceof Number parent) {
            require(p,parent.longValue(),false);
        } else if (!owner && (write || !"COMPANY".equals(row.get("visibility")))) throw exception(RESOURCE_FORBIDDEN);
        return row;
    }
    public Map<String,Object> complete(PostSnapshot p, long id) {
        require(p,id,true);
        var completed=assets.completeUpload(actor(p),id);
        if (!"ACCEPTED".equals(completed.uploadStatus())) throw invalid("素材校验未通过，请重新上传");
        jdbc.update("UPDATE fr_content_material SET state='READY' WHERE asset_id=? AND tenant_id=? AND company_id=? AND author_user_id=? AND removed=FALSE",id,tenant(),p.orgId(),p.userId());
        return Map.of("assetId",Long.toString(id),"status","READY");
    }
    public Map<String,Object> page(PostSnapshot p, String scope, String type, String keyword, int page) {
        visibility(scope);
        if (!Set.of("ALL","IMAGE","VIDEO","AUDIO").contains(type) || page<1 || page>10000 || keyword.length()>100)
            throw invalid("筛选参数无效");
        String where=" tenant_id=? AND company_id=? AND removed=FALSE AND state='READY' AND parent_asset_id IS NULL";
        List<Object> args=new ArrayList<>(List.of(tenant(),p.orgId()));
        if ("PERSONAL".equals(scope)) { where+=" AND author_user_id=?"; args.add(p.userId()); }
        else where+=" AND visibility='COMPANY'";
        if (!"ALL".equals(type)) { where+=" AND mime_type LIKE ?"; args.add(type.toLowerCase(Locale.ROOT)+"/%"); }
        if (!keyword.isBlank()) { where+=" AND name ILIKE ? ESCAPE '!'"; args.add("%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        Long total=jdbc.queryForObject("SELECT count(*) FROM fr_content_material WHERE"+where,Long.class,args.toArray());
        args.add(20);args.add((page-1)*20);
        var rows=jdbc.queryForList("""
            SELECT material.*, cover.asset_id AS cover_id
            FROM (SELECT * FROM fr_content_material WHERE """+where+"""
                ORDER BY created_at DESC,asset_id DESC LIMIT ? OFFSET ?) material
            LEFT JOIN LATERAL (
                SELECT c.asset_id FROM fr_content_material c
                WHERE c.parent_asset_id=material.asset_id AND c.tenant_id=material.tenant_id
                    AND c.company_id=material.company_id AND c.state='READY' AND c.removed=FALSE
                ORDER BY c.created_at DESC,c.asset_id DESC LIMIT 1
            ) cover ON true
            ORDER BY material.created_at DESC,material.asset_id DESC
            """,args.toArray());
        var items=rows.stream().map(row->{
            Map<String,Object> item=new LinkedHashMap<>();
            long id=((Number)row.get("asset_id")).longValue();
            item.put("id",Long.toString(id));item.put("name",row.get("name"));item.put("mimeType",row.get("mime_type"));
            item.put("sizeBytes",row.get("size_bytes"));item.put("durationSec",row.get("duration_sec"));item.put("visibility",row.get("visibility"));
            item.put("createdAt",row.get("created_at").toString());item.put("canManage",((Number)row.get("author_user_id")).longValue()==p.userId());
            item.put("coverId",Objects.toString(row.get("cover_id"),""));
            return item;
        }).toList();
        return Map.of("items",items,"total",total==null?0:total,"page",page,"pageSize",20);
    }
    public Map<String,Object> download(PostSnapshot p,long id) {
        var row=require(p,id,false);
        if (!"READY".equals(row.get("state"))) throw invalid("素材尚未上传完成");
        var ticket=assets.createDownloadUrl(actor(p),id);
        return Map.of("downloadUrl",ticket.downloadUrl(),"expiresAt",ticket.expiresAt()==null?0:ticket.expiresAt());
    }
    public void update(PostSnapshot p,long id,Map<String,Object> body) {
        var row=require(p,id,true);
        if (row.get("parent_asset_id")!=null) throw exception(RESOURCE_FORBIDDEN);
        List<String> columns=new ArrayList<>();List<Object> values=new ArrayList<>();
        if(body.containsKey("name")){columns.add("name=?");values.add(name(body.get("name")));}
        if(body.containsKey("visibility")){columns.add("visibility=?");values.add(visibility(body.get("visibility")));}
        if(columns.isEmpty())throw invalid("请选择要修改的内容");
        values.add(id);values.add(tenant());values.add(p.orgId());values.add(p.userId());
        jdbc.update("UPDATE fr_content_material SET "+String.join(",",columns)+" WHERE asset_id=? AND tenant_id=? AND company_id=? AND author_user_id=? AND removed=FALSE",values.toArray());
    }
    public void remove(PostSnapshot p,long id) {
        require(p,id,true);
        jdbc.update("UPDATE fr_content_material SET removed=TRUE WHERE asset_id=? AND tenant_id=? AND company_id=? AND author_user_id=?",id,tenant(),p.orgId(),p.userId());
    }
}
