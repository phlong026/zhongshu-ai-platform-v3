package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.util.List;

/**
 * 让游标查询也按组织范围过滤。
 *
 * <p>MyBatis-Plus 3.5.17 的 {@code BaseMapper.selectOne} 改走游标查询（映射语句 {@code selectWithCursor}），
 * 而 {@code MybatisPlusInterceptor} 只拦截 {@code Executor.query/update}，数据权限的 beforeQuery 不会执行，
 * 业务代码用 selectOne 就会越过组织隔离。游标查询仍会经过语句准备阶段（beforePrepare），在这里补上同样的条件。
 *
 * <p>普通查询已由 yudao 的数据权限拦截器处理：本拦截器在 beforeQuery 给 BoundSql 打标记，准备阶段见到标记即跳过，避免重复追加。
 * 只挂 {@link OrgScopeRule}，不改变 yudao 自带部门数据权限的现有行为；忽略数据权限（executeIgnore）同样生效。
 */
final class CursorQueryOrgScope implements InnerInterceptor {

    private static final String HANDLED = CursorQueryOrgScope.class.getName() + ".handled";

    private final DataPermissionInterceptor scope;

    CursorQueryOrgScope(OrgScopeRule rule) {
        this.scope = new DataPermissionInterceptor(new DataPermissionRuleHandler(new DataPermissionRuleFactoryImpl(List.of(rule))));
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds,
                            ResultHandler resultHandler, BoundSql boundSql) {
        boundSql.setAdditionalParameter(HANDLED, Boolean.TRUE);
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = mpSh.mappedStatement();
        BoundSql boundSql = mpSh.boundSql();
        if (ms.getSqlCommandType() != SqlCommandType.SELECT || boundSql.hasAdditionalParameter(HANDLED)) {
            return;
        }
        try {
            scope.beforeQuery(null, ms, null, null, null, boundSql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("组织范围条件追加失败：" + ms.getId(), e);
        }
    }

}
