-- V34: Candidate → Employee Contract Bridge
-- 1. Removes the legacy contract_type string column from candidates
--    (replaced by employment_type_id FK to configurable_list_values, added in V32)
-- 2. Adds RH_HIRE_CANDIDATE permission and grants it to HR roles

-- ── Drop legacy column ────────────────────────────────────────────────────────
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidates' AND COLUMN_NAME = 'contract_type'
)
BEGIN
    -- Drop the CHECK constraint first (SQL Server requires this before dropping the column)
    DECLARE @ConstraintName NVARCHAR(256);
    SELECT @ConstraintName = cc.name
      FROM sys.check_constraints cc
      JOIN sys.columns           c  ON cc.parent_object_id = c.object_id
                                   AND cc.parent_column_id = c.column_id
      JOIN sys.tables            t  ON c.object_id         = t.object_id
     WHERE t.name = 'candidates' AND c.name = 'contract_type';

    IF @ConstraintName IS NOT NULL
        EXEC('ALTER TABLE [dbo].[candidates] DROP CONSTRAINT [' + @ConstraintName + ']');

    ALTER TABLE [dbo].[candidates] DROP COLUMN contract_type;
END;

-- ── RH_HIRE_CANDIDATE permission ──────────────────────────────────────────────
-- Grant to: Manager (id=2), Responsable RH (id=3), DRH (id=4), Administrateur (id=1)
-- Adjust role IDs to match your Roles table if they differ.
MERGE [dbo].[RolePermissions] AS target
USING (
    VALUES
        (1, 'RH_HIRE_CANDIDATE'),
        (2, 'RH_HIRE_CANDIDATE'),
        (3, 'RH_HIRE_CANDIDATE'),
        (4, 'RH_HIRE_CANDIDATE')
) AS source (role_id, permission)
ON target.role_id = source.role_id AND target.permission = source.permission
WHEN NOT MATCHED THEN
    INSERT (role_id, permission) VALUES (source.role_id, source.permission);
