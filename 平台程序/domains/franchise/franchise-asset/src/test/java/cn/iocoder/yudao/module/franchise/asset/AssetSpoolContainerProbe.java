package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Run in the release image with an isolated PostgreSQL schema and three valid 50 MiB MP4 fixtures. */
public final class AssetSpoolContainerProbe {
    public static void main(String[] args) throws Exception {
        Path work=Path.of(args[0]), spool=Path.of(System.getProperty("platform.asset.spool-dir"));
        var db=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"postgres","");
        var jdbc=new JdbcTemplate(db);
        var barrier=new CyclicBarrier(3,()->{
            try(var files=Files.list(spool)){
                long count=files.filter(p->p.getFileName().toString().startsWith("fr-asset-"))
                    .filter(p->{try{return Files.size(p)==50L<<20;}catch(Exception e){throw new IllegalStateException(e);}}).count();
                if(count!=3) throw new AssertionError("Simultaneous full snapshots: "+count);
            }catch(java.io.IOException e){throw new IllegalStateException(e);}
        });
        var blobs=new LocalObjectStorageAdapter(work.resolve("objects").toString()){
            @Override public void putObject(String key,Path path){
                if(key.contains("/accepted/")){
                    try{barrier.await(120,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                }
                super.putObject(key,path);
            }
        };
        var post=new PostSnapshot(11,22,33,"store","sales",null,false,Set.of());
        var actor=new Actor(33,777,22,11);
        var service=new LocalAssetService(db,new DataSourceTransactionManager(db),blobs,
                new AssetContentScanner(),()->Optional.of(post),org->777L);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<String>>();
            for(int n=1;n<=3;n++){
                Path source=work.resolve(n+".mp4");
                jobs.add(pool.submit(()->{
                    TenantContextHolder.setTenantId(1L);
                    try {
                        long bytes=Files.size(source); if(bytes!=50L<<20)throw new AssertionError(bytes);
                        String hash=sha(source); var ticket=service.createUploadTicket(actor,"CONTENT_ASSET","video/mp4",bytes,hash);
                        try(var input=Files.newInputStream(source)){service.receiveUpload(actor,ticket.assetId(),ticket.uploadUrl().split("token=")[1],input);}
                        for(int i=0;i<2;i++)if(!"ACCEPTED".equals(service.completeUpload(actor,ticket.assetId()).uploadStatus()))throw new AssertionError("Rejected");
                        var row=jdbc.queryForMap("SELECT object_key,accepted_sha256 FROM fr_asset WHERE id=?",ticket.assetId());
                        if(!hash.equals(row.get("accepted_sha256"))||!hash.equals(sha(work.resolve("objects").resolve(row.get("object_key").toString()))))
                            throw new AssertionError("Digest mismatch");
                        return hash;
                    } finally { TenantContextHolder.clear(); }
                }));
            }
            for(var job:jobs)System.out.println("ACCEPTED_SHA256="+job.get(180,TimeUnit.SECONDS));
            try(var files=Files.list(spool)){if(files.findAny().isPresent())throw new AssertionError("Residual file");}
            System.out.println("CONCURRENT_BYTES=157286400 RESIDUAL_FILES=0");
        }finally{pool.shutdownNow();}
    }

    private static String sha(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=new DigestInputStream(Files.newInputStream(file),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());
    }
}
