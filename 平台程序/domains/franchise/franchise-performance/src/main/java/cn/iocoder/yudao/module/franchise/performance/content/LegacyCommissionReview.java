package cn.iocoder.yudao.module.franchise.performance.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;

/** Read-only evidence for a boss review recorded before unified signatures included refunds. */
final class LegacyCommissionReview {
    private LegacyCommissionReview() {}
    private static final ObjectMapper JSON = new ObjectMapper();

    // r is the release. Both the interactive decision and the batched export use this query.
    // updateJsonb set r.update_time to the database transaction start when the boss reviewed it.
    static final String EVIDENCE = """
        SELECT jsonb_build_object('review',r.reconciliations->-1,'payload',event.payload,
                   'project',r.project_id,'stage',s.stage,'latestFact',history.changed,
                   'eventAt',event.occurred_at)::text
        FROM fr_sale_payment_stage s
        JOIN LATERAL (
            SELECT max(greatest(sr.create_time,g.create_time,g.update_time,g.voided_at)) AS changed,
                   bool_and(sr.create_time IS NOT NULL AND (g.receipt_id IS NULL OR
                       (g.create_time IS NOT NULL AND g.update_time IS NOT NULL))) AS complete
            FROM fr_sale_stage_receipt sr LEFT JOIN fr_fin_refund g
              ON g.company_id=sr.company_id AND g.receipt_id=sr.receipt_id
            WHERE sr.company_id=s.company_id AND sr.stage_id=s.id
        ) history ON history.complete AND history.changed < r.update_time
        JOIN LATERAL (
            SELECT e.actor_user_id,e.actor_role_code,e.command,e.payload,e.occurred_at
            FROM pf_command_event e
            WHERE e.domain='franchise' AND e.aggregate_type='fr-perf-commission'
              AND e.org_id=r.company_id AND e.aggregate_id=r.id
            ORDER BY e.revision DESC LIMIT 1
        ) event ON event.command='perf-commission-reconcile' AND event.actor_role_code='boss'
                AND event.actor_user_id::text=r.reconciliations->-1->>'actorUserId'
        WHERE s.company_id=r.company_id
          AND s.contract_id::text=split_part(split_part(r.receipt_signature,'|',2),':',2)
          AND s.stage=CASE r.stage WHEN 'START' THEN 'SIGN_AFTER' WHEN 'TOPPING' THEN 'CAP_DONE' ELSE r.stage END
          AND r.reconciliations->-1->>'receiptSignature'=r.receipt_signature
          AND r.update_time > r.release_at
        """;

    static boolean possible(String current, String saved) {
        return saved != null && current != null && !saved.contains("|refunded:")
                && saved.startsWith("required:") && saved.contains("|unified:")
                && current.startsWith(saved + "|refunded:");
    }

    static boolean covers(String current, String saved, String evidence) {
        if (!possible(current, saved) || evidence == null) return false;
        try {
            JsonNode data = JSON.readTree(evidence);
            if (data == null || !data.isObject()) return false;
            JsonNode review = data.required("review");
            JsonNode command = JSON.readTree(data.required("payload").asText());
            if (command == null || !command.isObject()) return false;
            String proof = review.required("evidenceRef").asText("");
            String note = review.required("note").asText("");
            OffsetDateTime reviewed = OffsetDateTime.parse(review.required("at").asText());
            return !proof.isBlank() && !note.isBlank() && proof.equals(command.required("evidenceRef").asText())
                    && note.equals(command.required("note").asText())
                    && data.required("project").asText().equals(command.required("projectId").asText())
                    && data.required("stage").asText().equals(command.required("stage").asText())
                    && saved.equals(review.required("receiptSignature").asText())
                    && saved.substring(saved.lastIndexOf(':')+1).equals(review.required("stageNetCollectedCents").asText())
                    && reviewed.isAfter(OffsetDateTime.parse(data.required("latestFact").asText()))
                    && !reviewed.isAfter(OffsetDateTime.parse(data.required("eventAt").asText()).plusNanos(999));
        } catch (java.io.IOException | IllegalArgumentException | java.time.DateTimeException error) {
            return false;
        }
    }
}
