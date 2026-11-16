package cn.iocoder.yudao.module.design.budget;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** The frozen migration kernel retains the recorded original source, apart from its Java package. */
class OriginalBudgetSourceBaselineTest {
 @Test void originalBudgetRulesRetainTheRecordedSourceBaseline() throws Exception {
  for(var entry:Map.ofEntries(Map.entry("BudgetInputs.java","7c937f27fbea98cc9ba6e87e013d25c359d5ae898bd05121e99bd7c4a4d4da6a"),Map.entry("BudgetCalculator.java","35f853bf7b20b684571f0f29b6ec622a65594871b39e7fa088a19ef91a483413")).entrySet()) {
   String source=Files.readString(Path.of("src/main/java/cn/iocoder/yudao/module/design/budget/original",entry.getKey()),StandardCharsets.UTF_8)
    .replace("\r\n","\n").replace("package cn.iocoder.yudao.module.design.budget.original;","package cn.iocoder.yudao.module.design.budget;");
   assertThat(cn.hutool.crypto.digest.DigestUtil.sha256Hex(source)).as(entry.getKey()).isEqualTo(entry.getValue());
  }
 }
}
