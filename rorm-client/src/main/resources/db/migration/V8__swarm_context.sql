create table swarm_agent_state
(
    run_id             varchar(256) not null,
    chat_id            varchar(256) not null,
    role               varchar(64)  not null,
    schema_name        varchar(256) not null,
    agent_config       JSONB        not null,
    rounds             JSONB        not null default '[]'::jsonb,
    registration_order bigint generated always as identity,
    primary key (run_id, chat_id)
);

create index idx_swarm_agent_state_run on swarm_agent_state (run_id, registration_order);
