ALTER TABLE allocation_requests ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX IF NOT EXISTS idx_allocation_requests_idempotency_key
    ON allocation_requests(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
