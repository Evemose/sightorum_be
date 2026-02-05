create table temporary_nodes
(
    id                  UUID not null,
    in_progress_content text,
    constraint pk_temporary_nodes primary key (id)
);

alter table temporary_nodes
    add constraint FK_TEMPORARY_NODES_ON_ID foreign key (id) references chat_nodes (id);