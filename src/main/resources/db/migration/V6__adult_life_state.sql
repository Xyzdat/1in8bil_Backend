-- Adult state is initialized lazily when a saved local life reaches adulthood.
-- No existing character, family, history or balance is rewritten by this migration.
alter table life_session add column adult_state jsonb;
