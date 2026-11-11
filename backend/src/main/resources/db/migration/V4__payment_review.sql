CREATE TABLE payment_review (
    account_id text NOT NULL,
    transaction_id text NOT NULL,
    mode text NOT NULL DEFAULT 'AUTO' CHECK (mode IN ('AUTO', 'MANUAL', 'EXCLUDED')),
    document_id uuid REFERENCES document(id),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (account_id, transaction_id),
    FOREIGN KEY (account_id, transaction_id) REFERENCES bank_transaction(account_id, external_id) ON DELETE CASCADE,
    CHECK ((mode = 'MANUAL' AND document_id IS NOT NULL) OR (mode <> 'MANUAL' AND document_id IS NULL))
);
CREATE TABLE payment_review_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id text NOT NULL,
    transaction_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('LINK', 'UNLINK', 'RESUME')),
    previous_mode text NOT NULL,
    previous_document_id uuid,
    previous_document_label text,
    next_mode text NOT NULL,
    next_document_id uuid,
    next_document_label text,
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    actor text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (account_id, transaction_id) REFERENCES bank_transaction(account_id, external_id) ON DELETE CASCADE
);
CREATE INDEX payment_review_history ON payment_review_audit(account_id, transaction_id, id);
