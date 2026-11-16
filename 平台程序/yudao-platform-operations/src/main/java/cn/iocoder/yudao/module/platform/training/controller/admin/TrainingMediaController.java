package cn.iocoder.yudao.module.platform.training.controller.admin;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.platform.training.TrainingLearning;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.*;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Native video/image clients cannot attach bearer headers; opaque, expiring tickets are re-authorized per range request. */
@RestController @RequestMapping("/training/media")
public class TrainingMediaController {
    private final TrainingLearning learning;
    public TrainingMediaController(TrainingLearning learning){this.learning=learning;}
    @GetMapping("/{token}") @PermitAll @TenantIgnore
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(enable=false)
    public void content(@PathVariable String token,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var media=learning.media(token);long length=Files.size(media.file());long start=0,end=length-1;String range=request.getHeader("Range");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("Accept-Ranges","bytes");
        response.setContentType(media.mime());response.setHeader("Content-Disposition",(media.download()?ContentDisposition.attachment():ContentDisposition.inline()).filename(media.name(),StandardCharsets.UTF_8).build().toString());
        if(range!=null){try{if(!range.matches("bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException();String[] values=range.substring(6).split("-",-1);if(values[0].isEmpty()){long suffix=Long.parseLong(values[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,length-suffix);}else{start=Long.parseLong(values[0]);if(!values[1].isEmpty())end=Math.min(end,Long.parseLong(values[1]));}if(start>=length||end<start)throw new IllegalArgumentException();response.setStatus(206);response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+length);}catch(IllegalArgumentException e){response.setStatus(416);response.setHeader("Content-Range","bytes */"+length);return;}}
        response.setContentLengthLong(end-start+1);try(var file=new RandomAccessFile(media.file().toFile(),"r")){file.seek(start);byte[] bytes=new byte[65536];long remaining=end-start+1;while(remaining>0){int read=file.read(bytes,0,(int)Math.min(bytes.length,remaining));if(read<0)break;response.getOutputStream().write(bytes,0,read);remaining-=read;}}
    }
}
