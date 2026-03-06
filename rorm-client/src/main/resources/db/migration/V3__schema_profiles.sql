create table schema_profiles
(
    id          UUID         not null,
    schema_name varchar(255) not null,
    profile     jsonb        not null,
    created_at  timestamp with time zone,
    constraint pk_schema_profiles primary key (id)
);

alter table schema_profiles
    add constraint uc_schema_profiles_schema_name unique (schema_name);

create index idx_schema_profiles_schema_name on schema_profiles (schema_name);
