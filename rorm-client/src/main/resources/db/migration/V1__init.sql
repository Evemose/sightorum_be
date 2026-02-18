-- Chat node base table (JPA JOINED inheritance)
create table chat_nodes
(
    id         UUID not null,
    created_at timestamp with time zone,
    constraint pk_chat_nodes primary key (id)
);

-- Message nodes
create table message_nodes
(
    id      UUID         not null,
    content text,
    sender  varchar(255) not null,
    constraint pk_message_nodes primary key (id)
);

alter table message_nodes
    add constraint FK_MESSAGE_NODES_ON_ID foreign key (id) references chat_nodes (id);

-- Temporary nodes (in-progress streaming)
create table temporary_nodes
(
    id                  UUID not null,
    in_progress_content text,
    constraint pk_temporary_nodes primary key (id)
);

alter table temporary_nodes
    add constraint FK_TEMPORARY_NODES_ON_ID foreign key (id) references chat_nodes (id);

-- Chat forked nodes
create table chat_forked_nodes
(
    id                   UUID not null,
    created_at           timestamp with time zone,
    fork_point_id        UUID not null,
    reason               text not null,
    further_instructions text not null,
    constraint pk_chat_forked_nodes primary key (id)
);

alter table chat_forked_nodes
    add constraint FK_CHAT_FORKED_NODES_ON_ID foreign key (id) references chat_nodes (id);

-- Training node subtypes
create table training_queued_nodes
(
    id              UUID  not null,
    training_id     UUID  not null,
    request_payload JSONB not null,
    constraint pk_training_queued_nodes primary key (id)
);

alter table training_queued_nodes
    add constraint FK_TRAINING_QUEUED_NODES_ON_ID foreign key (id) references chat_nodes (id);

create table training_started_nodes
(
    id          UUID  not null,
    training_id UUID  not null,
    payload     JSONB not null,
    constraint pk_training_started_nodes primary key (id)
);

alter table training_started_nodes
    add constraint FK_TRAINING_STARTED_NODES_ON_ID foreign key (id) references chat_nodes (id);

create table training_progress_nodes
(
    id                  UUID             not null,
    training_id         UUID             not null,
    progress_percentage double precision not null,
    constraint pk_training_progress_nodes primary key (id)
);

alter table training_progress_nodes
    add constraint FK_TRAINING_PROGRESS_NODES_ON_ID foreign key (id) references chat_nodes (id);

create table training_finish_nodes
(
    id          UUID  not null,
    training_id UUID  not null,
    metrics     JSONB not null,
    constraint pk_training_finish_nodes primary key (id)
);

alter table training_finish_nodes
    add constraint FK_TRAINING_FINISH_NODES_ON_ID foreign key (id) references chat_nodes (id);

create table training_failed_nodes
(
    id          UUID  not null,
    training_id UUID  not null,
    message     text  not null,
    payload     JSONB not null,
    constraint pk_training_failed_nodes primary key (id)
);

alter table training_failed_nodes
    add constraint FK_TRAINING_FAILED_NODES_ON_ID foreign key (id) references chat_nodes (id);

-- Chat progress (sessions)
create table chat_progress
(
    id          UUID         not null,
    status      varchar(255) not null,
    model_space JSONB,
    parent_id   UUID,
    created_at  timestamp with time zone,
    constraint pk_chat_progress primary key (id)
);

alter table chat_progress
    add constraint FK_CHAT_PROGRESS_ON_PARENT foreign key (parent_id) references chat_progress (id);

-- Join table: chat_progress <-> chat_nodes
create table chat_progress_nodes
(
    chat_progress_id UUID not null,
    nodes_id UUID not null
);

alter table chat_progress_nodes
    add constraint uc_chat_progress_nodes_nodes unique (nodes_id);

alter table chat_progress_nodes
    add constraint fk_chapronod_on_chat_node foreign key (nodes_id) references chat_nodes (id);

alter table chat_progress_nodes
    add constraint fk_chapronod_on_chat_progress foreign key (chat_progress_id) references chat_progress (id);

-- Import jobs
create table import_jobs
(
    id             UUID         not null,
    status         varchar(255) not null,
    target_schema  varchar(255) not null,
    upload_dir     text         not null,
    started_at     timestamp with time zone,
    completed_at   timestamp with time zone,
    total_rows     bigint,
    processed_rows bigint,
    error_message  text,
    constraint pk_import_jobs primary key (id)
);

-- Metamodels
create table metamodels
(
    id          UUID         not null,
    schema_name varchar(255) not null,
    model_space JSONB        not null,
    created_at  timestamp with time zone,
    updated_at  timestamp with time zone,
    constraint pk_metamodels primary key (id)
);

alter table metamodels
    add constraint uc_metamodels_schemaname unique (schema_name);

-- Research sessions
create table researches
(
    id           UUID         not null,
    swarm_id     varchar(255) not null unique,
    metamodel_id UUID         not null,
    status       varchar(255) not null,
    created_at   timestamp with time zone,
    updated_at   timestamp with time zone,
    constraint pk_researches primary key (id),
    constraint fk_researches_metamodel foreign key (metamodel_id) references metamodels (id)
);

-- Research DAG nodes
create table research_nodes
(
    id               UUID         not null,
    research_id      UUID         not null,
    node_type        varchar(255) not null,
    node_id          varchar(255) not null,
    branch_id        varchar(255),
    step_id          varchar(255),
    previous_step_id varchar(255),
    dependency_refs  jsonb        not null,
    status           varchar(255) not null,
    progress_node_id varchar(255),
    payload          jsonb,
    raw_response     text,
    error_message    text,
    created_at       timestamp with time zone,
    constraint pk_research_nodes primary key (id),
    constraint fk_research_nodes_research foreign key (research_id) references researches (id)
);

-- DAG edges
create table research_node_dependencies
(
    node_id       UUID not null,
    dependency_id UUID not null,
    constraint pk_research_node_deps primary key (node_id, dependency_id),
    constraint fk_rnd_node foreign key (node_id) references research_nodes (id),
    constraint fk_rnd_dependency foreign key (dependency_id) references research_nodes (id)
);

-- Add event log to import_jobs
alter table import_jobs
    add column event_log jsonb;
