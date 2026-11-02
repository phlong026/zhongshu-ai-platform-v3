package cn.iocoder.yudao.module.design.asset;

import java.util.List;
import java.util.Set;

/**
 * 资产类型上传策略（服务端强制，架构 §6.9）：
 * 用户草图 JPG/PNG ≤10MB；立面参考图 DESIGN_REFERENCE JPG/PNG ≤20MB（需求五b）；
 * 后台公司案例（封面/平面/立面）JPG/PNG/GIF/BMP/TIFF/PDF ≤20MB（2026-10-16 需求：封面上传
 * 扩展常见图片格式；仅列 JDK ImageIO 可解码的格式，WEBP/HEIC 无原生解码器不放开）。
 *
 * <p>B 端业务类型（14 文档 D-基1-5，audience=B_END）：财务凭证/工程图纸/现场照片/合同附件——
 * 经 franchise-asset 通道（服务账号身份）上传，免内容审核（moderationRequired=false）；
 * C 端入口 {@link AssetService#createUploadTicket} 拒绝 B_END（入口隔离，14 文档 D-基1-4a）。
 */
public enum AssetTypePolicy {

    USER_SKETCH(10L * 1024 * 1024, Set.of("image/jpeg", "image/png")),
    DESIGN_REFERENCE(20L * 1024 * 1024, Set.of("image/jpeg", "image/png")),
    USER_AVATAR(2L * 1024 * 1024, Set.of("image/jpeg", "image/png")),
    CASE_IMAGE(20L * 1024 * 1024, Set.of("image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff")),
    CASE_PDF(20L * 1024 * 1024, Set.of("application/pdf")),
    AI_OUTPUT(20L * 1024 * 1024, Set.of("image/jpeg", "image/png")),
    FIN_VOUCHER(20L * 1024 * 1024, Set.of("image/jpeg", "image/png", "application/pdf"), Audience.B_END, false),
    ENG_DRAWING(30L * 1024 * 1024, Set.of("application/pdf", "image/jpeg", "image/png"), Audience.B_END, false),
    ENG_SITE_PHOTO(10L * 1024 * 1024, Set.of("image/jpeg", "image/png"), Audience.B_END, false),
    CONTRACT_ATTACHMENT(20L * 1024 * 1024, Set.of("application/pdf", "image/jpeg", "image/png"), Audience.B_END, false),
    /** 内容创作素材（阶段 1.5 / 16 文档 D-1.5-9）：jpeg/png/mp3/mp4 ≤50MB，B_END 免审 */
    CONTENT_ASSET(50L * 1024 * 1024, Set.of("image/jpeg", "image/png", "audio/mpeg", "video/mp4"), Audience.B_END, false);

    /** 类型受众：C_END=C 端业主入口可传；B_END=仅 B 端服务账号入口（franchise-asset/Port）可传 */
    public enum Audience {
        C_END, B_END
    }

    private final long maxBytes;

    private final Set<String> allowedMimes;

    private final Audience audience;

    private final boolean moderationRequired;

    AssetTypePolicy(long maxBytes, Set<String> allowedMimes) {
        this(maxBytes, allowedMimes, Audience.C_END, true);
    }

    AssetTypePolicy(long maxBytes, Set<String> allowedMimes, Audience audience, boolean moderationRequired) {
        this.maxBytes = maxBytes;
        this.allowedMimes = allowedMimes;
        this.audience = audience;
        this.moderationRequired = moderationRequired;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public List<String> allowedMimes() {
        return List.copyOf(allowedMimes);
    }

    public boolean mimeAllowed(String mime) {
        return allowedMimes.contains(mime);
    }

    public Audience audience() {
        return audience;
    }

    /** B_END=false：completeUpload 跳过内容安全扫描与内容审核（对象存在性/大小/SHA 校验保留） */
    public boolean moderationRequired() {
        return moderationRequired;
    }

    public boolean bEnd() {
        return audience == Audience.B_END;
    }

    /** 上传前缀（AI 输出隔离区：Runtime 仅可写此前缀） */
    public String keyPrefix() {
        return switch (this) {
            case USER_SKETCH -> "user-sketches";
            case DESIGN_REFERENCE -> "user-references";
            case USER_AVATAR -> "user-avatars";
            case CASE_IMAGE, CASE_PDF -> "company-cases";
            case AI_OUTPUT -> "ai-quarantine";
            case FIN_VOUCHER -> "fin-vouchers";
            case ENG_DRAWING -> "eng-drawings";
            case ENG_SITE_PHOTO -> "eng-site-photos";
            case CONTRACT_ATTACHMENT -> "contract-attachments";
            case CONTENT_ASSET -> "content-assets";
        };
    }

}
