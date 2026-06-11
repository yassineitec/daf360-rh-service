package com.daf360.rh.mapper;

import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.WorkingTimeRegimeCreateDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeResponseDto;
import org.mapstruct.*;

@Mapper(componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface WorkingTimeRegimeMapper {

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "isActive",  constant = "true")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkingTimeRegime toEntity(WorkingTimeRegimeCreateDto dto);

    WorkingTimeRegimeResponseDto toDto(WorkingTimeRegime regime);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "paysId",    ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateFromDto(WorkingTimeRegimeCreateDto dto, @MappingTarget WorkingTimeRegime regime);
}
