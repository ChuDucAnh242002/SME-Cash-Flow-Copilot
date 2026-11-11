CREATE TABLE consumer_failure (
    topic text NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    attempts bigint NOT NULL CHECK (attempts > 0),
    first_failed_at timestamptz NOT NULL DEFAULT now(),
    last_failed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (topic, partition_id)
);
