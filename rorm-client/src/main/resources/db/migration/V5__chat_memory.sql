create table chat_memory
(
    id              bigint generated always as identity primary key,
    conversation_id varchar(256)             not null,
    message_type    varchar(20)              not null,
    payload         JSONB                    not null,
    created_at      timestamp with time zone not null default now()
);

create index idx_chat_memory_conversation on chat_memory (conversation_id, created_at);
