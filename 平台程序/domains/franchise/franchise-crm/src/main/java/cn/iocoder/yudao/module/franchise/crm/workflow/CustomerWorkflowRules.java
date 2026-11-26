package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.SaveCommand;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Step;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Version 1's explicit evidence checks; saving a draft deliberately does not run completion checks. */
public final class CustomerWorkflowRules {
    private CustomerWorkflowRules() { }
    private static final Map<Step, Set<String>> FIELDS = Map.of(
            Step.INVITATION, Set.of("contactMethod", "invitationResult", "appointmentAt", "nextContactOn"),
            Step.INTRODUCTION, Set.of("receptionist", "requirements", "feedback"),
            Step.HOUSE_SELECTION, Set.of("referenceCaseId", "referenceCaseName", "selectionResult", "versionLabel", "confirmed"),
            Step.SITE_VISIT, Set.of("siteName", "address", "feedback"),
            Step.MEASUREMENT, Set.of("address", "measurer", "width", "depth", "area", "floors", "checked"),
            Step.QUOTATION, Set.of("quoteId", "amountCents", "scope", "versionLabel", "communicated", "result"));
    private static final Set<String> BOOLEAN_FIELDS = Set.of("confirmed", "checked", "communicated");
    private static final Set<String> DECIMAL_FIELDS = Set.of("width", "depth", "area");
    private static final Set<String> QUOTE_SNAPSHOT_FIELDS = Set.of("amountCents", "scope", "versionLabel");

    public static SaveCommand normalize(Step step, SaveCommand input) {
        if (!step.manual() || input == null || input.revision() < 0) throw invalid("跟进记录参数无效");
        if (input.occurredOn() != null && input.occurredOn().isAfter(today())) throw invalid("发生日期不能晚于今天");
        Map<String, Object> fields = new TreeMap<>();
        Map<String, Object> source = input.fields() == null ? Map.of() : input.fields();
        boolean linkedQuote = step == Step.QUOTATION && present(source, "quoteId");
        for (var entry : source.entrySet()) {
            String key = entry.getKey();
            if (!FIELDS.get(step).contains(key)) throw invalid("不支持的步骤字段：" + key);
            if (linkedQuote && QUOTE_SNAPSHOT_FIELDS.contains(key)) continue;
            Object value = entry.getValue();
            if (value == null || value instanceof String text && text.isBlank()) continue;
            if (BOOLEAN_FIELDS.contains(key)) {
                if (!(value instanceof Boolean)) throw invalid("确认字段格式无效");
                fields.put(key, value);
            } else if (DECIMAL_FIELDS.contains(key)) {
                BigDecimal decimal = decimal(value, key);
                if (decimal.signum() < 0 || decimal.compareTo(new BigDecimal("1000000")) > 0 || decimal.scale() > 2)
                    throw invalid("尺寸或面积超出允许范围");
                fields.put(key, decimal.stripTrailingZeros().toPlainString());
            } else if ("amountCents".equals(key) || "floors".equals(key) || "referenceCaseId".equals(key) || "quoteId".equals(key)) {
                long number = integer(value, key);
                if ("quoteId".equals(key) && number <= 0) throw invalid("报价原单编号无效");
                if (number < 0 || "amountCents".equals(key) && number > 9_000_000_000_000L
                        || "floors".equals(key) && number > 200) throw invalid("数值超出允许范围");
                fields.put(key, Long.toString(number));
            } else {
                if (!(value instanceof String text)) throw invalid("文本字段格式无效");
                fields.put(key, text(text, key.equals("requirements") || key.equals("feedback") ? 4000 : 1000));
            }
        }
        if (fields.containsKey("invitationResult") && !Set.of("CONFIRMED", "PENDING", "DECLINED").contains(fields.get("invitationResult")))
            throw invalid("邀约结果无效");
        if (fields.containsKey("appointmentAt")) {
            try { fields.put("appointmentAt", LocalDateTime.parse(String.valueOf(fields.get("appointmentAt"))).toString()); }
            catch (RuntimeException error) { throw invalid("约定到店时间格式无效"); }
        }
        if (fields.containsKey("nextContactOn")) {
            try { fields.put("nextContactOn", LocalDate.parse(String.valueOf(fields.get("nextContactOn"))).toString()); }
            catch (RuntimeException error) { throw invalid("下次联系日期格式无效"); }
        }
        List<Attachment> attachments = new ArrayList<>();
        Set<Long> ids = new java.util.HashSet<>();
        for (Attachment attachment : input.attachments() == null ? List.<Attachment>of() : input.attachments()) {
            if (attachment == null) throw invalid("附件参数无效");
            long id = integer(attachment.assetId(), "assetId");
            if (id <= 0 || !ids.add(id)) throw invalid("附件编号无效或重复");
            attachments.add(new Attachment(Long.toString(id), text(attachment.title(), 200)));
        }
        if (attachments.size() > 20) throw invalid("每个步骤最多保留20个附件");
        return new SaveCommand(input.revision(), input.occurredOn(), text(input.note(), 4000),
                new LinkedHashMap<>(fields), List.copyOf(attachments));
    }

