-- Promote soft schema_name references to real foreign keys against metamodels(schema_name).
-- ON DELETE NO ACTION ("do nothing"): deleting a metamodel that still has dependent rows is refused.

alter table schema_profiles
    add constraint fk_schema_profiles_metamodel
        foreign key (schema_name) references metamodels (schema_name)
        on delete no action
        on update no action;

alter table sessions
    add constraint fk_sessions_metamodel
        foreign key (schema_name) references metamodels (schema_name)
        on delete no action
        on update no action;
