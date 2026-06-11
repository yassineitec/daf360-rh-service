package com.daf360.rh.mapper;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.profile.EmployeeProfileCreateDto;
import com.daf360.rh.dto.profile.EmployeeProfileResponseDto;
import com.daf360.rh.dto.profile.EmployeeProfileSummaryDto;
import com.daf360.rh.dto.profile.EmployeeProfileUpdateDto;
import org.mapstruct.*;

@Mapper(componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        imports = LifecycleStatus.class)
public interface EmployeeProfileMapper {

    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "lifecycleStatus", expression = "java(LifecycleStatus.PRE_ONBOARDING)")
    @Mapping(target = "deleted",         constant = "false")
    @Mapping(target = "isOnProbation",   constant = "false")
    @Mapping(target = "createdAt",       ignore = true)
    @Mapping(target = "updatedAt",       ignore = true)
    @Mapping(target = "deletedAt",       ignore = true)
    // FK dimension fields — resolved by service after mapping
    @Mapping(target = "nationality",     ignore = true)
    @Mapping(target = "grade",           ignore = true)
    @Mapping(target = "discipline",      ignore = true)
    @Mapping(target = "nogLevel",        ignore = true)
    @Mapping(target = "department",      ignore = true)
    @Mapping(target = "bank",            ignore = true)
    EmployeeProfile toEntity(EmployeeProfileCreateDto dto);

    // ── Response DTO — derive label strings and IDs from FK entities ──────────
    @Mapping(target = "nationality",   expression = "java(profile.getNationality() != null ? profile.getNationality().getLabelFr() : null)")
    @Mapping(target = "nationalityId", expression = "java(profile.getNationality() != null ? profile.getNationality().getId() : null)")
    @Mapping(target = "grade",         expression = "java(profile.getGrade() != null ? profile.getGrade().getLabelFr() : null)")
    @Mapping(target = "gradeId",       expression = "java(profile.getGrade() != null ? profile.getGrade().getId() : null)")
    @Mapping(target = "discipline",    expression = "java(profile.getDiscipline() != null ? profile.getDiscipline().getLabelFr() : null)")
    @Mapping(target = "disciplineId",  expression = "java(profile.getDiscipline() != null ? profile.getDiscipline().getId() : null)")
    @Mapping(target = "nogLevel",      expression = "java(profile.getNogLevel() != null ? profile.getNogLevel().getLabelFr() : null)")
    @Mapping(target = "nogLevelId",    expression = "java(profile.getNogLevel() != null ? profile.getNogLevel().getId() : null)")
    @Mapping(target = "department",    expression = "java(profile.getDepartment() != null ? profile.getDepartment().getLabelFr() : null)")
    @Mapping(target = "departmentId",  expression = "java(profile.getDepartment() != null ? profile.getDepartment().getId() : null)")
    @Mapping(target = "bankName",      expression = "java(profile.getBank() != null ? profile.getBank().getLabelFr() : null)")
    @Mapping(target = "bankId",        expression = "java(profile.getBank() != null ? profile.getBank().getId() : null)")
    EmployeeProfileResponseDto toResponseDto(EmployeeProfile profile);

    @Mapping(target = "department", expression = "java(profile.getDepartment() != null ? profile.getDepartment().getLabelFr() : null)")
    @Mapping(target = "grade",      expression = "java(profile.getGrade() != null ? profile.getGrade().getLabelFr() : null)")
    EmployeeProfileSummaryDto toSummaryDto(EmployeeProfile profile);

    /**
     * PATCH — only non-null fields in dto are applied to the entity.
     * FK dimension fields (gradeId, disciplineId, etc.) are handled manually
     * in EmployeeProfileService.updateProfile() via repository lookups.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "userId",          ignore = true)
    @Mapping(target = "paysId",          ignore = true)
    @Mapping(target = "lifecycleStatus", ignore = true)
    @Mapping(target = "deleted",         ignore = true)
    @Mapping(target = "createdAt",       ignore = true)
    @Mapping(target = "updatedAt",       ignore = true)
    @Mapping(target = "deletedAt",       ignore = true)
    @Mapping(target = "nationality",     ignore = true)
    @Mapping(target = "grade",           ignore = true)
    @Mapping(target = "discipline",      ignore = true)
    @Mapping(target = "nogLevel",        ignore = true)
    @Mapping(target = "department",      ignore = true)
    @Mapping(target = "bank",            ignore = true)
    void updateEntityFromDto(EmployeeProfileUpdateDto dto, @MappingTarget EmployeeProfile profile);
}
