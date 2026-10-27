-- V101__add_personal_phone_column.sql
--
-- EmployeeProfile.personalPhone (split out from the old shared `phone` column, per that
-- field's own doc comment) was added to the entity without a corresponding schema change,
-- crash-looping rh-service on startup: ProfilePhotoWarmupService's ApplicationReadyEvent
-- listener runs a findAll() over employee_profiles, and Hibernate's SELECT list includes
-- every mapped column — an unmapped column throws immediately at startup, not lazily.

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'personal_phone'
)
BEGIN
    ALTER TABLE [dbo].[employee_profiles]
        ADD [personal_phone] NVARCHAR(50) NULL;
END
