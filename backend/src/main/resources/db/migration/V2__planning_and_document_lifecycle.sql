ALTER TABLE workspace ADD COLUMN planning_balance numeric(19,2)
    CHECK (planning_balance BETWEEN -999999999.99 AND 999999999.99);
ALTER TABLE document ADD COLUMN expected_date date;
ALTER TABLE document ADD COLUMN cancelled boolean NOT NULL DEFAULT false;
ALTER TABLE document ADD COLUMN revision bigint NOT NULL DEFAULT 0;
