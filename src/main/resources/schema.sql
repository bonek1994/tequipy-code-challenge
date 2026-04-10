CREATE TABLE IF NOT EXISTS equipments (
    id              UUID             PRIMARY KEY,
    type            VARCHAR(255)     NOT NULL,
    brand           VARCHAR(255)     NOT NULL,
    model           VARCHAR(255)     NOT NULL,
    state           VARCHAR(255)     NOT NULL,
    condition_score DOUBLE PRECISION NOT NULL,
    purchase_date   DATE             NOT NULL,
    retired_reason  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS allocation_requests (
    id          UUID         PRIMARY KEY,
    employee_id UUID         NOT NULL,
    state       VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS allocation_policy_requirements (
    allocation_request_id  UUID             NOT NULL REFERENCES allocation_requests(id),
    type                   VARCHAR(255)     NOT NULL,
    quantity               INT              NOT NULL,
    minimum_condition_score DOUBLE PRECISION,
    preferred_brand        VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS allocation_equipment_ids (
    allocation_request_id UUID NOT NULL REFERENCES allocation_requests(id),
    equipment_id          UUID NOT NULL
);
