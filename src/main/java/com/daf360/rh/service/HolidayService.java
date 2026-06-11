package com.daf360.rh.service;

import com.daf360.rh.domain.Holiday;
import com.daf360.rh.dto.admin.HolidayCreateDto;
import com.daf360.rh.dto.admin.HolidayResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<HolidayResponseDto> list(Long paysId, Integer year) {
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
