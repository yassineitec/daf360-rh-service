package com.daf360.rh.mapper;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.dto.candidate.*;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CandidateMapper {

    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "status",          ignore = true)
    @Mapping(target = "rejectionReason", ignore = true)
    @Mapping(target = "createdBy",       ignore = true)
    @Mapping(target = "acceptedBy",      ignore = true)
    @Mapping(target = "acceptedAt",      ignore = true)
    @Mapping(target = "createdAt",       ignore = true)
    @Mapping(target = "updatedAt",       ignore = true)
    @Mapping(target = "cvPath",          ignore = true)
    @Mapping(target = "cvOriginalName",  ignore = true)
    @Mapping(target = "cvUploadedAt",    ignore = true)
    // FK dimension fields — resolved by CandidateService after mapping
    @Mapping(target = "nationality",     ignore = true)
    @Mapping(target = "appliedGrade",    ignore = true)
    @Mapping(target = "appliedDiscipline", ignore = true)
    @Mapping(target = "department",      ignore = true)
    Candidate toEntity(CreateCandidateRequest request);

    /**
     * Response DTO — derive label strings and IDs from FK entities.
     * itProvisioning is populated separately in CandidateService after loading ItProvisioning.
     */
    @Mapping(target = "itProvisioning",      ignore = true)
    @Mapping(target = "nationalityId",        expression = "java(candidate.getNationality()    != null ? candidate.getNationality().getId()             : null)")
    @Mapping(target = "nationality",          expression = "java(candidate.getNationality()    != null ? candidate.getNationality().getLabelFr()        : null)")
    @Mapping(target = "appliedGradeId",       expression = "java(candidate.getAppliedGrade()   != null ? candidate.getAppliedGrade().getId()            : null)")
    @Mapping(target = "appliedGrade",         expression = "java(candidate.getAppliedGrade()   != null ? candidate.getAppliedGrade().getLabelFr()       : null)")
    @Mapping(target = "appliedDisciplineId",  expression = "java(candidate.getAppliedDiscipline() != null ? candidate.getAppliedDiscipline().getId()    : null)")
    @Mapping(target = "appliedDiscipline",    expression = "java(candidate.getAppliedDiscipline() != null ? candidate.getAppliedDiscipline().getLabelFr(): null)")
    @Mapping(target = "departmentId",         expression = "java(candidate.getDepartment()     != null ? candidate.getDepartment().getId()              : null)")
    @Mapping(target = "department",           expression = "java(candidate.getDepartment()     != null ? candidate.getDepartment().getLabelFr()         : null)")
    CandidateResponse toResponse(Candidate candidate);

    @Mapping(target = "appliedGrade", expression = "java(candidate.getAppliedGrade() != null ? candidate.getAppliedGrade().getLabelFr() : null)")
    CandidateListItem toListItem(Candidate candidate);

    List<CandidateListItem> toListItems(List<Candidate> candidates);

    @Mapping(target = "assetsProvided", ignore = true)
    ItProvisioningSummary toItSummary(ItProvisioning provisioning);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "paysId",          ignore = true)
    @Mapping(target = "status",          ignore = true)
    @Mapping(target = "rejectionReason", ignore = true)
    @Mapping(target = "createdBy",       ignore = true)
    @Mapping(target = "acceptedBy",      ignore = true)
    @Mapping(target = "acceptedAt",      ignore = true)
    @Mapping(target = "createdAt",       ignore = true)
    @Mapping(target = "updatedAt",       ignore = true)
    @Mapping(target = "cvPath",          ignore = true)
    @Mapping(target = "cvOriginalName",  ignore = true)
    @Mapping(target = "cvUploadedAt",    ignore = true)
    // FK dimension fields — resolved by CandidateService after mapping
    @Mapping(target = "nationality",       ignore = true)
    @Mapping(target = "appliedGrade",      ignore = true)
    @Mapping(target = "appliedDiscipline", ignore = true)
    @Mapping(target = "department",        ignore = true)
    void updateEntity(@MappingTarget Candidate candidate, UpdateCandidateRequest request);
}
