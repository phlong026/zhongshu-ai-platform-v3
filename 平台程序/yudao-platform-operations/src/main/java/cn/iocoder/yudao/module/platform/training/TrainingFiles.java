package cn.iocoder.yudao.module.platform.training;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.operations.OperationsGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import static cn.iocoder.yudao.module.platform.training.TrainingModel.*;

/** Private, persistent chunk storage. No file is ever exposed through the public infra file API. */
@Service
public class TrainingFiles {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TrainingFiles.class);
    static final int CHUNK=4*1024*1024;
    private static final Set<String> VIDEO=Set.of("mp4","mov","m4v","avi","mkv");
    private static final Set<String> AUDIO=Set.of("mp3","m4a");
    private static final Set<String> OFFICE=Set.of("docx","pptx","xlsx");
    private static final Set<String> IMAGES=Set.of("jpg","jpeg","png","webp");
    private final JdbcTemplate jdbc;
    private final OperationsGuard guard;
    private final TransactionTemplate tx;
    private final Path root;
    private final String ffmpeg,ffprobe,office;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private TrainingCloudStorage cloud;
    @Value("${training.pdfinfo:pdfinfo}") private String pdfinfo;
    private final ObjectMapper json;
    public TrainingFiles(JdbcTemplate jdbc,OperationsGuard guard,PlatformTransactionManager tm,ObjectMapper json,
      @Value("${training.storage-root:./data/training-private}")String root,
      @Value("${training.ffmpeg:ffmpeg}")String ffmpeg,@Value("${training.ffprobe:ffprobe}")String ffprobe,
      @Value("${training.libreoffice:soffice}")String office){
        this.jdbc=jdbc;this.guard=guard;this.tx=new TransactionTemplate(tm);this.json=json;
        this.root=Path.of(root).toAbsolutePath().normalize();this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.office=office;
        try{Files.createDirectories(this.root);}catch(IOException e){throw new IllegalStateException("培训私有存储目录不可用",e);}
    }
    public Path path(String key){
        check(key!=null&&!key.isBlank(),"文件尚未可用");
        Path p=root.resolve(key).normalize();check(p.startsWith(root)&&!p.equals(root),"文件路径无效");
        for(Path parent=p;parent!=null&&parent.startsWith(root);parent=parent.getParent())check(!Files.isSymbolicLink(parent),"文件路径无效");
        return p;
    }
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static String digest(Path file)throws Exception{var md=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    public Path readPath(String key){Path file=path(key);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&cloud!=null)cloud.restore(key,file);check(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"课程资料暂时不可用，请联系管理员");return file;}
    private static String ext(String file){int at=file.lastIndexOf('.');return at<0?"":file.substring(at+1).toLowerCase(Locale.ROOT);}
    public Map<String,Object> begin(Upload in){
        var a=guard.require();text(in.name(),200,"资料名称");
        text(in.fileName(),240,"文件名称");check(!in.fileName().matches(".*[\\\\/\\r\\n].*"),"文件名称无效");
        text(in.requestKey(),100,"上传请求号");check(in.sha256()!=null&&in.sha256().matches("[0-9a-f]{64}"),"请先计算文件校验值");
        String extension=ext(in.fileName());long limit=VIDEO.contains(extension)?2L*1024*1024*1024:AUDIO.contains(extension)?200L*1024*1024:IMAGES.contains(extension)?10L*1024*1024:Set.of("xlsx","csv").contains(extension)?50L*1024*1024:OFFICE.contains(extension)||extension.equals("pdf")?100L*1024*1024:Set.of("zip","dwg").contains(extension)?500L*1024*1024:0;
        check(in.size()>0&&in.size()<=limit,"文件类型不支持或超过对应大小上限");
        String fp=digest((in.name()+"|"+in.category()+"|"+in.materialId()+"|"+in.fileName()+"|"+in.size()+"|"+in.sha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return tx.execute(status->{
            lockCatalog(a.tenantId());
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",a.tenantId()+":"+a.userId()+":"+in.requestKey());
            var old=jdbc.queryForList("SELECT * FROM pf_training_upload WHERE tenant_id=? AND actor_id=? AND request_key=?",a.tenantId(),a.userId(),in.requestKey());
            if(!old.isEmpty()){check(fp.equals(old.get(0).get("fingerprint")),"相同请求号不能用于不同文件");return upload(old.get(0).get("id").toString());}
            String material=in.materialId();int number=1;
            if(material==null||material.isBlank()){
                requireMaterialCategory(a.tenantId(),in.category());material=id();jdbc.update("INSERT INTO pf_training_material(id,tenant_id,name,category,created_by) VALUES(?,?,?,?,?)",material,a.tenantId(),in.name().trim(),in.category(),a.userId());
            }else{
                check(!jdbc.queryForList("SELECT id FROM pf_training_material WHERE id=? AND tenant_id=? AND deleted_at IS NULL FOR UPDATE",material,a.tenantId()).isEmpty(),"资料不存在");
                number=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM pf_training_material_version WHERE material_id=? AND tenant_id=?",Integer.class,material,a.tenantId());
                jdbc.update("UPDATE pf_training_material SET revision=revision+1 WHERE id=? AND tenant_id=?",material,a.tenantId());
            }
            String version=id(),upload=id();int count=(int)((in.size()+CHUNK-1)/CHUNK);
            jdbc.update("INSERT INTO pf_training_material_version(id,tenant_id,material_id,version_no,file_name,extension,size_bytes,sha256,created_by) VALUES(?,?,?,?,?,?,?,?,?)",version,a.tenantId(),material,number,in.fileName(),extension,in.size(),in.sha256(),a.userId());
            jdbc.update("INSERT INTO pf_training_upload(id,tenant_id,actor_id,version_id,request_key,fingerprint,chunk_size,chunk_count) VALUES(?,?,?,?,?,?,?,?)",upload,a.tenantId(),a.userId(),version,in.requestKey(),fp,CHUNK,count);
            return upload(upload);
        });
    }
    public Map<String,Object> upload(String uploadId){var a=guard.require();var rows=jdbc.queryForList("SELECT u.*,v.material_id,v.file_name,v.size_bytes,v.sha256,v.state AS processing_state,v.error FROM pf_training_upload u JOIN pf_training_material_version v ON v.id=u.version_id JOIN pf_training_material m ON m.id=v.material_id AND m.tenant_id=v.tenant_id WHERE m.deleted_at IS NULL AND u.id=? AND u.tenant_id=? AND u.actor_id=?",uploadId,a.tenantId(),a.userId());check(!rows.isEmpty(),"上传任务不存在");var out=new LinkedHashMap<>(rows.get(0));out.put("parts",jdbc.queryForList("SELECT part_no FROM pf_training_upload_part WHERE upload_id=? ORDER BY part_no",Integer.class,uploadId));out.remove("fingerprint");return out;}
    public void part(String uploadId,int part,InputStream input)throws IOException{
        var a=guard.require();Map<String,Object> u=upload(uploadId);check("UPLOADING".equals(u.get("state")),"任务已完成，不能继续写入");
        int count=((Number)u.get("chunk_count")).intValue();long size=((Number)u.get("size_bytes")).longValue();check(part>=0&&part<count,"分片编号无效");
        long expected=Math.min(CHUNK,size-(long)part*CHUNK);Path directory=path("uploads/"+uploadId);Files.createDirectories(directory);
        Path temp=Files.createTempFile(directory,"receiving-",".tmp");
        try{
            try(var out=Files.newOutputStream(temp)){byte[] bytes=new byte[65536];long total=0;int n;while((n=input.read(bytes))!=-1){total+=n;check(total<=expected,"分片超出预期大小");out.write(bytes,0,n);}check(total==expected,"分片不完整");}
            String hash;try{hash=digest(temp);}catch(Exception e){throw new IOException(e);}
            tx.executeWithoutResult(status->{
                var locked=jdbc.queryForMap("SELECT state FROM pf_training_upload WHERE id=? AND tenant_id=? AND actor_id=? FOR UPDATE",uploadId,a.tenantId(),a.userId());check("UPLOADING".equals(locked.get("state")),"上传任务已结束");
                var old=jdbc.queryForList("SELECT sha256 FROM pf_training_upload_part WHERE upload_id=? AND part_no=?",uploadId,part);
                if(!old.isEmpty()){check(hash.equals(old.get(0).get("sha256")),"已上传分片内容不一致");return;}
                try{Files.move(temp,directory.resolve(part+".part"),StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new ServiceException(500,"分片保存失败，请重试");}
                jdbc.update("INSERT INTO pf_training_upload_part(upload_id,part_no,size_bytes,sha256) VALUES(?,?,?,?)",uploadId,part,expected,hash);
            });
        }finally{Files.deleteIfExists(temp);}
    }
    public Map<String,Object> finish(String uploadId){
        var a=guard.require();
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT u.*,v.size_bytes,v.sha256,v.extension FROM pf_training_upload u JOIN pf_training_material_version v ON v.id=u.version_id JOIN pf_training_material m ON m.id=v.material_id AND m.tenant_id=v.tenant_id WHERE m.deleted_at IS NULL AND u.id=? AND u.tenant_id=? AND u.actor_id=? FOR UPDATE OF u",uploadId,a.tenantId(),a.userId());check(!rows.isEmpty(),"上传任务不存在");var u=rows.get(0);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanupCompletedParts(uploadId); }
            });
            if("COMPLETE".equals(u.get("state")))return;
            int count=((Number)u.get("chunk_count")).intValue();check(jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_upload_part WHERE upload_id=?",Integer.class,uploadId)==count,"还有分片尚未上传");
            String version=u.get("version_id").toString(),key="versions/"+version+"/source."+u.get("extension");Path file=path(key);
            try{
                Files.createDirectories(file.getParent());Path merged=file.resolveSibling("merge.tmp");
                try(var out=Files.newOutputStream(merged)){for(int n=0;n<count;n++)Files.copy(path("uploads/"+uploadId+"/"+n+".part"),out);}
                check(Files.size(merged)==((Number)u.get("size_bytes")).longValue()&&digest(merged).equals(u.get("sha256")),"文件校验失败，请重新选择原文件");
                Files.move(merged,file,StandardCopyOption.REPLACE_EXISTING);
                jdbc.update("UPDATE pf_training_material_version SET original_key=?,state='UPLOADED',error='' WHERE id=? AND tenant_id=?",key,version,a.tenantId());
                jdbc.update("UPDATE pf_training_upload SET state='COMPLETE' WHERE id=?",uploadId);
            }catch(ServiceException e){throw e;}catch(Exception e){throw new ServiceException(500,"合并文件失败，请重试");}
        });return upload(uploadId);
    }
    @Scheduled(fixedDelayString="${training.chunk-cleanup-delay-ms:60000}")
    public void cleanupCompletedParts() {
        Path uploads=path("uploads");
        if (!Files.isDirectory(uploads,LinkOption.NOFOLLOW_LINKS)) return;
        try (var directories=Files.newDirectoryStream(uploads)) {
            for (Path directory:directories) {
                if (Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) {
                    cleanupCompletedParts(directory.getFileName().toString());
                }
            }
        } catch(IOException e) {
            LOG.warn("Could not scan training upload parts",e);
        }
    }

    private void cleanupCompletedParts(String uploadId) {
        try {
            var completed=jdbc.queryForList("""
                SELECT v.original_key,u.chunk_count
                FROM pf_training_upload u JOIN pf_training_material_version v
                    ON v.id=u.version_id AND v.tenant_id=u.tenant_id
                WHERE u.id=? AND u.state='COMPLETE' AND v.original_key IS NOT NULL
                """,uploadId);
            if (completed.isEmpty()) return;
            var upload=completed.get(0);
            if (!Files.isRegularFile(path((String)upload.get("original_key")),LinkOption.NOFOLLOW_LINKS)) return;
            Path directory=path("uploads/"+uploadId);
            int count=((Number)upload.get("chunk_count")).intValue();
            for (int part=0;part<count;part++) Files.deleteIfExists(directory.resolve(part+".part"));
            try { Files.deleteIfExists(directory); }
            catch(DirectoryNotEmptyException e) { /* An in-flight write cleans up its own temporary file. */ }
        } catch(IOException | RuntimeException e) {
            LOG.warn("Could not reclaim completed training upload parts for {}",uploadId,e);
        }
    }

    public Map<String,Object> materials(String keyword,String category,int page){
        var a=guard.require();check(page>0&&page<=10000,"页码无效");check(keyword==null||keyword.length()<=100,"关键词过长");
        String from=" FROM pf_training_material m JOIN pf_training_material_version v ON v.material_id=m.id AND v.tenant_id=m.tenant_id WHERE m.deleted_at IS NULL AND m.tenant_id=? AND m.name ILIKE ? AND (?='' OR m.category=?)";
        String kw="%"+(keyword==null?"":keyword.replace("%","\\%").replace("_","\\_"))+"%",cat=category==null?"":category;
        var rows=jdbc.queryForList("SELECT m.id AS material_id,m.name,m.category,m.revision AS material_revision,v.id AS version_id,v.version_no,v.file_name,v.extension,v.size_bytes,v.state,v.error,v.duration,v.created_at"+from+" ORDER BY m.created_at DESC,v.version_no DESC LIMIT 50 OFFSET ?",a.tenantId(),kw,cat,cat,(page-1)*50);
        return Map.of("list",rows,"total",jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,a.tenantId(),kw,cat,cat));
    }
    public Map<String,Object> version(String version,long tenant){var rows=jdbc.queryForList("SELECT v.*,m.name,m.category,m.revision AS material_revision FROM pf_training_material_version v JOIN pf_training_material m ON m.id=v.material_id AND m.tenant_id=v.tenant_id WHERE v.id=? AND v.tenant_id=? AND m.deleted_at IS NULL",version,tenant);check(!rows.isEmpty(),"资料版本不存在");return rows.get(0);}
    // Short metadata transactions share this tenant lock; conversion and cloud I/O run outside it.
    void lockCatalog(long tenant){jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","training-catalog:"+tenant);}
    private Map<String,Object> materialForChange(String id,OperationsGuard.Actor actor,Long revision){
        var rows=jdbc.queryForList("SELECT * FROM pf_training_material WHERE id=? AND tenant_id=? AND deleted_at IS NULL FOR UPDATE",id,actor.tenantId());
        check(!rows.isEmpty(),"资料不存在或已删除");var material=rows.get(0);
        check(revision!=null&&revision==((Number)material.get("revision")).longValue(),"资料已被其他人修改，请刷新后重试");return material;
    }
    public Map<String,Object> editMaterial(String id,MaterialEdit input){
        var actor=guard.require();check(input!=null,"请填写资料信息");text(input.name(),200,"资料名称");
        return tx.execute(status->{lockCatalog(actor.tenantId());requireMaterialCategory(actor.tenantId(),input.category());materialForChange(id,actor,input.expectedRevision());long revision=input.expectedRevision()+1;
            jdbc.update("UPDATE pf_training_material SET name=?,category=?,revision=? WHERE id=? AND tenant_id=?",input.name().trim(),input.category(),revision,id,actor.tenantId());
            jdbc.update("INSERT INTO pf_training_audit(tenant_id,actor_id,object_id,action,revision,detail) VALUES(?,?,?,'MATERIAL_EDIT',?,?)",actor.tenantId(),actor.userId(),id,revision,input.name().trim()+" / "+input.category());return Map.of("id",id,"revision",revision);
        });
    }
    private static final String UNCATEGORIZED="未分类";
    private void seedMaterialCategories(long tenant){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_material_category WHERE tenant_id=?",Long.class,tenant)>0)return;
        var names=new ArrayList<>(CATEGORIES);names.add(UNCATEGORIZED);
        for(int n=0;n<names.size();n++)jdbc.update("INSERT INTO pf_training_material_category(id,tenant_id,name,sort_order,system_default) VALUES(?,?,?,?,?)",id(),tenant,names.get(n),n==CATEGORIES.size()?9999:n,n==CATEGORIES.size());
    }
    private void requireMaterialCategory(long tenant,String category){
        seedMaterialCategories(tenant);check(category!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_material_category WHERE tenant_id=? AND name=? AND deleted_at IS NULL",Long.class,tenant,category)>0,"资料分类已调整，请刷新后重新选择分类");
    }
    public List<Map<String,Object>> materialCategories(){
        var a=guard.require();return tx.execute(status->{lockCatalog(a.tenantId());seedMaterialCategories(a.tenantId());return jdbc.queryForList("SELECT c.*,COUNT(m.id) AS material_count FROM pf_training_material_category c LEFT JOIN pf_training_material m ON m.tenant_id=c.tenant_id AND m.category=c.name AND m.deleted_at IS NULL WHERE c.tenant_id=? AND c.deleted_at IS NULL GROUP BY c.id ORDER BY c.sort_order,c.created_at,c.id",a.tenantId());});
    }
    private String categoryName(CategoryEdit in){
        check(in!=null,"请填写分类名称");text(in.name(),40,"分类名称");String name=java.text.Normalizer.normalize(in.name().strip(),java.text.Normalizer.Form.NFC);
        check(name.codePoints().noneMatch(Character::isISOControl)&&!Set.of("全部","全部资料").contains(name),"分类名称无效");return name;
    }
    private Map<String,Object> categoryForChange(String id,long tenant,Long revision){
        var rows=jdbc.queryForList("SELECT * FROM pf_training_material_category WHERE id=? AND tenant_id=? AND deleted_at IS NULL FOR UPDATE",id,tenant);check(!rows.isEmpty(),"资料分类不存在或已删除");var row=rows.get(0);
        check(!Boolean.TRUE.equals(row.get("system_default")),"未分类为保留分类，不能修改或删除");check(revision!=null&&revision==((Number)row.get("revision")).longValue(),"分类已被其他人修改，请刷新后重试");return row;
    }
    private void uniqueCategory(long tenant,String name,String except){check(jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_material_category WHERE tenant_id=? AND lower(name)=lower(?) AND deleted_at IS NULL AND id<>?",Long.class,tenant,name,except)==0,"分类名称已存在");}
    public Map<String,Object> createMaterialCategory(CategoryEdit in){
        var a=guard.require();String name=categoryName(in);return tx.execute(status->{lockCatalog(a.tenantId());seedMaterialCategories(a.tenantId());uniqueCategory(a.tenantId(),name,"");check(jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_material_category WHERE tenant_id=? AND deleted_at IS NULL",Long.class,a.tenantId())<200,"资料分类最多200个");String key=id();
            jdbc.update("INSERT INTO pf_training_material_category(id,tenant_id,name,sort_order,created_by) VALUES(?,?,?,100,?)",key,a.tenantId(),name,a.userId());categoryAudit(a,key,"CATEGORY_CREATE",0,name);return Map.of("id",key,"name",name,"revision",0);
        });
    }
    public Map<String,Object> renameMaterialCategory(String id,CategoryEdit in){
        var a=guard.require();String name=categoryName(in);return tx.execute(status->{lockCatalog(a.tenantId());var row=categoryForChange(id,a.tenantId(),in.expectedRevision());uniqueCategory(a.tenantId(),name,id);long revision=in.expectedRevision()+1;
            jdbc.update("UPDATE pf_training_material_category SET name=?,revision=?,updated_at=now() WHERE id=? AND tenant_id=?",name,revision,id,a.tenantId());
            int moved=jdbc.update("UPDATE pf_training_material SET category=?,revision=revision+1 WHERE tenant_id=? AND category=? AND deleted_at IS NULL",name,a.tenantId(),row.get("name"));categoryAudit(a,id,"CATEGORY_RENAME",revision,row.get("name")+" → "+name+" / "+moved+"份资料");return Map.of("id",id,"name",name,"revision",revision,"materialCount",moved);
        });
    }
    public Map<String,Object> deleteMaterialCategory(String id,CategoryDelete in){
        var a=guard.require();check(in!=null,"请选择资料分类");return tx.execute(status->{lockCatalog(a.tenantId());seedMaterialCategories(a.tenantId());var row=categoryForChange(id,a.tenantId(),in.expectedRevision());
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_material WHERE tenant_id=? AND category=? AND deleted_at IS NULL",Long.class,a.tenantId(),row.get("name"));check(in.expectedMaterialCount()!=null&&in.expectedMaterialCount()==count,"分类中的资料数量已变化，请刷新后重新确认删除");
            jdbc.update("UPDATE pf_training_material SET category=?,revision=revision+1 WHERE tenant_id=? AND category=? AND deleted_at IS NULL",UNCATEGORIZED,a.tenantId(),row.get("name"));long revision=in.expectedRevision()+1;
            jdbc.update("UPDATE pf_training_material_category SET deleted_at=now(),revision=?,updated_at=now() WHERE id=? AND tenant_id=?",revision,id,a.tenantId());categoryAudit(a,id,"CATEGORY_DELETE",revision,row.get("name")+" / "+count+"份资料移至未分类");return Map.of("id",id,"movedMaterials",count,"targetCategory",UNCATEGORIZED);
        });
    }
    private void categoryAudit(OperationsGuard.Actor a,String id,String action,long revision,String detail){jdbc.update("INSERT INTO pf_training_audit(tenant_id,actor_id,object_id,action,revision,detail) VALUES(?,?,?,?,?,?)",a.tenantId(),a.userId(),id,action,revision,detail);}
    public List<Map<String,Object>> materialReferences(String material,long tenant){
        String match="jsonb_path_exists(DATA,'$.** ? (@.versionId == $v || @.mainVersionId == $v || @.coverVersionId == $v)',jsonb_build_object('v',v.id))";
        String versions="SELECT 1 FROM pf_training_material_version v WHERE v.material_id=? AND v.tenant_id=c.tenant_id AND ";
        String draft="SELECT c.id,c.draft->>'title' AS title,0 AS release_no,'DRAFT' AS reference_type FROM pf_training_course c WHERE c.tenant_id=? AND EXISTS("+versions+match.replace("DATA","c.draft")+")";
        String released="SELECT c.id,r.content->>'title' AS title,r.release_no,'PUBLISHED' AS reference_type FROM pf_training_course c JOIN pf_training_release r ON r.id=c.active_release AND r.tenant_id=c.tenant_id WHERE c.tenant_id=? AND c.state='PUBLISHED' AND EXISTS("+versions+match.replace("DATA","r.content")+")";
        return jdbc.queryForList(draft+" UNION ALL "+released+" ORDER BY title,reference_type",tenant,material,tenant,material);
    }
    public void deleteMaterial(String id,MaterialDelete input){
        var actor=guard.require();check(input!=null,"请选择资料版本");tx.executeWithoutResult(status->{lockCatalog(actor.tenantId());materialForChange(id,actor,input.expectedRevision());
            check(materialReferences(id,actor.tenantId()).isEmpty(),"资料仍被课程草稿或已发布课程引用，请先解除引用并更新课程");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM pf_training_material_version WHERE tenant_id=? AND material_id=? AND state='PROCESSING'",Long.class,actor.tenantId(),id)==0,"资料正在处理，请完成或处理失败后再删除");
            long revision=input.expectedRevision()+1;jdbc.update("UPDATE pf_training_material SET deleted_at=now(),deleted_by=?,revision=? WHERE id=? AND tenant_id=?",actor.userId(),revision,id,actor.tenantId());
            jdbc.update("INSERT INTO pf_training_audit(tenant_id,actor_id,object_id,action,revision,detail) VALUES(?,?,?,'MATERIAL_DELETE',?,'从资料库删除，保留历史文件及审计记录')",actor.tenantId(),actor.userId(),id,revision);
        });
    }
    public void retry(String version){var a=guard.require();version(version,a.tenantId());check(jdbc.update("UPDATE pf_training_material_version SET state='UPLOADED',error='',lease_until=NULL WHERE id=? AND tenant_id=? AND state='FAILED' AND original_key IS NOT NULL",version,a.tenantId())==1,"仅失败的处理任务可以重试");}
    @Scheduled(fixedDelayString="${training.process-delay-ms:3000}")
    public void processPending(){
        jdbc.update("UPDATE pf_training_material_version SET state='UPLOADED',lease_until=NULL WHERE state='PROCESSING' AND lease_until<now()");
        var j=tx.execute(status->{
            var jobs=jdbc.queryForList("SELECT v.id,v.tenant_id FROM pf_training_material_version v JOIN pf_training_material m ON m.id=v.material_id AND m.tenant_id=v.tenant_id WHERE v.state='UPLOADED' AND m.deleted_at IS NULL ORDER BY v.created_at LIMIT 1");
            if(jobs.isEmpty())return null;var job=jobs.get(0);lockCatalog(((Number)job.get("tenant_id")).longValue());
            int claimed=jdbc.update("UPDATE pf_training_material_version v SET state='PROCESSING',lease_until=now()+interval '30 minutes' WHERE v.id=? AND v.state='UPLOADED' AND EXISTS(SELECT 1 FROM pf_training_material m WHERE m.id=v.material_id AND m.tenant_id=v.tenant_id AND m.deleted_at IS NULL)",job.get("id"));
            return claimed==1?job:null;
        });
        if(j==null)return;String id=j.get("id").toString();
        try{process(id,((Number)j.get("tenant_id")).longValue());}catch(Exception e){jdbc.update("UPDATE pf_training_material_version SET state='FAILED',lease_until=NULL,error=? WHERE id=? AND state='PROCESSING'",e instanceof ServiceException?e.getMessage():"处理失败，请检查原文件或联系管理员检查媒体处理服务",id);}
    }
    void process(String id,long tenant)throws Exception{
        var v=version(id,tenant);String extension=v.get("extension").toString();Path source=readPath(v.get("original_key").toString());String folder="versions/"+id+"/";
        String preview=null,media=null,poster=null;double duration=0;
        if(VIDEO.contains(extension)||AUDIO.contains(extension)){
            Path probe=source.resolveSibling("probe.json");run(List.of(ffprobe,"-v","error","-show_format","-show_streams","-of","json",source.toString()),probe,Duration.ofMinutes(1));
            var meta=json.readTree(Files.readString(probe));duration=meta.path("format").path("duration").asDouble();check(Double.isFinite(duration)&&duration>0,"视频或音频格式无效");
            if(VIDEO.contains(extension)){
                boolean video=false;for(var stream:meta.path("streams"))if("video".equals(stream.path("codec_type").asText()))video=true;check(video,"文件没有视频轨道");
                media=folder+"play.mp4";run(List.of(ffmpeg,"-nostdin","-y","-v","error","-threads","2","-filter_threads","1","-i",source.toString(),"-map","0:v:0","-map","0:a:0?","-vf","scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2","-c:v","libx264","-threads","2","-preset","fast","-crf","24","-pix_fmt","yuv420p","-c:a","aac","-movflags","+faststart",path(media).toString()),source.resolveSibling("processing.log"),Duration.ofMinutes(25));
                poster=folder+"poster.jpg";run(List.of(ffmpeg,"-nostdin","-y","-v","error","-threads","2","-filter_threads","1","-i",path(media).toString(),"-frames:v","1",path(poster).toString()),source.resolveSibling("poster.log"),Duration.ofMinutes(1));
            }else{media=folder+"play.mp3";run(List.of(ffmpeg,"-nostdin","-y","-v","error","-threads","2","-filter_threads","1","-i",source.toString(),"-vn","-c:a","libmp3lame",path(media).toString()),source.resolveSibling("processing.log"),Duration.ofMinutes(25));}
        }else if(extension.equals("pdf")){
            byte[] head=new byte[5];try(var in=Files.newInputStream(source)){check(in.read(head)==5&&new String(head,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"),"PDF文件格式无效");}run(List.of(pdfinfo,source.toString()),source.resolveSibling("pdf-check.log"),Duration.ofSeconds(30));preview=v.get("original_key").toString();
        }else if(OFFICE.contains(extension)){
            try(var zip=new ZipFile(source.toFile())){check(zip.getEntry("[Content_Types].xml")!=null&&zip.getEntry(extension.equals("docx")?"word/document.xml":extension.equals("pptx")?"ppt/presentation.xml":"xl/workbook.xml")!=null,"Office文件格式无效");check(zip.stream().noneMatch(e->e.getName().toLowerCase(Locale.ROOT).contains("vbaproject")),"不支持包含宏的课件");}
            Path out=source.resolveSibling("preview");Files.createDirectories(out);String profile=source.resolveSibling("office-profile").toUri().toString();
            run(List.of(office,"-env:UserInstallation="+profile,"--headless","--convert-to","pdf","--outdir",out.toString(),source.toString()),source.resolveSibling("office.log"),Duration.ofMinutes(3));
            Path pdf=out.resolve("source.pdf");check(Files.isRegularFile(pdf)&&Files.size(pdf)>5,"文档预览转换失败");run(List.of(pdfinfo,pdf.toString()),source.resolveSibling("pdf-check.log"),Duration.ofSeconds(30));preview=root.relativize(pdf).toString().replace('\\','/');
        }else if(extension.equals("webp")){
            Path image=source.resolveSibling("image.png");run(List.of(ffmpeg,"-nostdin","-y","-v","error","-threads","2","-filter_threads","1","-i",source.toString(),"-frames:v","1",image.toString()),source.resolveSibling("image.log"),Duration.ofSeconds(60));try(var in=ImageIO.createImageInputStream(image.toFile())){var readers=ImageIO.getImageReaders(in);check(readers.hasNext(),"图片格式无效");var reader=readers.next();try{reader.setInput(in);check((long)reader.getWidth(0)*reader.getHeight(0)<=40000000,"图片像素过大");check(reader.read(0)!=null,"图片损坏");}finally{reader.dispose();}}preview=root.relativize(image).toString().replace('\\','/');poster=preview;
        }else if(IMAGES.contains(extension)){
            try(var in=ImageIO.createImageInputStream(source.toFile())){var readers=ImageIO.getImageReaders(in);check(readers.hasNext(),"图片格式无效");var reader=readers.next();try{reader.setInput(in);check((long)reader.getWidth(0)*reader.getHeight(0)<=40000000,"图片像素过大");check(reader.read(0)!=null,"图片损坏");}finally{reader.dispose();}}preview=v.get("original_key").toString();poster=preview;
        }else if(extension.equals("zip")){try(var zip=new ZipFile(source.toFile())){check(zip.size()>0,"压缩包为空");}}
        else if(extension.equals("dwg")){byte[] h=new byte[6];try(var in=Files.newInputStream(source)){check(in.read(h)==6&&new String(h,java.nio.charset.StandardCharsets.US_ASCII).matches("AC10[0-9]{2}"),"DWG文件格式无效");}}
        else if(extension.equals("csv")){check(Files.size(source)<50L*1024*1024,"CSV文件过大");}
        else throw new ServiceException(400,"不支持的资料类型");
        if(cloud!=null){var keys=new LinkedHashSet<String>();keys.add(v.get("original_key").toString());for(String key:new String[]{preview,media,poster})if(key!=null)keys.add(key);for(String key:keys)cloud.store(key,path(key));}
        jdbc.update("UPDATE pf_training_material_version SET state='READY',preview_key=?,media_key=?,poster_key=?,duration=?,error='',lease_until=NULL WHERE id=? AND state='PROCESSING'",preview,media,poster,duration,id);
    }
    private void run(List<String> args,Path log,Duration timeout)throws Exception{
        Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!p.waitFor(timeout.toSeconds(),TimeUnit.SECONDS)){p.destroyForcibly();throw new ServiceException(400,"文件处理超时，可在稍后重试");}
        check(p.exitValue()==0,"处理工具未完成转换，请核查文件格式及处理服务");
    }
}
