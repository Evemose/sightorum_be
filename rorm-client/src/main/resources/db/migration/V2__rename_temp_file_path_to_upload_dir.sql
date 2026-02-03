-- Rename temp_file_path column to upload_dir to support multi-file datasets
alter table import_jobs
    rename column temp_file_path to upload_dir;
