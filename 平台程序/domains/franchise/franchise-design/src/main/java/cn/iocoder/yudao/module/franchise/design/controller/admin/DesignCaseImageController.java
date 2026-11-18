package cn.iocoder.yudao.module.franchise.design.controller.admin;

import cn.iocoder.yudao.module.franchise.design.DesignAccessService;
import cn.iocoder.yudao.module.franchise.design.DesignPermissions;
import cn.iocoder.yudao.module.franchise.design.publiccase.PublicCaseImageService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/franchise/design/cases")
@PreAuthorize("@post.has('" + DesignPermissions.USE + "')")
public class DesignCaseImageController {
    private final DesignAccessService access;
    private final PublicCaseImageService images;
    public DesignCaseImageController(DesignAccessService access, PublicCaseImageService images) { this.access = access; this.images = images; }
    @GetMapping("/{caseId}/assets/{assetId}/content")
    public ResponseEntity<byte[]> content(@PathVariable long caseId, @PathVariable long assetId,
            @RequestParam(defaultValue = "COVER") String rendition) throws IOException, InterruptedException {
        if (!java.util.Set.of("COVER", "PREVIEW", "ORIGINAL").contains(rendition)) return ResponseEntity.badRequest().build();
        // Authorization and current publication/membership checks run before every cache lookup.
        var ticket = access.caseAssetDownloadUrl(access.post(), caseId, assetId);
        var image = images.read(ticket.downloadUrl(), rendition);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .header("Cache-Control", "private, max-age=300").header("X-Content-Type-Options", "nosniff")
                .header("X-Image-Rendition", rendition).body(image.bytes());
    }
}
