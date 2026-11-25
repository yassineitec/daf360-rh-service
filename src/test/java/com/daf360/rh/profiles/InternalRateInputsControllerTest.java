package com.daf360.rh.profiles;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.controller.InternalRateInputsController;
import com.daf360.rh.controller.InternalRateInputsController.RateInputEmployeeDto;
import com.daf360.rh.domain.Discipline;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.Grade;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.RegimeResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class InternalRateInputsControllerTest {

    private final EmployeeProfileRepository repo = mock(EmployeeProfileRepository.class);
    private final RegimeResolutionService regimes = mock(RegimeResolutionService.class);
    private final AppProperties props = new AppProperties();
    private final InternalRateInputsController controller =
            new InternalRateInputsController(repo, regimes, props);

    @BeforeEach
    void setUp() {
        props.setInternalApiKey("secret");
    }

    private EmployeeProfile profile(long id, long userId, String gradeCode, String disciplineCode) {
        EmployeeProfile p = new EmployeeProfile();
        p.setId(id);
        p.setUserId(userId);
        p.setPaysId(179L);
        p.setLifecycleStatus(LifecycleStatus.ACTIVE);
        Grade g = new Grade();
        g.setCode(gradeCode);
        g.setLabelFr(gradeCode + " FR");
        p.setGrade(g);
        Discipline d = new Discipline();
        d.setCode(disciplineCode);
        d.setLabelFr(disciplineCode + " FR");
        p.setDiscipline(d);
        return p;
    }

    @Test
    void returnsActiveAndOnMissionStaff_withTrimmedStaffTypeAndRegimeHours() {
        when(repo.findByPaysIdAndLifecycleStatusIn(179L,
                EnumSet.of(LifecycleStatus.ACTIVE, LifecycleStatus.ON_MISSION)))
                .thenReturn(List.of(profile(56, 8, "G3", "CIVIL"), profile(57, 9, "G1", "ELEC")));
        when(repo.findStaffTypesByPaysId(179L)).thenReturn(List.of(
                new Object[]{56L, "Technical "}, new Object[]{57L, "Support"}));
        ResolvedRegimeDto regime = new ResolvedRegimeDto();
        regime.setHoursPerWeek(new BigDecimal("40"));
        when(regimes.resolveForEmployee(56L)).thenReturn(regime);
        when(regimes.resolveForEmployee(57L)).thenThrow(new RuntimeException("no regime"));

        List<RateInputEmployeeDto> rows = controller.employees(179L, "secret");

        assertThat(rows).hasSize(2);
        RateInputEmployeeDto first = rows.get(0);
        assertThat(first.profileId()).isEqualTo(56L);
        assertThat(first.userId()).isEqualTo(8L);
        assertThat(first.staffType()).isEqualTo("Technical");
        assertThat(first.gradeCode()).isEqualTo("G3");
        assertThat(first.gradeLabel()).isEqualTo("G3 FR");
        assertThat(first.disciplineCode()).isEqualTo("CIVIL");
        assertThat(first.regimeHoursPerWeek()).isEqualByComparingTo("40");
        assertThat(rows.get(1).staffType()).isEqualTo("Support");
        assertThat(rows.get(1).regimeHoursPerWeek()).isNull();
    }

    @Test
    void wrongKey_isForbidden() {
        assertThatThrownBy(() -> controller.employees(179L, "nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(repo);
    }

    @Test
    void blankConfiguredKey_failsClosed() {
        props.setInternalApiKey("");
        assertThatThrownBy(() -> controller.employees(179L, ""))
                .isInstanceOf(ResponseStatusException.class);
        verify(regimes, never()).resolveForEmployee(anyLong());
    }
}
