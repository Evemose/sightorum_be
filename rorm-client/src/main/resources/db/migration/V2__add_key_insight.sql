alter table agent_subconclusion_nodes
    add column key_insight text;

update agent_subconclusion_nodes
set key_insight = summary;

alter table agent_subconclusion_nodes
    alter column key_insight set not null;