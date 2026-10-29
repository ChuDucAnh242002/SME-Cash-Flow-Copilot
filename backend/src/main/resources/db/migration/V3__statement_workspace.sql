ALTER TABLE workspace DROP CONSTRAINT workspace_bank_mode_check;
ALTER TABLE workspace ADD CONSTRAINT workspace_bank_mode_check
    CHECK (bank_mode IN ('demo', 'nordea', 'statement'));
ALTER TABLE workspace ADD COLUMN statement_date date;
ALTER TABLE workspace ADD COLUMN statement_revision bigint NOT NULL DEFAULT 0;
