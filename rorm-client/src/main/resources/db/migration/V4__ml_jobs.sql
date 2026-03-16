-- Universal ML job persistence (replaces per-type tables)
create table ml_jobs
(
    id                   UUID         not null,
    job_id               UUID         not null unique,
    job_type             varchar(255) not null,
    schema               varchar(255),
    reason               text         not null,
    further_instructions text,
    request              jsonb        not null,
    created_at           timestamp with time zone,
    constraint pk_ml_jobs primary key (id)
);

create index idx_ml_jobs_job_type on ml_jobs (job_type);
