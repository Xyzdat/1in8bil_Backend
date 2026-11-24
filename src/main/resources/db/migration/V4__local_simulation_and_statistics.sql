create table statistical_release (
    id varchar(100) primary key,
    source_url text not null,
    license varchar(80) not null,
    source_updated date not null,
    retrieved_at timestamp with time zone not null,
    sha256 varchar(64) not null unique,
    imported_at timestamp with time zone not null default current_timestamp
);
create table statistical_observation (
    release_id varchar(100) not null references statistical_release(id),
    country_iso3 varchar(3) not null,
    indicator varchar(60) not null,
    year integer not null,
    value numeric not null check (value >= 0),
    unit varchar(60) not null,
    primary key (release_id, country_iso3, indicator, year)
);
alter table life_session add column simulation jsonb;
