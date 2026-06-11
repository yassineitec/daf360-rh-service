-- V18: Make employee_request_id nullable to support documents not tied to a request (applied manually 2026-06-04)
ALTER TABLE [dbo].[generated_documents] ALTER COLUMN employee_request_id BIGINT NULL;
