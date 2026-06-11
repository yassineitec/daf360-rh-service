-- V11__notifications_read_at.sql
-- Ensures the notifications table has the read_at column used by the HR notification API.
-- Safe to re-run.

USE [DAF360_HR];

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='notifications' AND COLUMN_NAME='read_at'
)
BEGIN
    ALTER TABLE [dbo].[notifications] ADD [read_at] DATETIMEOFFSET(6) NULL;
    PRINT 'Added: notifications.read_at';
END
ELSE
    PRINT 'Skipped: notifications.read_at (already exists)';

PRINT 'V11 complete.';
