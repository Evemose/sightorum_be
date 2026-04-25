create table sessions
(
    id              varchar(256) primary key,
    title           varchar(500),
    schema_name     varchar(256),
    primary_chat_id varchar(256)             not null,
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now()
);

create index idx_sessions_updated_at on sessions (updated_at desc);

create table session_analyses
(
    run_id       varchar(256) primary key,
    session_id   varchar(256)             not null references sessions (id) on delete cascade,
    kind         varchar(20)              not null,
    query        text,
    status       varchar(20)              not null default 'RUNNING',
    started_at   timestamp with time zone not null default now(),
    completed_at timestamp with time zone
);

create index idx_session_analyses_session on session_analyses (session_id, started_at desc);

create table session_imports
(
    job_id     varchar(256)             not null,
    session_id varchar(256)             not null references sessions (id) on delete cascade,
    linked_at  timestamp with time zone not null default now(),
    primary key (job_id, session_id)
);

create index idx_session_imports_session on session_imports (session_id, linked_at desc);
