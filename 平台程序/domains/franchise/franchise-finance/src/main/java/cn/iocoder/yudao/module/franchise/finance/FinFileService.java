package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinFileDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinVoucherMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import cn.hutool.core.util.HexUtil;

/**
 * 财务凭证文件：本地存储（prod 切 COS 时仅换 storage_key 解析层）；元数据入库（sha256/大小/上传人）。
 */
@Service
@Slf4j
public class FinFileService {

    @Resource
    private FinVoucherMapper voucherFileMapper;

    @Value("${franchise.fin.storage-dir:./data/fr-fin-files}")
    private String storageDir;

    @Transactional
    public long upload(PostSnapshot post, MultipartFile file) {
        try {
            String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
            String key = LocalDate.now() + "/" + System.nanoTime() + "-" + original;
            Path dir = Path.of(storageDir, LocalDate.now().toString());
            Files.createDirectories(dir);
            Path target = dir.resolve(System.nanoTime() + "-" + original);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexUtil.encodeHexStr(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)));
            FinFileDO record = new FinFileDO().setCompanyId(post.orgId()).setFileName(original)
                    .setStorageKey(target.toString()).setContentType(file.getContentType())
                    .setSizeBytes(file.getSize()).setSha256(sha256).setUploadedByUserId(post.userId());
            voucherFileMapper.insert(record);
            return record.getId();
        } catch (IOException e) {
            throw new IllegalStateException("凭证保存失败，请重试", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("凭证摘要计算失败", e);
        }
    }

    public FinFileDO get(long companyId, long fileId) {
        FinFileDO file = voucherFileMapper.selectById(fileId);
        return file != null && file.getCompanyId() == companyId ? file : null;
    }

    public Path resolve(FinFileDO file) {
        return Path.of(file.getStorageKey());
    }

}
