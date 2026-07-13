-- V40__candidate_gender.sql
-- Adds a gender column to the candidates table so gender is captured at the
-- candidate stage (candidate create form) and flows through to onboarding /
-- employee_profiles instead of being asked for the first time during onboarding.
-- Values are the canonical GENDER configurable-list codes (MALE/FEMALE/OTHER/
-- UNSPECIFIED — see V9__configurable_lists_seed.sql); the backend normalizes
-- any incoming variant via GenderNormalizer on write.
-- Safe to re-run — uses an IF NOT EXISTS guard.

USE [DAF360_HR];

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='gender')
    ALTER TABLE [dbo].[candidates] ADD [gender] NVARCHAR(30) NULL;

PRINT 'V40: candidate gender column ready.';
