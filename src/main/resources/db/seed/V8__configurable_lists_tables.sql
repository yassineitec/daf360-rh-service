USE [DAF360_HR];

IF OBJECT_ID('dbo.configurable_list_types','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[configurable_list_types](
        [id]          BIGINT IDENTITY(1,1) NOT NULL,
        [code]        VARCHAR(100)         NOT NULL,
        [label_fr]    NVARCHAR(255)        NOT NULL,
        [label_en]    NVARCHAR(255)        NOT NULL,
        [description] NVARCHAR(500)        NULL,
        [is_per_pays] BIT                  NOT NULL DEFAULT 0,
        [is_system]   BIT                  NOT NULL DEFAULT 0,
        [created_at]  DATETIME2(6)         NOT NULL DEFAULT GETDATE(),
        CONSTRAINT [PK_configurable_list_types] PRIMARY KEY CLUSTERED([id] ASC),
        CONSTRAINT [UX_ConfigListType_Code]     UNIQUE([code])
    );
    PRINT 'Created: configurable_list_types';
END
ELSE PRINT 'Skipped: configurable_list_types';

IF OBJECT_ID('dbo.configurable_list_values','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[configurable_list_values](
        [id]           BIGINT IDENTITY(1,1) NOT NULL,
        [list_type_id] BIGINT               NOT NULL,
        [pays_id]      BIGINT               NULL,
        [value_code]   VARCHAR(100)         NOT NULL,
        [label_fr]     NVARCHAR(255)        NOT NULL,
        [label_en]     NVARCHAR(255)        NOT NULL,
        [sort_order]   INT                  NOT NULL DEFAULT 0,
        [is_active]    BIT                  NOT NULL DEFAULT 1,
        [is_system]    BIT                  NOT NULL DEFAULT 0,
        [created_by]   BIGINT               NULL,
        [created_at]   DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]   DATETIMEOFFSET(6)    NULL,
        CONSTRAINT [PK_configurable_list_values]     PRIMARY KEY CLUSTERED([id] ASC),
        CONSTRAINT [FK_ConfigListVal_Type]           FOREIGN KEY([list_type_id]) REFERENCES [dbo].[configurable_list_types]([id]),
        CONSTRAINT [FK_ConfigListVal_Pays]           FOREIGN KEY([pays_id])      REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_ConfigListVal_CreatedBy]      FOREIGN KEY([created_by])   REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [UX_ConfigListVal_Type_Pays_Code] UNIQUE([list_type_id],[pays_id],[value_code])
    );
    PRINT 'Created: configurable_list_values';
END
ELSE PRINT 'Skipped: configurable_list_values';

PRINT 'V8 complete.';
