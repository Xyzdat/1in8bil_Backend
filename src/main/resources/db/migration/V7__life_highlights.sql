-- Passive, versioned selection of decisions and their confirmed consequences.
-- Existing lives are left untouched; tracking starts lazily on their next successful action.
alter table life_session add column life_highlights jsonb;
