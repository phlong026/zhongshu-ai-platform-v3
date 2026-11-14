package cn.iocoder.yudao.module.franchise.design.publiccase;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** B's view of A's published catalog plus employee-private favorites stored only in B. */
public final class PublicCaseAccessService implements AutoCloseable {

    public static final int PUBLIC_CASE_UNAVAILABLE = 1_112_100_010;
    public static final int PUBLIC_CASE_INVALID_RESPONSE = 1_112_100_011;
    public static final int PUBLIC_CASE_BUSY = 1_112_100_012;
    public static final int PUBLIC_CASE_PAGE_TIMEOUT = 1_112_100_013;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final PublicCaseCatalogClient catalog;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final PublicCaseAdmission admission;
    private final Duration favoritePageTimeout;
    private final ThreadPoolExecutor hydrationExecutor;

    public PublicCaseAccessService(PublicCaseCatalogClient catalog, ObjectMapper mapper,
                                   DataSource dataSource, PublicCaseAdmission admission,
                                   Duration favoritePageTimeout, int hydrationConcurrency,
                                   int maxQueuedHydrations) {
        if (favoritePageTimeout == null || favoritePageTimeout.isZero() || favoritePageTimeout.isNegative()
                || favoritePageTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid favorite page timeout");
        }
        if (hydrationConcurrency < 1 || hydrationConcurrency > 16 || maxQueuedHydrations < 50
                || maxQueuedHydrations > 4096) {
            throw new IllegalArgumentException("Invalid favorite hydration bounds");
        }
        this.catalog = catalog;
        this.mapper = mapper;
        this.jdbc = new JdbcTemplate(dataSource);
        this.admission = admission;
        this.favoritePageTimeout = favoritePageTimeout;
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "public-case-hydration-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.hydrationExecutor = new ThreadPoolExecutor(hydrationConcurrency, hydrationConcurrency,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(maxQueuedHydrations), threads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public DesignServicePort.CasePage listCases(PostSnapshot post, DesignServicePort.CaseQuery query) {
        validatePageLimit(query.limit());
        Map<String, String> parameters = new LinkedHashMap<>();
        put(parameters, "styleCode", query.styleCode());
        put(parameters, "floorCount", query.floorCount());
        put(parameters, "faceWidth", query.faceWidth());
        put(parameters, "depth", query.depth());
        put(parameters, "keyword", query.keyword());
        put(parameters, "cursor", query.cursor());
        put(parameters, "limit", query.limit());
        JsonNode data = fromCatalog(tenant(), post, false, () -> catalog.listCases(parameters));
        if (!data.isObject() || !data.path("list").isArray()) throw invalidResponse();
        List<Map<String, Object>> list = new ArrayList<>();
        data.path("list").forEach(item -> {
            if (!item.isObject()) throw invalidResponse();
            list.add(hideUnavailableActions(mapper.convertValue(item, MAP)));
        });
        JsonNode next = data.get("nextCursor");
        if (next != null && !next.isNull() && !next.isTextual()) throw invalidResponse();
        return new DesignServicePort.CasePage(list, next == null || next.isNull() ? null : next.textValue());
    }

    public List<DesignServicePort.StyleOption> listCaseStyles(PostSnapshot post) {
        JsonNode data = fromCatalog(tenant(), post, false, catalog::listStyles);
        if (!data.isArray()) throw invalidResponse();
        List<DesignServicePort.StyleOption> styles = new ArrayList<>();
        data.forEach(item -> {
            if (!item.path("code").isTextual() || !item.path("label").isTextual()) throw invalidResponse();
            styles.add(new DesignServicePort.StyleOption(item.path("code").textValue(), item.path("label").textValue()));
        });
        return styles;
    }

    public DesignServicePort.CaseDetailView getCaseDetail(PostSnapshot post, long caseId) {
        return new DesignServicePort.CaseDetailView(detail(tenant(), post, caseId, null));
    }

    public void favoriteCase(PostSnapshot post, long caseId) {
        long tenantId = tenant();
        // A remains the publication authority. A successful anonymous detail read is required before B stores a link.
        detail(tenantId, post, caseId, null);
        jdbc.update("""
                INSERT INTO fr_design_case_favorite
                    (id,tenant_id,org_id,employee_user_id,external_case_id)
                VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id,org_id,employee_user_id,external_case_id) DO NOTHING
                """, IdWorker.getId(), tenantId, post.orgId(), post.userId(), caseId);
    }

    public void unfavoriteCase(PostSnapshot post, long caseId) {
        requireCaseId(caseId);
        // Local deletion must remain available when A is unavailable or has already unpublished the case.
        jdbc.update("""
                DELETE FROM fr_design_case_favorite
                 WHERE tenant_id=? AND org_id=? AND employee_user_id=? AND external_case_id=?
                """, tenant(), post.orgId(), post.userId(), caseId);
    }

