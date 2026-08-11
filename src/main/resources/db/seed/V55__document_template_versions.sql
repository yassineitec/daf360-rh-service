CREATE TABLE [dbo].[document_template_versions] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL,
    [template_id]     BIGINT NOT NULL,
    [version_number]  INT NOT NULL,
    [html_content]    NVARCHAR(MAX) NOT NULL,
    [changed_by]      BIGINT NULL,
    [changed_at]      DATETIMEOFFSET(6) NOT NULL,
    [change_summary]  NVARCHAR(500) NULL,
    CONSTRAINT [PK_dtv] PRIMARY KEY ([id]),
    CONSTRAINT [FK_dtv_template] FOREIGN KEY ([template_id])
        REFERENCES [dbo].[document_templates]([id]) ON DELETE CASCADE
);
CREATE INDEX [IX_dtv_template] ON [dbo].[document_template_versions] ([template_id], [version_number] DESC);
