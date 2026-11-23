create table guest_owner (
    id uuid primary key,
    token_hash varchar(64) not null unique,
    expires_at timestamp with time zone not null
);

create table life_session (
    id uuid primary key,
    owner_id uuid not null references guest_owner (id),
    creation_key uuid not null,
    setup jsonb not null,
    state jsonb not null,
    engine_id varchar(40) not null,
    engine_token text,
    engine_token_expires_at timestamp with time zone,
    revision bigint not null check (revision >= 1),
    saved_at timestamp with time zone not null,
    unique (owner_id, creation_key),
    check ((engine_token is null) = (engine_token_expires_at is null))
);

create index life_session_owner_saved_idx on life_session (owner_id, saved_at desc);

create table life_session_action (
    id uuid primary key,
    session_id uuid not null references life_session (id),
    request_key uuid not null,
    command jsonb not null,
    unique (session_id, request_key)
);
