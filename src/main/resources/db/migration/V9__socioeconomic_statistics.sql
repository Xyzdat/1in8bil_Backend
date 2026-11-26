-- Versioned aggregates, isolated from saves and birth/mortality releases.
create table socioeconomic_release (
    id varchar(100) primary key,
    stage varchar(30) not null check (stage in ('family-housing', 'work-income')),
    manifest jsonb not null,
    imported_at timestamptz not null default current_timestamp
);

create table socioeconomic_observation (
    release_id varchar(100) not null references socioeconomic_release(id),
    row_key varchar(64) not null,
    source_id varchar(100) not null,
    country_iso3 varchar(3) not null,
    indicator varchar(100) not null,
    year integer not null check (year between 1900 and 2100),
    dimensions jsonb not null,
    value numeric,
    quality varchar(10) not null check (quality in ('VALID','MISSING','REJECTED')),
    provenance jsonb not null,
    primary key (release_id, row_key),
    check ((quality = 'VALID' and value is not null and value >= 0)
        or (quality <> 'VALID' and value is null))
);
create index socioeconomic_lookup on socioeconomic_observation(release_id, country_iso3, indicator, year desc);

create table socioeconomic_coverage (
    release_id varchar(100) not null references socioeconomic_release(id),
    country_iso3 varchar(3) not null,
    indicator varchar(100) not null,
    valid_count integer not null check (valid_count >= 0),
    missing_count integer not null check (missing_count >= 0),
    rejected_count integer not null check (rejected_count >= 0),
    latest_year integer,
    primary key (release_id, country_iso3, indicator),
    check ((valid_count = 0 and latest_year is null) or (valid_count > 0 and latest_year is not null))
);