    public static List<String> missing(Step step, LocalDate date, Map<String, Object> fields, List<Attachment> attachments) {
        List<String> errors = new ArrayList<>();
        if (date == null) errors.add("请记录实际发生日期");
        else if (date.isAfter(today())) errors.add("发生日期不能晚于今天");
        switch (step) {
            case INVITATION -> {
                required(errors, fields, "contactMethod", "联系渠道");
                if (!"CONFIRMED".equals(fields.get("invitationResult"))) errors.add("客户确认邀约后才能完成本步");
                required(errors, fields, "appointmentAt", "约定到店时间");
            }
            case INTRODUCTION -> {
                required(errors, fields, "receptionist", "接待人"); required(errors, fields, "requirements", "客户需求");
                required(errors, fields, "feedback", "讲解反馈");
            }
            case HOUSE_SELECTION -> {
                required(errors, fields, "selectionResult", "选型结果");
                if (!Boolean.TRUE.equals(fields.get("confirmed"))) errors.add("请确认客户的选型结果");
                if (!present(fields, "referenceCaseId") && attachments.isEmpty()) errors.add("请选择参考户型或上传外部方案");
            }
            case SITE_VISIT -> { required(errors, fields, "siteName", "工地名称"); required(errors, fields, "feedback", "客户反馈"); }
            case MEASUREMENT -> {
                required(errors, fields, "address", "实测地址"); required(errors, fields, "measurer", "测量人");
                positive(errors, fields, "width", "面宽"); positive(errors, fields, "depth", "进深"); positive(errors, fields, "area", "面积");
                if (!Boolean.TRUE.equals(fields.get("checked"))) errors.add("请核对测量数据");
                if (attachments.isEmpty()) errors.add("请上传测量资料");
            }
            case QUOTATION -> {
                positive(errors, fields, "amountCents", "报价金额");
                required(errors, fields, "versionLabel", "报价版本"); required(errors, fields, "result", "沟通结果");
                if (!Boolean.TRUE.equals(fields.get("communicated"))) errors.add("向客户发送或说明正式报价后才能完成本步");
                if (!present(fields, "quoteId")) {
                    required(errors, fields, "scope", "报价范围");
                    if (attachments.isEmpty()) errors.add("请关联正式报价原单，或上传外部正式报价文件");
                }
            }
            default -> throw invalid("该步骤由正式业务事实更新");
        }
        return List.copyOf(errors);
    }

    public static String reason(String value) {
        String reason = text(value, 1000);
        if (reason.isBlank()) throw invalid("请填写变更原因");
        return reason;
    }
    public static String text(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) throw invalid("内容超过允许长度");
        return text;
    }
    public static long integer(Object value, String field) {
        try { return decimal(value, field).longValueExact(); }
        catch (ArithmeticException error) { throw invalid(field + "必须是整数"); }
    }
    private static BigDecimal decimal(Object value, String field) {
        if (!(value instanceof Number) && !(value instanceof String)) throw invalid(field + "格式无效");
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException error) { throw invalid(field + "格式无效"); }
    }
    private static boolean present(Map<String, Object> fields, String key) { return fields.get(key) != null && !String.valueOf(fields.get(key)).isBlank(); }
    private static void required(List<String> errors, Map<String, Object> fields, String key, String label) { if (!present(fields, key)) errors.add("请填写" + label); }
    private static void positive(List<String> errors, Map<String, Object> fields, String key, String label) {
        if (!present(fields, key) || decimal(fields.get(key), key).signum() <= 0) errors.add("请填写有效的" + label);
    }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    public static ServiceException invalid(String message) { return new ServiceException(400, message); }
}
