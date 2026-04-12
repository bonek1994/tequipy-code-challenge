CREATE TABLE IF NOT EXISTS allocation_processing_results (
    allocation_id UUID PRIMARY KEY,
    state         VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS allocation_processing_equipment_ids (
    allocation_id UUID NOT NULL REFERENCES allocation_processing_results(allocation_id) ON DELETE CASCADE,
    equipment_id  UUID NOT NULL REFERENCES equipments(id),
    PRIMARY KEY (allocation_id, equipment_id)
);

