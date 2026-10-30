package com.daf360.rh.dto.profile;

/**
 * The KPI row of /rh/profiles — counted over the default perimeter of
 * {@code GET /api/hr/profiles/employees}, narrowed by its {@code pays} filter only.
 *
 * @param total          every row the list shows for that pays, other filters off
 * @param recentHires    rows hired over the last 3 months, today included (a future hire
 *                       date is not counted yet)
 * @param withContract   rows whose profile has a contract type — CDI + CDD + the others
 * @param cdi            rows whose contract is of CDI nature (lifecycle_nature, else the code)
 * @param cdd            rows whose contract is of CDD nature (same rule)
 * @param active         rows with lifecycle_status ACTIVE
 * @param onLeave        rows with lifecycle_status ON_LEAVE
 * @param onMission      rows with lifecycle_status ON_MISSION
 */
public record EmployeeStatsDto(long total, long recentHires,
                               long withContract, long cdi, long cdd,
                               long active, long onLeave, long onMission) {}
