package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

import java.util.Map;
import java.util.Set;

/** Reads only B asset references. Legacy URLs and arbitrary JSON are never returned or fetched. */
record HeadquartersDocumentReference(long assetId, Long commissionId, Integer dispatchSeq, String slot) {
    static HeadquartersDocumentReference parse(String text) {
        Long id = positiveLong(text);
        if (id != null) return new HeadquartersDocumentReference(id, null, null, null);
        Map<String,Object> value = JsonUtils.parseMap(text);
        if (value == null) return null;
        id = positiveLong(value.get("assetId"));
        Long commission = positiveLong(value.get("commissionId")), sequence = positiveLong(value.get("dispatchSeq"));
        String slot = value.get("slot") instanceof String s ? s : "";
        if (id == null || commission == null || sequence == null || sequence > Integer.MAX_VALUE
                || !Set.of("RESULT", "DRAWING", "REVIEW", "CONFIRMATION").contains(slot)) return null;
        return new HeadquartersDocumentReference(id, commission, sequence.intValue(), slot);
    }

    private static Long positiveLong(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) return null;
        String text = value.toString();
        if (!text.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.parseLong(text); } catch (NumberFormatException ignored) { return null; }
    }
}
