package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.platform.workflow.TodoList;
import cn.iocoder.yudao.module.platform.workflow.TodoService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageCenterReadSnapshotIntegrationTest extends PlatformPgIntegrationTest {
    @Resource JdbcTemplate jdbc;
    @Resource DataSource dataSource;

    @Test void bulkReadOnlyTouchesNotificationsVisibleWhenTheListWasRequested() throws Exception {
        long id=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        var post=new PostSnapshot(id,id,id,"franchise","sales",null,false,Set.of());
        var otherOrg=new PostSnapshot(id+8,id,id+8,"franchise","sales",null,false,Set.of());
        var todos=mock(TodoService.class);
        when(todos.list(post)).thenReturn(new TodoList(List.of(),0,List.of()));
        var service=new MessageCenterService(jdbc,todos,List.of());
        try(Connection connection=dataSource.getConnection()) {
            insert(connection,id,post);
            insert(connection,id+2,otherOrg);
            connection.setAutoCommit(false);
            // The late transaction began before the list and inserted a lower, backdated ID.
            insert(connection,id-1,post);
            var page=service.list(post,new MessageCenterService.Query(null,null,null,"ALL",null,30));
            assertThat(page.items()).extracting(row->row.get("id")).containsExactly(Long.toString(id));
            var cutoff=OffsetDateTime.parse(page.snapshotAt());
            connection.commit();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.readAll(otherOrg,cutoff))
                .hasMessage("消息列表已过期，请刷新后重试");
            assertThat(service.readAll(post,cutoff)).isEqualTo(1);
            assertThat(service.readAll(post,cutoff)).isZero();
            jdbc.update("UPDATE pf_message_read_snapshot SET expires_at=? WHERE user_id=? AND org_id=? AND snapshot_at=?",
                OffsetDateTime.now().minusMinutes(1),post.userId(),post.orgId(),cutoff);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.readAll(post,cutoff))
                .hasMessage("消息列表已过期，请刷新后重试");
            assertThat(jdbc.queryForObject("SELECT read FROM pf_message WHERE id=?",Boolean.class,id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT read FROM pf_message WHERE id=?",Boolean.class,id-1)).isFalse();
            assertThat(jdbc.queryForObject("SELECT read FROM pf_message WHERE id=?",Boolean.class,id+2)).isFalse();
        }
    }

    private static void insert(Connection connection,long id,PostSnapshot recipient) throws Exception {
        try(var statement=connection.prepareStatement("""
            INSERT INTO pf_message(id,user_id,org_id,sender_domain,type,route,title,description,dedupe_key,received_at)
            VALUES (?,?,?,'snapshot-test','BUSINESS','customer','Notice','',?,now()-INTERVAL '1 day')
            """)) {
            statement.setLong(1,id);
            statement.setLong(2,recipient.userId());
            statement.setLong(3,recipient.orgId());
            statement.setString(4,Long.toString(id));
            statement.executeUpdate();
        }
    }
}
