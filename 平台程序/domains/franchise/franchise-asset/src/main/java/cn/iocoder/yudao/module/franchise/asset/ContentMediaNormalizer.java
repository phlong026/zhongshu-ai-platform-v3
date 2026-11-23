package cn.iocoder.yudao.module.franchise.asset;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Decode local, integrity-checked content assets into the formats accepted by creation providers. */
final class ContentMediaNormalizer {
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif",
            "image/bmp", "image/tiff", "image/heic", "image/heif", "image/avif");
    private static final Set<String> AUDIO = Set.of("audio/mpeg", "audio/mp3", "audio/mp4", "audio/m4a",
            "audio/x-m4a", "audio/wav", "audio/x-wav", "audio/wave", "audio/aac", "audio/x-aac",
            "audio/ogg", "audio/flac", "audio/x-flac", "audio/amr", "audio/3gpp", "audio/webm");
    private static final Set<String> VIDEO = Set.of("video/mp4", "video/mov", "video/quicktime", "video/webm",
            "video/x-matroska", "video/x-msvideo", "video/3gpp", "video/3gpp2", "video/mpeg", "video/x-m4v");

    record Info(double duration, int width, int height, boolean video, boolean audio, String codec) { }
    static boolean supported(String mime) { return IMAGES.contains(mime) || AUDIO.contains(mime) || VIDEO.contains(mime); }
    static String outputMime(String mime) {
        if (IMAGES.contains(mime)) return "image/jpeg".equals(mime) ? mime : "image/png";
        if (AUDIO.contains(mime)) return "audio/mpeg";
        if (VIDEO.contains(mime)) return "video/mp4";
        return mime;
    }
    static String inputExtension(String mime) {
        return switch (mime) {
            case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; case "image/bmp" -> ".bmp";
            case "image/tiff" -> ".tiff"; case "image/heic", "image/heif" -> ".heic"; case "image/avif" -> ".avif";
            case "audio/mp3" -> ".mp3"; case "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a";
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"; case "audio/aac", "audio/x-aac" -> ".aac";
            case "audio/ogg" -> ".ogg"; case "audio/flac", "audio/x-flac" -> ".flac";
            case "audio/amr" -> ".amr"; case "audio/3gpp", "video/3gpp" -> ".3gp";
            case "audio/webm", "video/webm" -> ".webm"; case "video/mov", "video/quicktime" -> ".mov";
            case "video/x-matroska" -> ".mkv"; case "video/x-msvideo" -> ".avi";
            case "video/3gpp2" -> ".3g2"; case "video/mpeg" -> ".mpeg"; case "video/x-m4v" -> ".m4v";
            default -> null;
        };
    }

    /** Return a durable rejection only for unusable input; unavailable tools are retryable IO failures. */
    String normalize(String mime, Path source, Path output, long limit, AssetContentScanner scanner) throws IOException {
        if (!supported(mime)) return "MEDIA_TYPE: 素材格式暂不支持";
        if (Set.of("image/jpeg", "image/png").contains(mime)) return scanner.scan(mime, source, output, limit);
        if (!signatureMatches(mime, source)) return "MEDIA_TYPE: 文件内容与声明格式不符，请重新选择原文件";
        Path working = Files.createTempDirectory(source.getParent(), "fr-media-v1-");
        try {
            Path input = source;
            if (Set.of("image/heic", "image/heif", "image/avif").contains(mime)) {
                if (!safeHeifDimensions(source)) return "PIXEL_GUARD: HEIF 图片尺寸无效或超过4000万像素";
                Path png = working.resolve("decoded.png");
                if (!run(List.of(binary("heif", "heif-convert"), source.toString(), png.toString()),
                        working.resolve("decode.log"), Duration.ofSeconds(45), working, limit)) return "MEDIA_DECODE: 图片无法解码或转换后超过50MB，请检查原文件";
                // A multi-image HEIF is represented by its first still image.
                if (!Files.isRegularFile(png)) png = working.resolve("decoded-1.png");
                if (!Files.isRegularFile(png)) return "MEDIA_DECODE: 图片无法解码";
                return scanner.scan("image/png", png, output, limit);
            }
            Info before = inspect(input, working.resolve("probe.json"));
            boolean image = IMAGES.contains(mime), video = VIDEO.contains(mime);
            if (image && (!before.video() || !safePixels(before.width(), before.height())))
                return "PIXEL_GUARD: 图片尺寸无效或超过4000万像素";
            if (video && (!before.video() || !safePixels(before.width(), before.height()))
                    || !image && !video && (!before.audio() || before.video()))
                return "MEDIA_TYPE: 文件没有对应的视频或音频内容";
            if (!image && (!Double.isFinite(before.duration()) || before.duration() <= 0 || before.duration() > 3600))
                return "MEDIA_DURATION: 素材时长无效或超过1小时";
            var command = new ArrayList<>(List.of(binary("ffmpeg", "ffmpeg"), "-nostdin", "-y", "-v", "error",
                    "-max_alloc", "67108864", "-threads", "2", "-filter_threads", "1",
                    "-protocol_whitelist", "file,pipe", "-i", input.toString(), "-map_metadata", "-1"));
            if (image) command.addAll(List.of("-map", "0:v:0", "-frames:v", "1", "-c:v", "png", "-f", "image2"));
            else if (video) command.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?", "-vf",
                    "scale=w='min(1920,iw)':h='min(1920,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-threads", "2",
                    "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000", "-movflags", "+faststart", "-f", "mp4"));
            else command.addAll(List.of("-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", "128k", "-ac", "2", "-ar", "44100", "-f", "mp3"));
            command.addAll(List.of("-fs", Long.toString(limit + 1), output.toString()));
            if (!run(command, working.resolve("convert.log"), Duration.ofSeconds(90), output, limit))
                return "MEDIA_DECODE: 素材无法完整转换，请检查文件或缩短素材";
            if (!Files.isRegularFile(output) || Files.size(output) == 0 || Files.size(output) > limit)
                return "MEDIA_SIZE: 转换后素材超过50MB，请缩短素材";
            Info after = inspect(output, working.resolve("normalized.json"));
            if (!image && Math.abs(after.duration() - before.duration()) > Math.max(1.0, before.duration() * 0.01))
                return "MEDIA_DURATION: 转换后素材不完整，请缩短素材";
            if (video && (!after.video() || !"h264".equals(after.codec())) || !image && !video && !after.audio())
                return "MEDIA_DECODE: 素材转换未完成";
            return null;
        } catch (InvalidMedia error) {
            return "MEDIA_DECODE: 素材无法解码，请检查原文件";
        } finally {
            try (var files = Files.walk(working)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    Info inspect(Path file, Path result) throws IOException {
        if (!run(List.of(binary("ffprobe", "ffprobe"), "-v", "error", "-max_alloc", "67108864",
                "-protocol_whitelist", "file,pipe", "-show_entries", "format=duration:stream=codec_type,codec_name,width,height,duration:stream_disposition=attached_pic",
                "-of", "compact=p=0:nk=0", file.toString()),
                result, Duration.ofSeconds(15), null, 0)) throw new InvalidMedia();
        if (Files.size(result) > 1024 * 1024) throw new InvalidMedia();
        double duration = 0;
        boolean video = false, audio = false; int width = 0, height = 0; String codec = "";
        for (String line : Files.readAllLines(result)) {
            var stream = new java.util.HashMap<String, String>();
            for (String part : line.split("\\|")) { int equals = part.indexOf('='); if (equals > 0) stream.put(part.substring(0, equals), part.substring(equals + 1)); }
            if ("video".equals(stream.get("codec_type")) && !"1".equals(stream.get("disposition:attached_pic")) && !video) {
                video = true; width = (int) number(stream.get("width")); height = (int) number(stream.get("height")); codec = stream.getOrDefault("codec_name", "");
            }
            if ("audio".equals(stream.get("codec_type"))) audio = true;
            duration = Math.max(duration, number(stream.get("duration")));
        }
        return new Info(duration, width, height, video, audio, codec);
    }
    private static double number(String value) { try { return Double.parseDouble(value); } catch (NumberFormatException | NullPointerException ignored) { return 0; } }
    private static boolean safePixels(int width, int height) {
        return width > 0 && height > 0 && width <= 10000 && height <= 10000 && (long) width * height <= 40_000_000;
    }
    private static String binary(String key, String fallback) { return System.getProperty("platform.media." + key, fallback); }
    private static final class InvalidMedia extends IOException { }

    private static boolean run(List<String> command, Path log, Duration timeout, Path output, long limit) throws IOException {
        Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start(); }
        catch (IOException unavailable) { throw new IOException("媒体转换组件不可用，请联系管理员检查 FFmpeg/HEIF 配置", unavailable); }
        long until = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > until) throw new IOException("媒体转换超时，请缩短素材后重试");
                if (Files.size(log) > 1024 * 1024 || exceeds(output, limit))
                    return false;
            }
            return process.exitValue() == 0 && Files.size(log) <= 1024 * 1024 && !exceeds(output, limit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("媒体转换已中断，请重试", interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
    private static boolean exceeds(Path output, long limit) throws IOException {
        if (output == null || !Files.exists(output)) return false;
        if (!Files.isDirectory(output)) return Files.size(output) > limit;
        try (var files = Files.list(output)) {
            long total = 0;
            for (Path file : files.toList()) { if (Files.isRegularFile(file)) total += Files.size(file); if (total > limit) return true; }
        }
        return false;
    }

    private static boolean signatureMatches(String mime, Path file) throws IOException {
        byte[] b; try (var in = Files.newInputStream(file)) { b = in.readNBytes(64); }
        String header = new String(b, StandardCharsets.ISO_8859_1);
        boolean bmff = b.length >= 12 && header.substring(4, 8).equals("ftyp");
        boolean riff = b.length >= 12 && header.startsWith("RIFF");
        boolean ebml = b.length >= 4 && (b[0] & 255) == 0x1a && (b[1] & 255) == 0x45 && (b[2] & 255) == 0xdf && (b[3] & 255) == 0xa3;
        if (mime.equals("image/webp")) return riff && header.substring(8, 12).equals("WEBP");
        if (mime.equals("image/gif")) return header.startsWith("GIF87a") || header.startsWith("GIF89a");
        if (mime.equals("image/bmp")) return header.startsWith("BM");
        if (mime.equals("image/tiff")) return header.startsWith("II*\0") || header.startsWith("MM\0*");
        if (Set.of("image/heic", "image/heif", "image/avif").contains(mime)) return bmff;
        if (Set.of("audio/wav", "audio/x-wav", "audio/wave").contains(mime)) return riff && header.substring(8, 12).equals("WAVE");
        if (mime.equals("video/x-msvideo")) return riff && header.substring(8, 12).equals("AVI ");
        if (Set.of("audio/ogg", "audio/flac", "audio/x-flac", "audio/amr").contains(mime))
            return header.startsWith("OggS") || header.startsWith("fLaC") || header.startsWith("#!AMR");
        if (Set.of("audio/webm", "video/webm", "video/x-matroska").contains(mime)) return ebml;
        if (Set.of("audio/mpeg", "audio/mp3", "audio/aac", "audio/x-aac").contains(mime))
            return header.startsWith("ID3") || b.length >= 2 && (b[0] & 255) == 255 && (b[1] & 0xe0) == 0xe0;
        if (mime.equals("video/mpeg")) return b.length >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 1;
        return bmff || b.length >= 8 && Set.of("moov", "mdat", "wide", "free").contains(header.substring(4, 8));
    }

    // Inspect only structural HEIF property boxes, before native decoding allocates pixels.
    private static boolean safeHeifDimensions(Path file) throws IOException {
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            int[] count = {4096, 0};
            return heifBoxes(input, 0, input.length(), 0, count) && count[1] > 0;
        }
    }
    private static boolean heifBoxes(RandomAccessFile file, long offset, long end, int depth, int[] budget) throws IOException {
        if (depth > 4) return false;
        while (offset + 8 <= end) {
            if (--budget[0] <= 0) return false;
            file.seek(offset); long size = Integer.toUnsignedLong(file.readInt());
            byte[] name = new byte[4]; file.readFully(name); String type = new String(name, StandardCharsets.US_ASCII);
            long header = 8;
            if (size == 1) { if (offset + 16 > end) return false; size = file.readLong(); header = 16; }
            if (size == 0) size = end - offset;
            if (size < header || size > end - offset) return false;
            if (type.equals("ispe")) {
                if (size < header + 12) return false;
                file.seek(offset + header + 4); int width = file.readInt(), height = file.readInt();
                if (!safePixels(width, height)) return false; budget[1]++;
            } else if (Set.of("meta", "iprp", "ipco").contains(type)) {
                if (!heifBoxes(file, offset + header + (type.equals("meta") ? 4 : 0), offset + size, depth + 1, budget)) return false;
            }
            offset += size;
        }
        return true;
    }
}
