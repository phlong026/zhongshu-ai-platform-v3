package cn.iocoder.yudao.module.franchise.crm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Offline catalog; only three province files are retained in memory. No external request on the form path. */
public final class CrmRegions {
    public record Option(String code, String name) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Map<String, List<Option>>> CACHE = new LinkedHashMap<>(4, .75f, true);
    private static final List<Option> ROOT = roots();

    private CrmRegions() { }

    public static List<Option> children(String parent) {
        if (parent == null || parent.isBlank()) return ROOT;
        if (!parent.matches("(?:\\d{2}|\\d{4}|\\d{6}|\\d{9})")) return List.of();
        String province = parent.substring(0, 2);
        if (ROOT.stream().noneMatch(option -> option.code().equals(province))) return List.of();
        return province(province).getOrDefault(parent, List.of());
    }

    private static List<Option> roots() {
        try (var stream = new ClassPathResource("crm-regions/root.json").getInputStream()) {
            return List.copyOf(JSON.readValue(stream, new TypeReference<List<Option>>() { }));
        } catch (IOException error) {
            throw new IllegalStateException("客户地址数据不可用", error);
        }
    }

    private static synchronized Map<String, List<Option>> province(String code) {
        var cached = CACHE.get(code);
        if (cached != null) return cached;
        try (var stream = new GZIPInputStream(new ClassPathResource("crm-regions/" + code + ".json.gz").getInputStream())) {
            Map<String, List<Option>> data = JSON.readValue(stream, new TypeReference<>() { });
            data.replaceAll((key, value) -> List.copyOf(value));
            CACHE.put(code, data);
            if (CACHE.size() > 3) CACHE.remove(CACHE.keySet().iterator().next());
            return data;
        } catch (IOException error) {
            throw new IllegalStateException("客户地址数据不可用", error);
        }
    }
}
