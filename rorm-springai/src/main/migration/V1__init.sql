create table chat_nodes
(
    id         UUID not null,
    created_at timestamp with time zone,
    constraint pk_chat_nodes primary key (id)
);

create table chat_progress
(
    id         UUID         not null,
    status     varchar(255) not null,
    parent_id  UUID,
    created_at timestamp with time zone,
    constraint pk_chat_progress primary key (id)
);

create table chat_progress_memory_nodes
(
    chat_progress_id UUID not null,
    memory_nodes_id  UUID not null
);

create table chat_progress_past_nodes
(
    chat_progress_id UUID not null,
    past_nodes_id    UUID not null
);

create table failure_nodes
(
    id      UUID not null,
    message varchar(255),
    detail  JSONB,
    constraint pk_failure_nodes primary key (id)
);

create table message_nodes
(
    id      UUID         not null,
    content text,
    sender  varchar(255) not null,
    constraint pk_message_nodes primary key (id)
);

create table tool_call_nodes
(
    id          UUID  not null,
    description text,
    response    JSONB not null,
    constraint pk_tool_call_nodes primary key (id)
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

alter table chat_progress_memory_nodes
    add constraint uc_chat_progress_memory_nodes_memorynodes unique (memory_nodes_id);

alter table chat_progress_past_nodes
    add constraint uc_chat_progress_past_nodes_pastnodes unique (past_nodes_id);

alter table chat_progress
    add constraint FK_CHAT_PROGRESS_ON_PARENT foreign key (parent_id) references chat_progress (id);

alter table failure_nodes
    add constraint FK_FAILURE_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table message_nodes
    add constraint FK_MESSAGE_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table tool_call_nodes
    add constraint FK_TOOL_CALL_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_finish_nodes
    add constraint FK_TRAINING_FINISH_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_progress_nodes
    add constraint FK_TRAINING_PROGRESS_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_queued_nodes
    add constraint FK_TRAINING_QUEUED_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table training_started_nodes
    add constraint FK_TRAINING_STARTED_NODES_ON_ID foreign key (id) references chat_nodes (id);

alter table chat_progress_memory_nodes
    add constraint fk_chapromemnod_on_chat_node foreign key (memory_nodes_id) references chat_nodes (id);

alter table chat_progress_memory_nodes
    add constraint fk_chapromemnod_on_chat_progress foreign key (chat_progress_id) references chat_progress (id);

alter table chat_progress_past_nodes
    add constraint fk_chapropasnod_on_chat_node foreign key (past_nodes_id) references chat_nodes (id);

alter table chat_progress_past_nodes
    add constraint fk_chapropasnod_on_chat_progress foreign key (chat_progress_id) references chat_progress (id);