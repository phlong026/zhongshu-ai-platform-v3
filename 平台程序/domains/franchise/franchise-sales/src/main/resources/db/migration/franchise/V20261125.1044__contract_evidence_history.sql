-- Evidence corrections preserve both versions; existing SIGNED rows are deliberately not backfilled.
CREATE TABLE fr_sale_contract_evidence_event (
    id bigint PRIMARY KEY,
    company_id bigint NOT NULL,
    contract_id bigint NOT NULL REFERENCES fr_sale_contract(id),
    revision bigint NOT NULL,
    previous_revision bigint NOT NULL,
    before_evidence jsonb NOT NULL,
    after_evidence jsonb NOT NULL,
    reason varchar(500) NOT NULL,
    actor_user_id bigint NOT NULL,
    actor_post_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(contract_id,revision),
    CHECK(previous_revision >= 1 AND revision = previous_revision + 1),
    CHECK(jsonb_typeof(before_evidence) = 'object' AND jsonb_typeof(after_evidence) = 'object'),
    CHECK(length(trim(reason)) > 0)
);
