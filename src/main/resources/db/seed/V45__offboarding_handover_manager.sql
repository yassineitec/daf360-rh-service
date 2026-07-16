-- ============================================================
-- V45 — Add handover_manager_profile_id to offboarding instances
-- Created: 2026-07-16
-- ============================================================

ALTER TABLE [dbo].[offboarding_workflow_instances]
ADD [handover_manager_profile_id] BIGINT NULL;
GO
