create extension if not exists vector;

create table vector_store
(
    id        uuid default uuid_generate_v4() not null
        primary key,
    content   text,
    metadata  json,
    embedding halfvec(3072)
);

create index vector_store_embedding_idx on vector_store using hnsw (embedding halfvec_cosine_ops) with (ef_construction = 128);