    public DesignServicePort.CasePage listFavorites(PostSnapshot post, String cursor, int limit) {
        validatePageLimit(limit);
        long deadline = System.nanoTime() + favoritePageTimeout.toNanos();
        long tenantId = tenant();
        Long before = decodeCursor(cursor);
        List<Map<String, Object>> rows = before == null
                ? jdbc.queryForList("""
                        SELECT id,external_case_id FROM fr_design_case_favorite
                         WHERE tenant_id=? AND org_id=? AND employee_user_id=?
                         ORDER BY id DESC LIMIT ?
                        """, tenantId, post.orgId(), post.userId(), limit + 1)
                : jdbc.queryForList("""
                        SELECT id,external_case_id FROM fr_design_case_favorite
                         WHERE tenant_id=? AND org_id=? AND employee_user_id=? AND id<?
                         ORDER BY id DESC LIMIT ?
                        """, tenantId, post.orgId(), post.userId(), before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Map<String, Object>> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<Map<String, Object>> cases = hydrateFavorites(tenantId, post, pageRows, deadline);
        String next = hasMore ? encodeCursor(((Number) pageRows.get(pageRows.size() - 1).get("id")).longValue()) : null;
        return new DesignServicePort.CasePage(cases, next);
    }

    private Map<String, Object> detail(long tenantId, PostSnapshot post, long caseId, Duration remaining) {
        requireCaseId(caseId);
        JsonNode data = fromCatalog(tenantId, post, remaining != null,
                () -> remaining == null ? catalog.getCase(caseId) : catalog.getCase(caseId, remaining));
        if (!data.isObject()) throw invalidResponse();
        Map<String, Object> fields = mapper.convertValue(data, MAP);
        if (!sameCase(fields.get("caseId"), caseId)) throw invalidResponse();
        return hideUnavailableActions(fields);
    }

    /** B cannot materialize A's case assets yet, so it must not advertise that action as available. */
    private static Map<String, Object> hideUnavailableActions(Map<String, Object> fields) {
        Object actions = fields.get("allowedActions");
        if (!(actions instanceof List<?> values)) return fields;
        fields.put("allowedActions", values.stream()
                .filter(action -> !"DESIGN_WITH".equals(action))
                .toList());
        return fields;
    }

    private Map<String, Object> toSummary(Map<String, Object> detail) {
        Map<String, Object> summary = new LinkedHashMap<>();
        copy(detail, summary, "caseId", "title", "sourceType", "coverAssetId", "uploaderName",
                "uploaderCompany", "allowedActions");
        Object parameters = detail.get("parameters");
        if (parameters instanceof Map<?, ?> values) {
            copy(values, summary, "styleCode", "floorCount", "buildingArea", "faceWidth", "depth");
        } else {
            copy(detail, summary, "styleCode", "floorCount", "buildingArea", "faceWidth", "depth");
        }
        return summary;
    }

    private List<Map<String, Object>> hydrateFavorites(long tenantId, PostSnapshot post,
                                                       List<Map<String, Object>> rows, long deadline) {
        if (rows.isEmpty()) return List.of();
        List<Future<Map<String, Object>>> futures = new ArrayList<>(rows.size());
        boolean succeeded = false;
        try {
            for (Map<String, Object> row : rows) {
                long caseId = ((Number) row.get("external_case_id")).longValue();
                futures.add(hydrationExecutor.submit(() ->
                        toSummary(detail(tenantId, post, caseId, remaining(deadline)))));
            }
            List<Map<String, Object>> cases = new ArrayList<>(futures.size());
            for (Future<Map<String, Object>> future : futures) {
                cases.add(future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS));
            }
            succeeded = true;
            return cases;
        } catch (RejectedExecutionException busy) {
            throw new ServiceException(PUBLIC_CASE_BUSY, "公开案例服务繁忙，请稍后重试");
        } catch (TimeoutException | PageDeadlineExpired timeout) {
            throw pageTimeout();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceException(PUBLIC_CASE_UNAVAILABLE, "公开案例服务暂不可用");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof PageDeadlineExpired) throw pageTimeout();
            if (cause instanceof ServiceException service) throw service;
            throw new ServiceException(PUBLIC_CASE_UNAVAILABLE, "公开案例服务暂不可用");
        } finally {
            if (!succeeded) futures.forEach(future -> future.cancel(true));
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(remainingNanos(deadline));
    }

    private static long remainingNanos(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new PageDeadlineExpired();
        return remaining;
    }

    private static ServiceException pageTimeout() {
        return new ServiceException(PUBLIC_CASE_PAGE_TIMEOUT, "公开案例收藏列表读取超时");
    }

    private static final class PageDeadlineExpired extends RuntimeException { }

    private <T> T fromCatalog(long tenantId, PostSnapshot post, boolean pageDeadline, Supplier<T> call) {
        try (var ignored = admission.enter(tenantId, post.orgId())) {
            try {
                return call.get();
            } catch (PublicCaseCatalogException error) {
                if (pageDeadline && error.deadlineExceeded()) throw pageTimeout();
                if (error.invalidResponse()) throw invalidResponse();
                throw new ServiceException(PUBLIC_CASE_UNAVAILABLE, error.getMessage());
            }
        } catch (PublicCaseAdmission.BusyException busy) {
            throw new ServiceException(PUBLIC_CASE_BUSY, "公开案例服务繁忙，请稍后重试");
        }
    }

    private long tenant() {
        return TenantContextHolder.getRequiredTenantId();
    }

    private static void validatePageLimit(int limit) {
        if (limit < 1 || limit > 50) throw new ServiceException(400, "分页范围无效");
    }

    private static void requireCaseId(long caseId) {
        if (caseId <= 0) throw new ServiceException(400, "案例编号无效");
    }

    private static boolean sameCase(Object value, long expected) {
        if (value instanceof Number number) return number.longValue() == expected;
        if (!(value instanceof String text)) return false;
        try {
            return Long.parseLong(text) == expected;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void put(Map<String, String> target, String key, Object value) {
        if (value == null) return;
        if (value instanceof String text && text.isBlank()) return;
        target.put(key, value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString());
    }

    private static void copy(Map<?, ?> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("favorite:" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith("favorite:")) throw new IllegalArgumentException();
            long id = Long.parseLong(decoded.substring("favorite:".length()));
            if (id <= 0) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) {
            throw new ServiceException(400, "收藏游标无效");
        }
    }

    private static ServiceException invalidResponse() {
        return new ServiceException(PUBLIC_CASE_INVALID_RESPONSE, "公开案例服务返回无效响应");
    }

    @Override
    public void close() {
        hydrationExecutor.shutdownNow();
    }
}
