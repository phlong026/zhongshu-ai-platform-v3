package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRule;
import cn.iocoder.yudao.framework.mybatis.core.util.MyBatisUtils;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按当前工作身份所在组织自动过滤业务表（查询、更新、删除都生效）。
 *
 * <p>登记的表追加 {@code (col1 = 当前组织 OR col2 = 当前组织 ...)}；没有工作身份时追加恒假条件，返回空。
 * 后台任务等确需跨组织时，用 yudao 的 {@code DataPermissionUtils.executeIgnore(...)} 显式绕过，并自行写审计。
 *
 * <p>注意：yudao 的数据翻译（easy-trans）调用会跳过全部数据权限，业务域不要用它加载他组织数据。
 */
@Component
public class OrgScopeRule implements DataPermissionRule {

    private final CurrentPost currentPost;
    private final Map<String, List<String>> columnsByTable = new HashMap<>();

    public OrgScopeRule(CurrentPost currentPost, ObjectProvider<OrgScopeCustomizer> customizers) {
        this.currentPost = currentPost;
        customizers.orderedStream().forEach(c -> c.customize(this));
    }

    /** 登记表及其组织列；第一列为归属组织，其余为额外可见组织（如委托给总部的 design_org_id） */
    public void register(String table, String... orgColumns) {
        if (orgColumns.length == 0) {
            throw new IllegalArgumentException("至少需要一个组织列：" + table);
        }
        columnsByTable.put(table, List.of(orgColumns));
    }

    @Override
    public Set<String> getTableNames() {
        return Collections.unmodifiableSet(columnsByTable.keySet());
    }

    @Override
    public Expression getExpression(String tableName, Alias tableAlias) {
        List<String> columns = columnsByTable.get(tableName);
        if (columns == null) {
            return null;
        }
        Optional<PostSnapshot> post = currentPost.find();
        if (post.isEmpty()) {
            return new EqualsTo(new NullValue(), new NullValue()); // WHERE NULL = NULL，恒假
        }
        LongValue orgId = new LongValue(post.get().orgId());
        Expression result = null;
        for (String column : columns) {
            Expression eq = new EqualsTo(MyBatisUtils.buildColumn(tableName, tableAlias, column), orgId);
            result = result == null ? eq : new OrExpression(result, eq);
        }
        return columns.size() == 1 ? result : new ParenthesedExpressionList<>(result);
    }

}
