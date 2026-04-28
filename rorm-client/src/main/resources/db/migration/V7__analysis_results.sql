alter table session_analyses
    add column result        jsonb,
    add column error_message text;
