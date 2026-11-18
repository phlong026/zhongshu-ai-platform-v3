package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.MESSAGE_NOT_FOUND;

/** Organization-scoped inbox. Reading messages never completes or mutates business tasks. */
@Service
public class MessageCenterService {
    private static final com.fasterxml.jackson.databind.ObjectMapper STORED_JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TodoService todos;
    private final List<MessageCenterResolver> resolvers;
    @Autowired
    public MessageCenterService(JdbcTemplate jdbc, TodoService todos, ObjectProvider<MessageCenterResolver> resolvers) {
        this(jdbc,todos,resolvers.orderedStream().toList());
    }
    public MessageCenterService(JdbcTemplate jdbc, TodoService todos, List<MessageCenterResolver> resolvers) {
        this.jdbc=jdbc; this.todos=todos; this.resolvers=List.copyOf(resolvers);
    }
    public record Query(String type,String module,String keyword,String mode,Long beforeId,Integer limit) { }
    public record Page(List<Map<String,Object>> items,long unread,int pending,boolean hasMore,String nextCursor,
                       List<String> failedDomains,String snapshotAt) { }

    public Page list(PostSnapshot post, Query query) {
        TodoList work=visibleWork(post);
        OffsetDateTime snapshot = query.beforeId() == null ? captureReadSnapshot(post) : null;
        int limit=query.limit()==null?30:Math.max(1,Math.min(100,query.limit()));
        List<Map<String,Object>> items;
        boolean more=false;String cursor="";
        if("PENDING".equals(query.mode())) {
            items=work.items().stream().filter(TodoItem::actionable).map(todo->todo(post,todo))
                .filter(row->matches(row,query)).toList();
        } else {
            StringBuilder sql=new StringBuilder("SELECT id::text,user_id,org_id,type,route,object_id::text,title,description,read,create_time,business_context::text,dedupe_key FROM pf_message WHERE user_id=? AND org_id=?");
            List<Object> args=new ArrayList<>(List.of(post.userId(),post.orgId()));
            if(query.beforeId()!=null){sql.append(" AND id<?");args.add(query.beforeId());}
            if(Set.of("BUSINESS","LEARNING","SYSTEM").contains(text(query.type()))){sql.append(" AND type=?");args.add(query.type());}
            if(!text(query.module()).isBlank()) {
                sql.append(" AND COALESCE(business_context->>'module',CASE WHEN route IN('commission','design-progress','design-dispatch') THEN 'design' WHEN route='commission-payment' THEN 'finance' WHEN route='customer' THEN 'customer' WHEN route='content-task' THEN 'creation' WHEN route='course' THEN 'learning' WHEN route='personal-performance' THEN 'performance' ELSE 'system' END)=?");args.add(query.module());
            }
            if("UNREAD".equals(query.mode()))sql.append(" AND read=FALSE");
            if(!text(query.keyword()).isBlank()) {
                // strpos gives literal, parameterized matching (%, _ and backslash are not wildcards).
                sql.append(" AND strpos(lower(concat_ws(' ',title,description,object_id::text,business_context::text)),lower(?))>0");
                args.add(query.keyword().trim().substring(0,Math.min(100,query.keyword().trim().length())));
            }
            sql.append(" ORDER BY pf_message.id DESC LIMIT ?");args.add(limit+1);
            var rows=jdbc.queryForList(sql.toString(),args.toArray());more=rows.size()>limit;
            items=new ArrayList<>();
            for(var row:rows.subList(0,Math.min(limit,rows.size())))items.add(message(post,row,work.items()));
            if(!items.isEmpty())cursor=text(items.get(items.size()-1).get("id"));
        }
        return new Page(items,unread(post),work.actionableCount(),more,cursor,work.failedDomains(),
                snapshot == null ? null : snapshot.toString());
    }
    private TodoList visibleWork(PostSnapshot post){
        TodoList all=todos.list(post);
        var items=all.items().stream().filter(t->resolvers.stream().allMatch(r->r.allowTodo(post,t))).toList();
        return new TodoList(items,(int)items.stream().filter(TodoItem::actionable).count(),all.failedDomains());
    }
    public long unread(PostSnapshot post) {
        return jdbc.queryForObject("SELECT count(*) FROM pf_message WHERE user_id=? AND org_id=? AND read=FALSE",Long.class,post.userId(),post.orgId());
    }
    public Map<String,Object> open(PostSnapshot post,String id) {
        TodoList work=visibleWork(post);
        if(id.startsWith("todo:")) {
            var found=work.items().stream().filter(t->t.actionable()&&id.equals("todo:"+t.key())).findFirst();
            if(found.isEmpty())throw exception(MESSAGE_NOT_FOUND);
            return todo(post,found.get());
        }
        long numeric;try{numeric=Long.parseLong(id);}catch(RuntimeException e){throw exception(MESSAGE_NOT_FOUND);}
        var rows=jdbc.queryForList("SELECT id::text,user_id,org_id,type,route,object_id::text,title,description,read,create_time,business_context::text,dedupe_key FROM pf_message WHERE id=? AND user_id=? AND org_id=?",numeric,post.userId(),post.orgId());
        if(rows.isEmpty())throw exception(MESSAGE_NOT_FOUND);
        var item=message(post,rows.get(0),work.items());
        jdbc.update("UPDATE pf_message SET read=TRUE,read_time=COALESCE(read_time,now()) WHERE id=? AND user_id=? AND org_id=? AND read=FALSE",numeric,post.userId(),post.orgId());
        item.put("read",true);
        item.put("unread",unread(post));
        return item;
    }
    private OffsetDateTime captureReadSnapshot(PostSnapshot post) {
        jdbc.update("DELETE FROM pf_message_read_snapshot WHERE expires_at<=clock_timestamp()");
        return jdbc.queryForObject("""
            INSERT INTO pf_message_read_snapshot (user_id,org_id,snapshot_at,message_ids)
            SELECT ?,?,clock_timestamp(),COALESCE(array_agg(id),'{}'::bigint[])
            FROM pf_message WHERE user_id=? AND org_id=? AND read=FALSE
            ON CONFLICT (user_id,org_id,snapshot_at) DO UPDATE SET
                message_ids=pf_message_read_snapshot.message_ids
            RETURNING snapshot_at
            """, (rs, row) -> rs.getObject(1, OffsetDateTime.class),
            post.userId(), post.orgId(), post.userId(), post.orgId());
    }

