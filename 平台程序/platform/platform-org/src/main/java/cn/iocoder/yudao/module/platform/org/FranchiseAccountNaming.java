package cn.iocoder.yudao.module.platform.org;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 固定品牌和地区规则；拼音数据随制品发布，不依赖在线翻译或额外拼音引擎。 */
@Component
public class FranchiseAccountNaming {
    private static final Map<String, String> PROVINCES = Map.ofEntries(
            Map.entry("北京", "bj"), Map.entry("天津", "tj"), Map.entry("河北", "hb"),
            Map.entry("山西", "sx"), Map.entry("内蒙古", "nmg"), Map.entry("辽宁", "ln"),
            Map.entry("吉林", "jl"), Map.entry("黑龙江", "hlj"), Map.entry("上海", "sh"),
            Map.entry("江苏", "js"), Map.entry("浙江", "zj"), Map.entry("安徽", "ah"),
            Map.entry("福建", "fj"), Map.entry("江西", "jx"), Map.entry("山东", "sd"),
            Map.entry("河南", "hn"), Map.entry("湖北", "hb"), Map.entry("湖南", "hn"),
            Map.entry("广东", "gd"), Map.entry("广西", "gx"), Map.entry("海南", "hn"),
            Map.entry("重庆", "cq"), Map.entry("四川", "sc"), Map.entry("贵州", "gz"),
            Map.entry("云南", "yn"), Map.entry("西藏", "xz"), Map.entry("陕西", "sx"),
            Map.entry("甘肃", "gs"), Map.entry("青海", "qh"), Map.entry("宁夏", "nx"),
            Map.entry("新疆", "xj"), Map.entry("香港", "xg"), Map.entry("澳门", "am"),
            Map.entry("台湾", "tw"));
    private static final Map<String, String> CITY_CODES = Map.ofEntries(
            Map.entry("重庆", "cq"), Map.entry("长春", "cc"), Map.entry("长沙", "cs"),
            Map.entry("厦门", "xm"), Map.entry("六安", "la"), Map.entry("蚌埠", "bb"),
            Map.entry("朝阳", "cy"), Map.entry("乐山", "ls"), Map.entry("乐清", "yq"));
    private static final Map<Integer, String> PINYIN = loadPinyin();
    private static final Set<String> SYLLABLES = Set.copyOf(PINYIN.values());

    public record Description(String brand, String province, String city, String area, String ownerName,
                              String orgName, String baseLogin, String regionCode,
                              String areaPinyin, String ownerPinyin, String areaInitials, String ownerInitials) {
        public List<String> aliases(String loginName) {
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(loginName);
            aliases.add(loginName.substring("hjmz-".length()));
            aliases.add(baseLogin);
            aliases.add(baseLogin.substring("hjmz-".length()));
            aliases.add(regionCode + "-" + areaPinyin + "-" + ownerPinyin);
            aliases.add(regionCode + "-" + areaInitials + "-" + ownerInitials);
            aliases.add(ownerPinyin);
            aliases.add(ownerInitials);
            aliases.add(ownerName.toLowerCase(Locale.ROOT));
            aliases.add(orgName.toLowerCase(Locale.ROOT));
            return List.copyOf(aliases);
        }
    }

    public record Person(String name, String pinyin, String initials) { }

    public Person person(String name) {
        String cleaned = clean(name, "员工姓名", 20);
        return new Person(cleaned, pinyinOrOverride(cleaned, null), initials(cleaned));
    }

