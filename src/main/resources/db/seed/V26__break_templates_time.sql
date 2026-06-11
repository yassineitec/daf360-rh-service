-- =============================================================================
-- V26: Add time range columns to break_templates for scheduled collective breaks
--      break_time_start / break_time_end define WHEN the break occurs during the day
--      Engine: if work period overlaps the break window → deduct the overlap duration
-- =============================================================================
USE [DAF360_HR];
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='break_templates' AND COLUMN_NAME='break_time_start')
    ALTER TABLE [dbo].[break_templates] ADD [break_time_start] TIME(0) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='break_templates' AND COLUMN_NAME='break_time_end')
    ALTER TABLE [dbo].[break_templates] ADD [break_time_end] TIME(0) NULL;
GO
-- NULL = use old min_work_hours_trigger logic (backward compat)
-- Non-NULL = deduct only if work period overlaps the break window
