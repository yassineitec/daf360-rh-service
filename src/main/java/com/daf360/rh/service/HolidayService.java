package com.daf360.rh.service;

import com.daf360.rh.domain.Holiday;
import com.daf360.rh.dto.admin.HolidayCreateDto;
import com.daf360.rh.dto.admin.HolidayResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.HolidayRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages [holidays] per pays.
 * Also provides isWorkingDay() by consulting [pays_weekends] (via JdbcTemplate).
 *
 * holidays.created_at is datetime2 (verified from DB) — entity uses LocalDateTime.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepo;
    private final AuditService      auditService;
    private final JdbcTemplate      jdbcTemplate;
    /** V74 per-role country scope — see assertInScope. */
    private final TenantService     tenantService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Entities this caller may read or edit holidays for, in week-order of the reference list.
     *
     * Follows {@code ReferenceDataController.readableP()}: ALL means no filter, LIST/OWN means
     * exactly those entities, and an UNRESOLVED scope is permissive — a token with no pays
     * claim gets today's behaviour rather than an empty screen, which is a louder failure than
     * a leak on data every employee can see anyway.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> scopedPays() {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        String where = scope.unfiltered() ? "" : " AND p.id IN (:ids) ";
        String sql = """
                SELECT p.id, p.french_label, p.english_label, p.iso_code
                FROM [dbo].[pays] p
                WHERE ISNULL(p.deleted, 0) = 0
                  AND (EXISTS (SELECT 1 FROM [dbo].[Users] u WHERE u.pays_id = p.id)
                       OR EXISTS (SELECT 1 FROM [dbo].[holidays] h WHERE h.pays_id = p.id))
                """ + where + " ORDER BY p.french_label";
        if (scope.unfiltered()) {
            return jdbcTemplate.queryForList(sql);
        }
        String ids = scope.paysIds().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1");
        return jdbcTemplate.queryForList(sql.replace(":ids", ids));
    }

    /**
     * Refuses an entity outside the caller's scope.
     *
     * Applied to every write: the permission says "may edit holidays", it does not say "may
     * edit ANY country's holidays", and until now a CREATE_HOLIDAY holder could post a
     * paysId for a country they have nothing to do with.
     */
    private void assertInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.unfiltered() || paysId == null) return;
        if (!scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette entité n'est pas dans votre périmètre.");
        }
    }

    @Transactional(readOnly = true)
    public List<HolidayResponseDto> list(Long paysId, Integer year) {
        assertInScope(paysId);
        List<Holiday> holidays;
        if (year != null) {
            LocalDate from = LocalDate.of(year, 1, 1);
            LocalDate to   = LocalDate.of(year, 12, 31);
            holidays = holidayRepo.findByPaysIdAndDateHolidayBetween(paysId, from, to);
        } else {
            holidays = holidayRepo.findByPaysId(paysId);
        }
        return holidays.stream().map(this::toDto).toList();
    }

    public HolidayResponseDto create(HolidayCreateDto dto, Authentication auth) {
        assertInScope(dto.getPaysId());
        if (holidayRepo.existsByPaysIdAndDateHoliday(dto.getPaysId(), dto.getDateHoliday())) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Un jour férié existe déjà pour le " + dto.getDateHoliday());
        }
        Holiday h = Holiday.builder()
                .paysId(dto.getPaysId())
                .dateHoliday(dto.getDateHoliday())
                .frenchLabel(dto.getFrenchLabel())
                .englishLabel(dto.getEnglishLabel())
                .isRecurring(dto.getIsRecurring() != null && dto.getIsRecurring())
                .deleted(false)
                .createdAt(LocalDateTime.now())
                .build();
        Holiday saved = holidayRepo.save(h);
        auditService.log(actorId(auth), "CREATE_HOLIDAY", "Holiday", saved.getId(),
                null, saved.getDateHoliday().toString());
        return toDto(saved);
    }

    public HolidayResponseDto update(Long id, HolidayCreateDto dto, Authentication auth) {
        Holiday h = findOrThrow(id);
        // The row decides the entity, not the body: an id names a holiday that already
        // belongs to a country, and moving it elsewhere is not something this screen does.
        assertInScope(h.getPaysId());
        h.setFrenchLabel(dto.getFrenchLabel());
        h.setEnglishLabel(dto.getEnglishLabel());
        if (dto.getIsRecurring() != null) h.setIsRecurring(dto.getIsRecurring());
        h.setUpdatedAt(LocalDateTime.now());
        Holiday saved = holidayRepo.save(h);
        auditService.log(actorId(auth), "UPDATE_HOLIDAY", "Holiday", id, null, null);
        return toDto(saved);
    }

    public void delete(Long id, Authentication auth) {
        Holiday h = findOrThrow(id);
        assertInScope(h.getPaysId());
        h.setDeleted(true);
        h.setDeletedAt(LocalDateTime.now());
        holidayRepo.save(h);
        auditService.log(actorId(auth), "DELETE_HOLIDAY", "Holiday", id, null, null);
    }

    // ── isWorkingDay ──────────────────────────────────────────────────────────

    /**
     * Returns true if the given date is a working day for this pays:
     *   - Not in pays_weekends for this pays_id
     *   - Not in holidays table for this pays_id
     */
    @Transactional(readOnly = true)
    public boolean isWorkingDay(LocalDate date, Long paysId) {
        // 1. Check pays_weekends
        Set<String> weekendDays = jdbcTemplate.queryForList(
                "SELECT UPPER(ISNULL(day,'')) FROM [dbo].[pays_weekends] WHERE pays_id = ?",
                String.class, paysId).stream().collect(Collectors.toSet());

        String dow = date.getDayOfWeek().name(); // "MONDAY", "SATURDAY", …
        if (weekendDays.contains(dow)) return false;

        // Also handle numeric representations stored in some Timesheet setups
        int dowNum = date.getDayOfWeek().getValue(); // 1=Mon … 7=Sun
        if (weekendDays.contains(String.valueOf(dowNum))) return false;

        // Fallback: if no pays_weekends rows, treat Sat+Sun as weekend
        if (weekendDays.isEmpty()) {
            DayOfWeek d = date.getDayOfWeek();
            if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        }

        // 2. Check public holiday
        return !holidayRepo.existsByPaysIdAndDateHoliday(paysId, date);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Holiday findOrThrow(Long id) {
        return holidayRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.HOLIDAY_NOT_FOUND, "Jour férié introuvable: id=" + id));
    }

    HolidayResponseDto toDto(Holiday h) {
        HolidayResponseDto dto = new HolidayResponseDto();
        dto.setId(h.getId());
        dto.setPaysId(h.getPaysId());
        dto.setDateHoliday(h.getDateHoliday());
        dto.setFrenchLabel(h.getFrenchLabel());
        dto.setEnglishLabel(h.getEnglishLabel());
        dto.setIsRecurring(h.getIsRecurring());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
