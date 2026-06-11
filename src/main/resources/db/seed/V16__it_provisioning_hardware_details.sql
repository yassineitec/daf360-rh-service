-- V16: Add Brand/Model, Asset Tag and Status fields to each hardware item
-- Already applied manually on 2026-06-04.
ALTER TABLE [dbo].[it_provisioning] ADD
    laptop_brand              NVARCHAR(150) NULL,
    laptop_asset_tag          NVARCHAR(100) NULL,
    laptop_status             NVARCHAR(50)  NULL,
    mouse_brand               NVARCHAR(150) NULL,
    mouse_asset_tag           NVARCHAR(100) NULL,
    mouse_status              NVARCHAR(50)  NULL,
    keyboard_brand            NVARCHAR(150) NULL,
    keyboard_asset_tag        NVARCHAR(100) NULL,
    keyboard_status           NVARCHAR(50)  NULL,
    screen_brand              NVARCHAR(150) NULL,
    screen_asset_tag          NVARCHAR(100) NULL,
    screen_status             NVARCHAR(50)  NULL,
    headset_brand             NVARCHAR(150) NULL,
    headset_asset_tag         NVARCHAR(100) NULL,
    headset_status            NVARCHAR(50)  NULL,
    docking_station_brand     NVARCHAR(150) NULL,
    docking_station_asset_tag NVARCHAR(100) NULL,
    docking_station_status    NVARCHAR(50)  NULL;
-- Status allowed values (enforced in application layer):
-- NEUF | BON_ETAT | USAGE | EN_REPARATION | DEFECTUEUX