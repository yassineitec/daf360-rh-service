-- V39__candidate_experience_location.sql
-- Adds experience (years) and location columns to the candidates table.
-- Used to enrich the /rh/candidates and /rh/recrutement Kanban cards.
-- Safe to re-run — uses IF NOT EXISTS guards.

USE [DAF360_HR];

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='experience_years')
    ALTER TABLE [dbo].[candidates] ADD [experience_years] INT NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='location')
    ALTER TABLE [dbo].[candidates] ADD [location] NVARCHAR(150) NULL;

PRINT 'V39: candidate experience_years + location columns ready.';
