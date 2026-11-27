-- Optional result freezes declined random outcomes as well as successful commands.
-- Existing sessions and action commands remain untouched.
ALTER TABLE life_session_action ADD COLUMN budget_result jsonb;
