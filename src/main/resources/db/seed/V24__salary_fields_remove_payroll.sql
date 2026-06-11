-- =============================================================================
-- V24: Add candidate/HR salary fields to employee_profiles
--      Payroll simulation tables are kept in DB for historical data
--      but the application no longer uses them.
-- =============================================================================
USE [DAF360_HR];
GO

-- Add two net salary fields to employee_profiles
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='salaire_net_candidat')
    ALTER TABLE [dbo].[employee_profiles]
        ADD [salaire_net_candidat] DECIMAL(10,3) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='salaire_net_rh')
    ALTER TABLE [dbo].[employee_profiles]
        ADD [salaire_net_rh] DECIMAL(10,3) NULL;
GO
