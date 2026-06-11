USE [DAF360_HR];
GO

-- Drop CHECK constraint on employee_profiles
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name = 'CK_EmpProfile_StaffType'
           AND parent_object_id = OBJECT_ID('dbo.employee_profiles'))
    ALTER TABLE [dbo].[employee_profiles] DROP CONSTRAINT [CK_EmpProfile_StaffType];
GO

-- Drop CHECK constraint on candidates
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name = 'CK_Candidate_StaffType'
           AND parent_object_id = OBJECT_ID('dbo.candidates'))
    ALTER TABLE [dbo].[candidates] DROP CONSTRAINT [CK_Candidate_StaffType];
GO

-- Rename staff_type → department in employee_profiles
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'employee_profiles'
           AND COLUMN_NAME = 'staff_type'
           AND TABLE_SCHEMA = 'dbo')
    EXEC sp_rename 'dbo.employee_profiles.staff_type', 'department', 'COLUMN';
GO

-- Rename staff_type → department in candidates
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'candidates'
           AND COLUMN_NAME = 'staff_type'
           AND TABLE_SCHEMA = 'dbo')
    EXEC sp_rename 'dbo.candidates.staff_type', 'department', 'COLUMN';
GO
