-- ─────────────────────────────────────────────────────────────────────────────
-- Assistant & FAQ (Admin › Assistant & FAQ)
-- Questions / réponses saisies par les administrateurs RH, en plus de la FAQ livrée
-- avec l'application. L'assistant de chat et la page « Aide & guides » les affichent
-- pour la page indiquée (`page_key` = la clé de guide de la page, ex. 'home').
-- Français obligatoire ; anglais et arabe facultatifs (l'écran retombe sur le français).
-- Idempotent. À appliquer à la main (pas de Flyway).
-- ─────────────────────────────────────────────────────────────────────────────
SET NOCOUNT ON;

IF OBJECT_ID('dbo.help_faq', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.help_faq (
        id           BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_help_faq PRIMARY KEY,
        page_key     NVARCHAR(80)   NOT NULL,
        question_fr  NVARCHAR(300)  NOT NULL,
        answer_fr    NVARCHAR(2000) NOT NULL,
        question_en  NVARCHAR(300)  NULL,
        answer_en    NVARCHAR(2000) NULL,
        question_ar  NVARCHAR(300)  NULL,
        answer_ar    NVARCHAR(2000) NULL,
        order_index  INT            NOT NULL CONSTRAINT DF_help_faq_order  DEFAULT 0,
        is_active    BIT            NOT NULL CONSTRAINT DF_help_faq_active DEFAULT 1,
        created_at   DATETIMEOFFSET(6) NOT NULL,
        created_by   BIGINT         NULL,
        updated_at   DATETIMEOFFSET(6) NULL,
        updated_by   BIGINT         NULL
    );
    CREATE INDEX IX_help_faq_page ON dbo.help_faq (page_key, is_active, order_index);
END
