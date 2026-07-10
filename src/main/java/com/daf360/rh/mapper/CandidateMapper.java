package com.daf360.rh.mapper;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
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
    @Mapping(target = "fitScore",        ignore = true)
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
    @Mapping(target = "itProvisioning",               ignore = true)
    @Mapping(target = "recruitmentDemandJobTitle",    ignore = true)
    @Mapping(target = "employmentTypeLabel",           ignore = true)
    @Mapping(target = "nationalityId",        expression = "java(candidate.getNationality()    != null ? candidate.getNationality().getId()             : null)")
    @Mapping(target = "nationality",          expression = "java(candidate.getNationality()    != null ? candidate.getNationality().getLabelFr()        : null)")
    @Mapping(target = "appliedGradeId",       expression = "java(candidate.getAppliedGrade()   != null ? candidate.getAppliedGrade().getId()            : null)")
    @Mapping(target = "appliedGrade",         expression = "java(candidate.getAppliedGrade()   != null ? candidate.getAppliedGrade().getLabelFr()       : null)")
    @Mapping(target = "appliedDisciplineId",  expression = "java(candidate.getAppliedDiscipline() != null ? candidate.getAppliedDiscipline().getId()    : null)")
    @Mapping(target = "appliedDiscipline",    expression = "java(candidate.getAppliedDiscipline() != null ? candidate.getAppliedDiscipline().getLabelFr(): null)")
    @Mapping(target = "departmentId",         expression = "java(candidate.getDepartment()     != null ? candidate.getDepartment().getId()              : null)")
    @Mapping(target = "department",           expression = "java(candidate.getDepartment()     != null ? candidate.getDepartment().getLabelFr()         : null)")
    CandidateResponse toResponse(Candidate candidate);

    @Mapping(target = "appliedGrade",    expression = "java(candidate.getAppliedGrade() != null ? candidate.getAppliedGrade().getLabelFr() : null)")
    @Mapping(target = "fullName",        expression = "java(buildFullName(candidate))")
    @Mapping(target = "initials",        expression = "java(buildInitials(candidate))")
    @Mapping(target = "colorIndex",      expression = "java((int)(Math.abs(candidate.getId() == null ? 0L : candidate.getId()) % 8))")
    @Mapping(target = "poste",           source = "appliedPosition")
    @Mapping(target = "stage",           expression = "java(mapStage(candidate.getStatus()))")
    @Mapping(target = "fitScore",        expression = "java(com.daf360.rh.pipeline.PipelineSupport.fitScore(candidate.getStatus() != null ? candidate.getStatus().name() : null, candidate.getCvPath() != null, candidate.getExperienceYears()))")
    @Mapping(target = "contractType",    ignore = true) // resolved from employmentTypeId in CandidateService
    @Mapping(target = "applicationDate", expression = "java(candidate.getCreatedAt() != null ? candidate.getCreatedAt().toLocalDate().toString() : null)")
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
    @Mapping(target = "cvPath",                ignore = true)
    @Mapping(target = "cvOriginalName",        ignore = true)
    @Mapping(target = "cvUploadedAt",          ignore = true)
    @Mapping(target = "fitScore",              ignore = true)
    @Mapping(target = "recruitmentDemandId",   ignore = true)
    @Mapping(target = "employmentTypeId",      ignore = true)
    // FK dimension fields — resolved by CandidateService after mapping
    @Mapping(target = "nationality",           ignore = true)
    @Mapping(target = "appliedGrade",          ignore = true)
    @Mapping(target = "appliedDiscipline",     ignore = true)
    @Mapping(target = "department",            ignore = true)
    void updateEntity(@MappingTarget Candidate candidate, UpdateCandidateRequest request);

    // ── Computed helpers ───────────────────────────────────────────────────────

    default String buildFullName(Candidate c) {
        String f = c.getFirstName() != null ? c.getFirstName() : "";
        String l = c.getLastName()  != null ? c.getLastName()  : "";
        return (f + " " + l).trim();
    }

    default String buildInitials(Candidate c) {
        String f = c.getFirstName();
        String l = c.getLastName();
        String fi = (f != null && !f.isEmpty()) ? String.valueOf(f.charAt(0)).toUpperCase() : "";
        String li = (l != null && !l.isEmpty()) ? String.valueOf(l.charAt(0)).toUpperCase() : "";
        return fi + li;
    }

    /** Canonical stage label — delegates to PipelineSupport so the list and Kanban agree. */
    default String mapStage(CandidateStatus status) {
        return com.daf360.rh.pipeline.PipelineSupport.stageLabel(status);
    }
}
