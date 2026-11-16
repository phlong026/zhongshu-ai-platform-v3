package cn.iocoder.yudao.module.design.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Exact original mini-program rules. Keep the newer workbook calculations independent. */
public final class OriginalBudgetRules {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private OriginalBudgetRules() { }
    public static BudgetInputs.Resolved resolve(Map<String,Object> imported,Map<String,Object> overrides) {
        var original=cn.iocoder.yudao.module.design.budget.original.BudgetInputs.resolve(
                cn.iocoder.yudao.module.design.budget.original.BudgetInputs.fromSnapshot(imported),overrides);
        return new BudgetInputs.Resolved(original.values(),original.sources(),original.quantities(),original.missingFields(),original.warnings());
    }
    private static cn.iocoder.yudao.module.design.budget.original.BudgetInputs.Resolved original(BudgetInputs.Resolved input) {
        return new cn.iocoder.yudao.module.design.budget.original.BudgetInputs.Resolved(input.values(),input.sources(),input.quantities(),input.missingFields(),input.warnings());
    }
    public static BudgetCalculator.Quantity resolveQuantity(BudgetInputs.Resolved input,String unit,String source,String key) {
        var quantity=cn.iocoder.yudao.module.design.budget.original.BudgetCalculator.resolveQuantity(original(input),unit,source,key);
        return new BudgetCalculator.Quantity(quantity.value(),quantity.source());
    }
    public static BudgetCalculator.Result calculate(BudgetInputs.Resolved input,List<BudgetCalculator.LineInput> lines) {
        var originalLines=lines.stream().map(line->MAPPER.convertValue(line,cn.iocoder.yudao.module.design.budget.original.BudgetCalculator.LineInput.class)).toList();
        var result=cn.iocoder.yudao.module.design.budget.original.BudgetCalculator.calculate(original(input),originalLines);
        return MAPPER.convertValue(result,BudgetCalculator.Result.class);
    }
}
