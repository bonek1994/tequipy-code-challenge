-- Partial index covering only AVAILABLE equipment.
-- Smaller, cache-friendly, and cheaper to maintain than the full composite index
-- because it is only updated when equipment enters or leaves the AVAILABLE state,
-- not on subsequent RESERVED → ASSIGNED transitions.
-- The query planner will prefer this index for the allocation-path queries:
--   findAvailableWithMinConditionScore(types, minScore)
--   findByIdsForUpdate(ids, minConditionScore)
CREATE INDEX IF NOT EXISTS idx_equipments_available_type_score
    ON equipments (type, condition_score)
    WHERE state = 'AVAILABLE';
