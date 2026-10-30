-- 列表/详情图片衍生版本（2026-10-20 性能治理）
-- 原图 object_key 不变；衍生物写同前缀的 -thumb.jpg / -preview.jpg，由服务端生成并复用原图的
-- 对象级权限与公开展示授权。NULL 表示尚未生成：读链路按需生成并回写，上传时也会直接生成。
ALTER TABLE asset ADD COLUMN IF NOT EXISTS thumb_key VARCHAR(256) NULL;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS preview_key VARCHAR(256) NULL;

COMMENT ON COLUMN asset.thumb_key IS '缩略图对象 key（长边 480 JPEG）；NULL 表示尚未生成，读取时按需补生成';
COMMENT ON COLUMN asset.preview_key IS '预览图对象 key（长边 1280 JPEG）；NULL 表示尚未生成，读取时按需补生成';
