create table agent_subconclusion_node_research_steps
(
    agent_subconclusion_node_id UUID not null,
    reasoning                   text not null,
    action                      text not null,
    observation                 text not null
);

create table agent_subconclusion_nodes
(
    id              UUID not null,
    created_at      timestamp with time zone,
    summary         text not null,
    details         text not null,
    conversation_id text not null unique,
    constraint pk_agent_subconclusion_nodes primary key (id)
);

create table chat_forked_nodes
(
    id                   UUID not null,
    created_at           timestamp with time zone,
    fork_point_id        UUID not null,
    reason               text not null,
    further_instructions text not null,
    constraint pk_chat_forked_nodes primary key (id)
);


create table chat_nodes
(
    id         UUID not null,
    created_at timestamp with time zone,
    constraint pk_chat_nodes primary key (id)
);

create table training_failed_nodes
(
    id          UUID  not null,
    training_id UUID  not null,
    message     text  not null,
    payload     JSONB not null,
    constraint pk_training_failed_nodes primary key (id)
);

create table chat_progress
(
    id          UUID         not null,
    status      varchar(255) not null,
    model_space JSONB,
    parent_id   UUID,
    created_at  timestamp with time zone,
    constraint pk_chat_progress primary key (id)
);

create table chat_progress_nodes
(
    chat_progress_id UUID not null,
    nodes_id UUID not null
);

create table message_nodes
(
    id      UUID         not null,
    content text,
    sender  varchar(255) not null,
    constraint pk_message_nodes primary key (id)
);

create table training_finish_nodes
(
    id          UUID  not null,
    training_id UUID  not null,
    metrics     JSONB not null,
    constraint pk_training_finish_nodes primary key (id)
);

create table training_progress_nodes
(
    id                  UUID             not null,
    training_id         UUID             not null,
    progress_percentage double precision not null,
    constraint pk_training_progress_nodes primary key (id)
);

create table training_queued_nodes
(
    id              UUID  not null,
    training_id     UUID  not null,
    request_payload JSONB not null,
    constraint pk_training_queued_nodes primary key (id)
);

create table training_started_nodes
(
    id          UUID  not null,
    training_id UUID  not null,
    payload     JSONB not null,
    constraint pk_training_started_nodes primary key (id)
);

alter table chat_progress_nodes
    add constraint uc_chat_progress_nodes_nodes unique (nodes_id);

alter table chat_progress
    add constraint FK_CHAT_PROGRESS_ON_PARENT foreign key (parent_id) references chat_progress (id);

alter table message_nodes
    add constraint FK_MESSAGE_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_finish_nodes
    add constraint FK_TRAINING_FINISH_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_progress_nodes
    add constraint FK_TRAINING_PROGRESS_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_queued_nodes
    add constraint FK_TRAINING_QUEUED_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_started_nodes
    add constraint FK_TRAINING_STARTED_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table agent_subconclusion_node_research_steps
    add constraint fk_agentsubconclusionnoderesearchstep_on_agentsubconclusionnode foreign key (agent_subconclusion_node_id) references chat_nodes (id);

alter table chat_progress_nodes
    add constraint fk_chapronod_on_chat_node foreign key (nodes_id) references chat_nodes (id);

alter table chat_progress_nodes
    add constraint fk_chapronod_on_chat_progress foreign key (chat_progress_id) references chat_progress (id);

create table rorm_client.import_jobs
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

create table rorm_client.metamodels
(
    id          UUID         not null,
    schema_name varchar(255) not null,
    model_space JSONB        not null,
    created_at  timestamp with time zone,
    updated_at  timestamp with time zone,
    constraint pk_metamodels primary key (id)
);

alter table rorm_client.metamodels
    add constraint uc_metamodels_schemaname unique (schema_name);