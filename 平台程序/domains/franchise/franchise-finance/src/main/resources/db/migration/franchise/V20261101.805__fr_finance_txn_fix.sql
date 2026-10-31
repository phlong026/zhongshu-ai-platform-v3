-- V20261101.805：修正 fr_fin_txn_no 主键结构（MyBatis-Plus @TableId 需要单列 id）
DROP TABLE IF EXISTS fr_fin_txn_no;
CREATE TABLE fr_fin_txn_no (
    id          BIGINT      NOT NULL,
    company_id  BIGINT      NOT NULL,
    txn_no      VARCHAR(64) NOT NULL,
    owner_type  VARCHAR(16) NOT NULL CHECK (owner_type IN ('receipt','payment','refund')),
    owner_id    BIGINT      NOT NULL,
    closed      BOOLEAN     NOT NULL DEFAULT FALSE,
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_txn_no PRIMARY KEY (id),
    CONSTRAINT uk_fr_fin_txn_no UNIQUE (company_id, txn_no)
);
