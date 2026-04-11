CREATE INDEX IF NOT EXISTS idx_equipments_state_type_condition_score
    ON equipments(state, type, condition_score);
