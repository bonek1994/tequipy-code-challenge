CREATE TABLE IF NOT EXISTS equipments (
    id              UUID             PRIMARY KEY,
    type            VARCHAR(255)     NOT NULL,
    brand           VARCHAR(255)     NOT NULL,
    model           VARCHAR(255)     NOT NULL,
    state           VARCHAR(255)     NOT NULL,
    condition_score DOUBLE PRECISION NOT NULL,
    purchase_date   DATE             NOT NULL,
    retired_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_equipments_state_type_condition_score
    ON equipments(state, type, condition_score);

CREATE TABLE IF NOT EXISTS allocations (
    id              UUID         PRIMARY KEY,
    state           VARCHAR(255) NOT NULL,
    idempotency_key UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_allocations_idempotency_key
    ON allocations(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS allocation_policy_requirements (
    id                      UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    allocation_request_id   UUID             NOT NULL REFERENCES allocations(id),
    type                    VARCHAR(255)     NOT NULL,
    quantity                INT              NOT NULL,
    minimum_condition_score DOUBLE PRECISION,
    preferred_brand         VARCHAR(255),
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS allocation_equipment_ids (
    allocation_request_id UUID NOT NULL REFERENCES allocations(id),
    equipment_id          UUID NOT NULL REFERENCES equipments(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (allocation_request_id, equipment_id)
);

CREATE TABLE IF NOT EXISTS allocation_processing_results (
    allocation_id UUID PRIMARY KEY,
    state         VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS allocation_processing_equipment_ids (
    allocation_id UUID NOT NULL REFERENCES allocation_processing_results(allocation_id) ON DELETE CASCADE,
    equipment_id  UUID NOT NULL REFERENCES equipments(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (allocation_id, equipment_id)
);

