package cn.iocoder.yudao.module.franchise.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ContentMediaNormalizerTest {
    @TempDir Path directory;
    final ContentMediaNormalizer normalizer = new ContentMediaNormalizer();
    final AssetContentScanner scanner = new AssetContentScanner();

    void ffmpeg(String... args) throws Exception {
        var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-v", "error", "-threads", "1", "-filter_threads", "1"));
        command.addAll(List.of(args));
        Path log = directory.resolve("fixture.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("fixture encoding timeout").isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    Path convert(String mime, Path source) throws Exception {
        Path result = Files.createTempFile(directory, "normalized-", ".tmp");
        assertThat(normalizer.normalize(mime, source, result, 50L << 20, scanner)).isNull();
        assertThat(Files.size(result)).isPositive(); return result;
    }
    @Test void phoneHevcMovBecomesPlayableH264Mp4AndKeepsItsDuration() throws Exception {
        Path mov = directory.resolve("iphone.mov");
        ffmpeg("-f", "lavfi", "-i", "color=c=blue:s=640x360:r=15", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "5.2", "-c:v", "libx265", "-x265-params", "pools=1:frame-threads=1:log-level=error", "-tag:v", "hvc1",
                "-c:a", "aac", mov.toString());
        assertThat(normalizer.inspect(mov, directory.resolve("original.json")).codec()).isEqualTo("hevc");
        var info = normalizer.inspect(convert("video/quicktime", mov), directory.resolve("result.json"));
        assertThat(info.codec()).isEqualTo("h264"); assertThat(info.audio()).isTrue();
        assertThat(info.width()).isEqualTo(640); assertThat(info.height()).isEqualTo(360);
        assertThat(info.duration()).isBetween(5.0, 5.5);
    }
    @Test void commonRecordingsBecomeMp3WithoutLosingAudio() throws Exception {
        for (var item : List.of(new String[]{"m4a","audio/mp4","aac"}, new String[]{"aac","audio/aac","aac"},
                new String[]{"wav","audio/wav","pcm_s16le"}, new String[]{"ogg","audio/ogg","libvorbis"},
                new String[]{"flac","audio/flac","flac"})) {
            Path source = directory.resolve("recording." + item[0]);
            ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "5.2", "-c:a", item[2], source.toString());
            var info = normalizer.inspect(convert(item[1], source), directory.resolve(item[0] + ".json"));
            assertThat(info.audio()).isTrue(); assertThat(info.video()).isFalse(); assertThat(info.duration()).isBetween(5.0, 5.6);
        }
    }
    @Test void webpGifAndAvifBecomeActualPng() throws Exception {
        for (var item : List.of(new String[]{"webp", "libwebp"}, new String[]{"gif", "gif"}, new String[]{"avif", "libaom-av1"})) {
            Path source = directory.resolve("photo." + item[0]);
            ffmpeg("-f", "lavfi", "-i", "color=c=green:s=64x48", "-frames:v", "1", "-c:v", item[1], source.toString());
            Path result = convert("image/" + item[0], source);
            assertThat(javax.imageio.ImageIO.read(result.toFile()).getWidth()).isEqualTo(64);
            assertThat(Files.readAllBytes(result)).startsWith(new byte[]{(byte)137,80,78,71});
        }
    }
    @Test void heifAndMultiImageHeifAreDecodedByTheInstalledHeifDecoder() throws Exception {
        Path png = directory.resolve("source.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64,48,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",png.toFile());
        for (boolean multiple : List.of(false,true)) {
            Path heic=directory.resolve(multiple ? "multiple.heic" : "single.heic");
            var command=new ArrayList<>(List.of("heif-enc","-o",heic.toString(),png.toString()));
            if (multiple) command.add(png.toString());
            Path log=directory.resolve("heif-fixture.log");
            var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try { assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).as(Files.readString(log)).isZero(); }
            finally { if(process.isAlive())process.destroyForcibly(); }
            Path converted=convert("image/heic",heic);
            assertThat(javax.imageio.ImageIO.read(converted.toFile()).getWidth()).isEqualTo(64);
            try (var files=Files.list(directory)) { assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("fr-media-v1-"))).isTrue(); }
        }
    }
    @Test void headersAloneAndWrongMediaFamiliesCannotPass() throws Exception {
        Path fake = directory.resolve("fake.mp4"); Files.write(fake, new byte[]{0,0,0,12,'f','t','y','p','m','p','4','2'});
        Path result = directory.resolve("bad.tmp");
        assertThat(normalizer.normalize("video/mp4", fake, result, 50L << 20, scanner)).startsWith("MEDIA_DECODE");
        Path wav = directory.resolve("voice.wav");
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440", "-t", "5.2", wav.toString());
        assertThat(normalizer.normalize("video/mp4", wav, result, 50L << 20, scanner)).startsWith("MEDIA_TYPE");
        Path url = directory.resolve("playlist.m3u8"); Files.writeString(url, "#EXTM3U\nhttps://127.0.0.1/private");
        assertThat(normalizer.normalize("audio/mpeg", url, result, 50L << 20, scanner)).startsWith("MEDIA_TYPE");
    }
    @Test void unavailableDecoderRemainsRetryableAndCleansTemporaryFiles() throws Exception {
        Path wav = directory.resolve("voice.wav");
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440", "-t", "5.2", wav.toString());
        String old = System.getProperty("platform.media.ffprobe");
        System.setProperty("platform.media.ffprobe", directory.resolve("missing-binary").toString());
        try { assertThatThrownBy(() -> normalizer.normalize("audio/wav", wav, directory.resolve("out.mp3"), 50L << 20, scanner))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("组件不可用"); }
        finally { if (old == null) System.clearProperty("platform.media.ffprobe"); else System.setProperty("platform.media.ffprobe", old); }
        try (var files = Files.list(directory)) { assertThat(files.noneMatch(p -> p.getFileName().toString().startsWith("fr-media-v1-"))).isTrue(); }
    }
}
