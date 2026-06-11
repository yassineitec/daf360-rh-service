-- V15: Add serial number (S/N) fields to hardware items in it_provisioning
-- Already applied manually on 2026-06-04.
ALTER TABLE [dbo].[it_provisioning] ADD
    laptop_sn          NVARCHAR(100) NULL,
    mouse_sn           NVARCHAR(100) NULL,
    keyboard_sn        NVARCHAR(100) NULL,
    screen_sn          NVARCHAR(100) NULL,
    headset_sn         NVARCHAR(100) NULL,
    docking_station_sn NVARCHAR(100) NULL;
