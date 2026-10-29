CREATE TABLE workspace (
    id integer PRIMARY KEY CHECK (id = 1),
    bank_mode text NOT NULL CHECK (bank_mode IN ('demo', 'nordea')),
    last_sync timestamptz,
    sync_error text,
    last_reconciled timestamptz
);
CREATE TABLE account (
    id text PRIMARY KEY,
    name text NOT NULL,
    currency text NOT NULL CHECK (currency = 'EUR'),
    booked_balance numeric(19,2) NOT NULL,
    observed_at timestamptz NOT NULL
);
CREATE TABLE bank_transaction (
    account_id text NOT NULL REFERENCES account(id),
    external_id text NOT NULL,
    booking_date date NOT NULL,
    amount numeric(19,2) NOT NULL,
    reference text NOT NULL,
    counterparty text NOT NULL,
    PRIMARY KEY (account_id, external_id)
);
CREATE TABLE document (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('INVOICE', 'EXPENSE')),
    counterparty text NOT NULL,
    reference text NOT NULL,
    normalized_reference text NOT NULL UNIQUE,
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    due_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE allocation (
    account_id text NOT NULL,
    transaction_id text NOT NULL,
    document_id uuid NOT NULL REFERENCES document(id),
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (account_id, transaction_id),
    FOREIGN KEY (account_id, transaction_id) REFERENCES bank_transaction(account_id, external_id)
);
CREATE TABLE outbox (
    id uuid PRIMARY KEY,
    type text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    last_error text
);
CREATE INDEX outbox_pending ON outbox(created_at) WHERE published_at IS NULL;
CREATE TABLE processed_event (
    id uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
