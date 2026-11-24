create table reference_release (
    id varchar(100) primary key,
    manifest jsonb not null,
    imported_at timestamptz not null default current_timestamp
);
create table country_birth_reference (
    release_id varchar(100) not null references reference_release(id),
    iso3 varchar(3) not null,
    profile jsonb not null,
    primary key(release_id, iso3)
);
create table mortality_rate (
    release_id varchar(100) not null references reference_release(id),
    iso3 varchar(3) not null,
    sex varchar(6) not null check(sex in ('MALE','FEMALE')),
    year integer not null,
    age integer not null check(age between 0 and 100),
    qx double precision not null check(qx between 0 and 1),
    mx double precision not null check(mx >= 0),
    primary key(release_id, iso3, sex, year, age)
);
