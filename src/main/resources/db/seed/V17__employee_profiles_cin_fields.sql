-- V17: Add CIN city and date fields to employee_profiles (applied manually 2026-06-04)
ALTER TABLE [dbo].[employee_profiles] ADD
    cin_city NVARCHAR(100) NULL,
    cin_date NVARCHAR(50)  NULL;