    public Description describe(String brand, String province, String city, String area, String ownerName,
                                String areaOverride, String ownerOverride) {
        if (!"合家美宅".equals(brand)) throw new IllegalArgumentException("请选择已配置的品牌");
        String p = trimSuffix(clean(province, "省份", 30), "特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区", "自治区", "省", "市");
        String c = trimSuffix(clean(city, "城市", 30), "自治州", "地区", "市");
        if (List.of("北京", "上海", "天津", "重庆").contains(p) && ("市辖区".equals(city) || "县".equals(c))) c = p;
        String a = trimSuffix(clean(area, "门店片区", 20), "区", "县", "市");
        String owner = clean(ownerName, "老板姓名", 20);
        String provinceCode = PROVINCES.get(p);
        if (provinceCode == null) throw new IllegalArgumentException("请选择有效省份");
        String cityCode = CITY_CODES.containsKey(c) ? CITY_CODES.get(c) : initials(c);
        String regionCode = p.equals(c) ? provinceCode : provinceCode + cityCode;
        String areaPinyin = pinyinOrOverride(a, areaOverride);
        String ownerPinyin = pinyinOrOverride(owner, ownerOverride);
        String orgName = brand + "-" + p + (p.equals(c) ? "" : c) + "-" + a + "-" + owner;
        if (orgName.length() > 30) throw new IllegalArgumentException("加盟商名称超过30字，请精简门店片区名称");
        String baseLogin = "hjmz-" + regionCode + "-" + areaPinyin;
        if (baseLogin.length() + ownerPinyin.length() + 5 > 100) throw new IllegalArgumentException("账号拼音过长，请精简片区拼音");
        return new Description(brand, p, c, a, owner, orgName, baseLogin, regionCode,
                areaPinyin, ownerPinyin, correctedInitials(a, areaOverride), correctedInitials(owner, ownerOverride));
    }

    private static String clean(String value, String label, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > max || text.codePoints().anyMatch(c ->
                !(Character.isIdeographic(c) || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'))) {
            throw new IllegalArgumentException(label + "须为1至" + max + "个汉字、字母或数字");
        }
        return text;
    }

    private static String trimSuffix(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private static String pinyinOrOverride(String value, String override) {
        if (override != null && !override.isBlank()) {
            String result = override.trim().toLowerCase(Locale.ROOT);
            if (result.length() > 80 || result.chars().anyMatch(c -> c < 'a' || c > 'z')) {
                throw new IllegalArgumentException("校正拼音只能填写不带声调的小写字母");
            }
            return result;
        }
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(c -> result.append(syllable(c)));
        return result.toString();
    }

    private static String initials(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(c -> result.append(syllable(c).charAt(0)));
        return result.toString();
    }

    private static String correctedInitials(String source, String override) {
        if (override == null || override.isBlank() || !source.codePoints().allMatch(Character::isIdeographic)) {
            return initials(source);
        }
        // 校正全拼也必须校正简称；优先保留原字读音边界，避免“西安 xian”被拆成 xia+n。
        int[] characters = source.codePoints().toArray();
        String result = splitInitials(characters, 0, override.trim().toLowerCase(Locale.ROOT), 0, new java.util.HashSet<>());
        if (result == null) throw new IllegalArgumentException("校正拼音须包含每个汉字的完整读音，请核对姓名或片区拼音");
        return result;
    }

    private static String splitInitials(int[] characters, int index, String text, int offset, Set<Long> failed) {
        if (index == characters.length) return offset == text.length() ? "" : null;
        long state = ((long) index << 32) | offset;
        if (failed.contains(state) || offset >= text.length()) return null;
        var choices = new LinkedHashSet<String>();
        String preferred = PINYIN.get(characters[index]);
        if (preferred != null && text.startsWith(preferred, offset)) choices.add(preferred);
        for (int length = Math.min(6, text.length() - offset); length > 0; length--) {
            String syllable = text.substring(offset, offset + length);
            if (SYLLABLES.contains(syllable)) choices.add(syllable);
        }
        for (String syllable : choices) {
            String rest = splitInitials(characters, index + 1, text, offset + syllable.length(), failed);
            if (rest != null) return syllable.charAt(0) + rest;
        }
        failed.add(state);
        return null;
    }

    private static String syllable(int c) {
        if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') return Character.toString(c);
        if (c >= 'A' && c <= 'Z') return Character.toString(c + 'a' - 'A');
        String syllable = PINYIN.get(c);
        if (syllable == null || syllable.isBlank()) throw new IllegalArgumentException("部分文字无法生成拼音，请校正片区或姓名拼音");
        return syllable;
    }

    private static Map<Integer, String> loadPinyin() {
        var stream = FranchiseAccountNaming.class.getResourceAsStream("/franchise-pinyin.tsv");
        if (stream == null) throw new IllegalStateException("加盟商拼音字典未随制品发布");
        Map<Integer, String> map = new java.util.HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", 2);
                map.put(Integer.parseInt(parts[0], 16), parts[1]);
            }
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("加盟商拼音字典无法读取", e);
        }
        return Map.copyOf(map);
    }
}
