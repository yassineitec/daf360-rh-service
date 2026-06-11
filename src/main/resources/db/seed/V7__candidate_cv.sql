-- V7__candidate_cv.sql
-- Adds CV storage columns to the candidates table.
-- Safe to re-run — uses IF NOT EXISTS guards.

USE [DAF360_HR];

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='cv_path')
    ALTER TABLE [dbo].[candidates] ADD [cv_path] VARCHAR(500) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='cv_original_name')
    ALTER TABLE [dbo].[candidates] ADD [cv_original_name] VARCHAR(255) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='cv_uploaded_at')
    ALTER TABLE [dbo].[candidates] ADD [cv_uploaded_at] DATETIMEOFFSET(6) NULL;

PRINT 'V7: candidate CV columns ready.';
