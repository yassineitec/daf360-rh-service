package com.daf360.rh.leave;

import com.daf360.rh.domain.LeaveBalance;
import com.daf360.rh.domain.enums.LeaveType;
import com.daf360.rh.dto.leave.AdjustBalanceDto;
import com.daf360.rh.dto.leave.LeaveBalanceResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.LeaveBalanceRepository;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.LeaveBalanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for LeaveBalanceService balance arithmetic.
 * No Spring context, no database — all repositories mocked with Mockito.
 */
@ExtendWith(MockitoExtension.class)
class LeaveBalanceServiceTest {

    @Mock  LeaveBalanceRepository balanceRepo;
    @Mock  AuditService           auditService;
    @InjectMocks LeaveBalanceService service;

    private static final Long   PROFILE_ID  = 1L;
    private static final Long   BALANCE_ID  = 10L;
    private static final int    YEAR        = 2026;
    private static final String LEAVE_CONGE = LeaveType.CONGE.name();

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LeaveBalance balanceWith(BigDecimal acquis, BigDecimal pris) {
        BigDecimal restants = acquis.subtract(pris);
        return LeaveBalance.builder()
                .id(BALANCE_ID)
                .employeeProfileId(PROFILE_ID)
                .annee(YEAR)
                .leaveType(LEAVE_CONGE)
                .joursAcquis(acquis)
                .joursPris(pris)
                .joursRestants(restants)
                .derniereMaj(OffsetDateTime.now())
                .build();
    }

    // ── initializeBalances ────────────────────────────────────────────────────

    @Test
    void initializeBalances_createsOneRecordPerLeaveType() {
        when(balanceRepo.existsByEmployeeProfileIdAndAnneeAndLeaveType(
                anyLong(), anyInt(), anyString())).thenReturn(false);
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initializeBalances(PROFILE_ID, YEAR);

        int expectedTypes = LeaveType.values().length;
        verify(balanceRepo, times(expectedTypes)).save(any(LeaveBalance.class));
    }

    @Test
    void initializeBalances_skipsExistingTypes() {
        // CONGE already exists, all others don't
        when(balanceRepo.existsByEmployeeProfileIdAndAnneeAndLeaveType(
                eq(PROFILE_ID), eq(YEAR), eq(LEAVE_CONGE))).thenReturn(true);
        when(balanceRepo.existsByEmployeeProfileIdAndAnneeAndLeaveType(
                eq(PROFILE_ID), eq(YEAR), argThat(s -> !LEAVE_CONGE.equals(s)))).thenReturn(false);
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initializeBalances(PROFILE_ID, YEAR);

        verify(balanceRepo, times(LeaveType.values().length - 1)).save(any());
    }

