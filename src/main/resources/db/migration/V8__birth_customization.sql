-- Opt-in birth customization; existing sessions and original creation requests stay intact.
alter table life_session add column character_customization jsonb;
