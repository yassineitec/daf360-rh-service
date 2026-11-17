package com.daf360.rh.regime;

import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.WorkingTimeRegimeCreateDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeResponseDto;
import com.daf360.rh.mapper.WorkingTimeRegimeMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every column the admin form fills must survive the mapper in both directions. The create
 * DTO used to lack description / break / overtime / daily cap, and MapStruct (unmapped target
 * policy IGNORE) dropped them without a word.
 */
class WorkingTimeRegimeMapperTest {

    private final WorkingTimeRegimeMapper mapper = Mappers.getMapper(WorkingTimeRegimeMapper.class);

    private static WorkingTimeRegimeCreateDto fullDto() {
        WorkingTimeRegimeCreateDto dto = new WorkingTimeRegimeCreateDto();
        dto.setPaysId(178L);
        dto.setCode("TUN_STD");
        dto.setLabelFr("Standard");
        dto.setLabelEn("Standard EN");
        dto.setDescriptionFr("Desc FR");
        dto.setDescriptionEn("Desc EN");
        dto.setHoursPerWeek(new BigDecimal("40"));
        dto.setDaysPerWeek(5);
        dto.setStartTime(LocalTime.of(8, 15));
        dto.setEndTime(LocalTime.of(17, 45));
        dto.setBreakDurationMin(60);
        dto.setOvertimeAllowed(true);
        dto.setMaxHoursPerDay(new BigDecimal("10.0"));
        dto.setIsFlexible(false);
        dto.setIsDefault(true);
        return dto;
    }

    @Test
    void create_keepsEveryFormField() {
        WorkingTimeRegime r = mapper.toEntity(fullDto());

        assertThat(r.getDescriptionFr()).isEqualTo("Desc FR");
        assertThat(r.getDescriptionEn()).isEqualTo("Desc EN");
        assertThat(r.getBreakDurationMin()).isEqualTo(60);
        assertThat(r.getOvertimeAllowed()).isTrue();
        assertThat(r.getMaxHoursPerDay()).isEqualByComparingTo("10.0");
        assertThat(r.getStartTime()).isEqualTo(LocalTime.of(8, 15));
        assertThat(r.getIsActive()).isTrue();
    }

    @Test
    void update_writesEveryFormField() {
        WorkingTimeRegime r = mapper.toEntity(new WorkingTimeRegimeCreateDto() {{
            setPaysId(178L); setCode("X"); setLabelFr("X"); setLabelEn("X");
            setHoursPerWeek(new BigDecimal("39")); setDaysPerWeek(5);
        }});

        mapper.updateFromDto(fullDto(), r);

        assertThat(r.getDescriptionFr()).isEqualTo("Desc FR");
        assertThat(r.getBreakDurationMin()).isEqualTo(60);
        assertThat(r.getOvertimeAllowed()).isTrue();
        assertThat(r.getMaxHoursPerDay()).isEqualByComparingTo("10.0");
    }

    @Test
    void response_carriesEveryFormField() {
        WorkingTimeRegimeResponseDto out = mapper.toDto(mapper.toEntity(fullDto()));

        assertThat(out.getDescriptionFr()).isEqualTo("Desc FR");
        assertThat(out.getDescriptionEn()).isEqualTo("Desc EN");
        assertThat(out.getBreakDurationMin()).isEqualTo(60);
        assertThat(out.getOvertimeAllowed()).isTrue();
        assertThat(out.getMaxHoursPerDay()).isEqualByComparingTo("10.0");
    }
}
