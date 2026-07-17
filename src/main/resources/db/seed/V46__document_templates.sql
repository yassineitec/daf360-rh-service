-- V46 — Document template management
-- Stores HR document templates (HTML with {{variable}} placeholders) per pays.

CREATE TABLE [dbo].[document_templates] (
    [id]           BIGINT         IDENTITY(1,1) NOT NULL,
    [pays_id]      BIGINT         NOT NULL,
    [category]     NVARCHAR(50)   NOT NULL,        -- ATTESTATION | CONTRACT | LETTRE | AUTRE
    [name]         NVARCHAR(200)  NOT NULL,
    [description]  NVARCHAR(500)  NULL,
    [html_content] NVARCHAR(MAX)  NOT NULL,
    [variables]    NVARCHAR(MAX)  NULL,             -- JSON array of detected {{key}} tokens
    [page_size]    NVARCHAR(10)   NOT NULL DEFAULT ('A4'),
    [is_active]    BIT            NOT NULL DEFAULT (1),
    [created_by]   BIGINT         NULL,
    [created_at]   DATETIMEOFFSET NOT NULL DEFAULT (SYSDATETIMEOFFSET()),
    [updated_at]   DATETIMEOFFSET NULL,
    CONSTRAINT [PK_document_templates] PRIMARY KEY CLUSTERED ([id] ASC)
);
GO

CREATE INDEX [IX_doctmpl_pays_cat] ON [dbo].[document_templates] ([pays_id], [category]);
GO