    @Test
    void initializeBalances_setsCorrectDefaultForConge() {
        when(balanceRepo.existsByEmployeeProfileIdAndAnneeAndLeaveType(anyLong(), anyInt(), anyString()))
                .thenReturn(false);
        ArgumentCaptor<LeaveBalance> captor = ArgumentCaptor.forClass(LeaveBalance.class);
        when(balanceRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.initializeBalances(PROFILE_ID, YEAR);

        LeaveBalance conge = captor.getAllValues().stream()
                .filter(b -> LEAVE_CONGE.equals(b.getLeaveType()))
                .findFirst().orElseThrow();

        assertThat(conge.getJoursAcquis()).isEqualByComparingTo(
                LeaveBalanceService.DEFAULT_DAYS.get(LeaveType.CONGE));
        assertThat(conge.getJoursPris()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(conge.getJoursRestants()).isEqualByComparingTo(
                LeaveBalanceService.DEFAULT_DAYS.get(LeaveType.CONGE));
    }

    // ── adjustBalance ─────────────────────────────────────────────────────────

    @Test
    void adjustBalance_positveDelta_increasesAcquisAndRestants() {
        LeaveBalance balance = balanceWith(new BigDecimal("30"), new BigDecimal("5"));
        when(balanceRepo.findById(BALANCE_ID)).thenReturn(Optional.of(balance));
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustBalanceDto dto = new AdjustBalanceDto();
        dto.setDelta(new BigDecimal("5"));
        dto.setReason("Régularisation");

        LeaveBalanceResponseDto result = service.adjustBalance(BALANCE_ID, dto, null);

        assertThat(result.getJoursAcquis()).isEqualByComparingTo("35");
        assertThat(result.getJoursRestants()).isEqualByComparingTo("30");  // was 25, +5 → 30
        verify(auditService).log(any(), eq("ADJUST_BALANCE"), any(), any(), any(), any());
    }

    @Test
    void adjustBalance_negativeDelta_decreasesAcquisAndRestants() {
        LeaveBalance balance = balanceWith(new BigDecimal("30"), BigDecimal.ZERO);
        when(balanceRepo.findById(BALANCE_ID)).thenReturn(Optional.of(balance));
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdjustBalanceDto dto = new AdjustBalanceDto();
        dto.setDelta(new BigDecimal("-10"));
        dto.setReason("Correction");

        LeaveBalanceResponseDto result = service.adjustBalance(BALANCE_ID, dto, null);

        assertThat(result.getJoursAcquis()).isEqualByComparingTo("20");
        assertThat(result.getJoursRestants()).isEqualByComparingTo("20");
    }

    @Test
    void adjustBalance_unknownId_throwsNotFound() {
        when(balanceRepo.findById(99L)).thenReturn(Optional.empty());

        AdjustBalanceDto dto = new AdjustBalanceDto();
        dto.setDelta(BigDecimal.ONE);
        dto.setReason("Test");

        assertThatThrownBy(() -> service.adjustBalance(99L, dto, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_BALANCE_NOT_FOUND);
    }

    // ── deduct ────────────────────────────────────────────────────────────────

    @Test
    void deduct_sufficientBalance_deductsCorrectly() {
        LeaveBalance balance = balanceWith(new BigDecimal("30"), new BigDecimal("5"));
        when(balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(PROFILE_ID, YEAR, LEAVE_CONGE))
                .thenReturn(Optional.of(balance));
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deduct(PROFILE_ID, LEAVE_CONGE, YEAR, new BigDecimal("10"));

        ArgumentCaptor<LeaveBalance> captor = ArgumentCaptor.forClass(LeaveBalance.class);
        verify(balanceRepo).save(captor.capture());

        LeaveBalance saved = captor.getValue();
        assertThat(saved.getJoursPris()).isEqualByComparingTo("15");       // 5 + 10
        assertThat(saved.getJoursRestants()).isEqualByComparingTo("15");   // 30 - 15
    }

    @Test
    void deduct_insufficientBalance_throwsException() {
        LeaveBalance balance = balanceWith(new BigDecimal("5"), new BigDecimal("3"));
        when(balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(PROFILE_ID, YEAR, LEAVE_CONGE))
                .thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> service.deduct(PROFILE_ID, LEAVE_CONGE, YEAR, new BigDecimal("10")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_BALANCE_INSUFFICIENT);

        verify(balanceRepo, never()).save(any());
    }

    @Test
    void deduct_balanceNotFound_throwsException() {
        when(balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(anyLong(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deduct(PROFILE_ID, LEAVE_CONGE, YEAR, BigDecimal.ONE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_BALANCE_NOT_FOUND);
    }

    // ── restore ───────────────────────────────────────────────────────────────

    @Test
    void restore_revertsDeductedDays() {
        LeaveBalance balance = balanceWith(new BigDecimal("30"), new BigDecimal("10"));
        when(balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(PROFILE_ID, YEAR, LEAVE_CONGE))
                .thenReturn(Optional.of(balance));
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.restore(PROFILE_ID, LEAVE_CONGE, YEAR, new BigDecimal("10"));

        ArgumentCaptor<LeaveBalance> captor = ArgumentCaptor.forClass(LeaveBalance.class);
        verify(balanceRepo).save(captor.capture());

        LeaveBalance saved = captor.getValue();
        assertThat(saved.getJoursPris()).isEqualByComparingTo("0");        // 10 - 10
        assertThat(saved.getJoursRestants()).isEqualByComparingTo("30");   // 30 - 0
    }

    @Test
    void restore_doesNotGoBelowZero() {
        // Restoring more than was taken — joursPris should floor at 0
        LeaveBalance balance = balanceWith(new BigDecimal("30"), new BigDecimal("3"));
        when(balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(PROFILE_ID, YEAR, LEAVE_CONGE))
                .thenReturn(Optional.of(balance));
        when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.restore(PROFILE_ID, LEAVE_CONGE, YEAR, new BigDecimal("10"));

        ArgumentCaptor<LeaveBalance> captor = ArgumentCaptor.forClass(LeaveBalance.class);
        verify(balanceRepo).save(captor.capture());

        assertThat(captor.getValue().getJoursPris()).isEqualByComparingTo("0");
    }

    @Test
    void restore_silentlyIgnoresMissingBalance() {
        when(balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(anyLong(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        // Must not throw
        assertThatNoException().isThrownBy(
                () -> service.restore(PROFILE_ID, LEAVE_CONGE, YEAR, BigDecimal.ONE));
        verify(balanceRepo, never()).save(any());
    }
}
