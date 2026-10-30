CREATE TABLE recurring_expense (
    id uuid PRIMARY KEY,
    counterparty text NOT NULL,
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    first_due_date date NOT NULL,
    end_date date,
    next_month date NOT NULL,
    stopped boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date >= first_due_date)
);
ALTER TABLE document ADD COLUMN recurring_expense_id uuid REFERENCES recurring_expense(id);
ALTER TABLE document ADD COLUMN occurrence_month date;
ALTER TABLE document ADD CONSTRAINT document_occurrence_check CHECK
    ((recurring_expense_id IS NULL AND occurrence_month IS NULL)
     OR (recurring_expense_id IS NOT NULL AND occurrence_month IS NOT NULL AND kind = 'EXPENSE'));
CREATE UNIQUE INDEX document_recurring_month ON document(recurring_expense_id, occurrence_month)
    WHERE recurring_expense_id IS NOT NULL;
ALTER TABLE workspace ADD COLUMN recurrence_error text;