    public int readAll(PostSnapshot post, OffsetDateTime through) {
        if (through == null) return 0;
        return jdbc.update("""
            UPDATE pf_message m SET read=TRUE,read_time=COALESCE(m.read_time,now())
            FROM pf_message_read_snapshot s
            WHERE s.user_id=? AND s.org_id=? AND s.snapshot_at=? AND s.expires_at>clock_timestamp()
                AND m.user_id=s.user_id AND m.org_id=s.org_id AND NOT m.read
                AND m.id=ANY(s.message_ids)
            """, post.userId(), post.orgId(), through);
    }
    private Map<String,Object> message(PostSnapshot post,Map<String,Object> raw,List<TodoItem> work) {
        Map<String,Object> row=new LinkedHashMap<>();
        for(String k:List.of("id","type","route","title","description","read"))row.put(k,raw.get(k));
        row.put("objectId",raw.get("object_id"));row.put("createTime",raw.get("create_time"));
        row.put("context",json(raw.get("business_context")));row.put("dedupeKey",raw.get("dedupe_key"));
        row.put("actionable",false);row.put("kind","NOTICE");row.put("stateLabel","仅知会");
        for(var resolver:resolvers)resolver.resolve(post,row,work);
        row.remove("dedupeKey");return row;
    }
    private Map<String,Object> todo(PostSnapshot post,TodoItem todo) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("id","todo:"+todo.key());row.put("type","BUSINESS");row.put("title",todo.title());
        row.put("description","");row.put("read",true);row.put("kind","TODO");row.put("actionable",true);
        row.put("stateLabel",todo.status());row.put("createTime",todo.since());row.put("actionPath",todo.appPath());
        row.put("actionLabel","去办理");row.put("module",module(todo.domain()));
        Map<String,Object> context=new LinkedHashMap<>();context.put("todoKey",todo.key());context.put("domain",todo.domain());
        context.put("recipientReason","当前岗位负责办理");
        if(todo.amountCents()!=null)context.put("amountCents",String.valueOf(todo.amountCents()));
        row.put("context",context);
        for(var resolver:resolvers)resolver.resolveTodo(post,todo,row);
        return row;
    }
    static String module(String domain) {
        if(domain.contains("fin")||domain.contains("sale"))return "finance";
        if(domain.contains("eng"))return "engineering";
        if(domain.contains("perf"))return "performance";
        return "design";
    }
    private static boolean matches(Map<String,Object> row,Query q) {
        return (text(q.type()).isBlank()||q.type().equals(row.get("type")))
            &&(text(q.module()).isBlank()||q.module().equals(row.get("module")))
            &&(text(q.keyword()).isBlank()||JsonUtils.toJsonString(row).toLowerCase(Locale.ROOT).contains(q.keyword().trim().toLowerCase(Locale.ROOT)));
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> json(Object value) {
        if(value instanceof Map)return new LinkedHashMap<>((Map<String,Object>)value);
        if(value==null||String.valueOf(value).isBlank())return new LinkedHashMap<>();
        // Persisted JSON already passed write-side validation. Reusing the HTTP XSS mapper here
        // would turn internal route separators into &amp; on every read.
        String raw=String.valueOf(value).trim();if(!raw.startsWith("{"))return new LinkedHashMap<>();
        try{return new LinkedHashMap<>(STORED_JSON.readValue(raw,Map.class));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Invalid stored message context",e);}
    }
    public static String text(Object value){return value==null?"":String.valueOf(value);}
}
