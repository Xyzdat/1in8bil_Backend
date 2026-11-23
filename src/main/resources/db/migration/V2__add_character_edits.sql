alter table life_session
    add column display_name varchar(100),
    add column custom_appearance integer check (custom_appearance between 0 and 100);
