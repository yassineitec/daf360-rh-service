package com.daf360.rh.service;

import com.daf360.rh.domain.BreakLegalRule;
import com.daf360.rh.domain.BreakTemplate;
import com.daf360.rh.dto.break_.BreakLegalRuleDto;
import com.daf360.rh.dto.break_.BreakTemplateDto;
import com.daf360.rh.dto.break_.CreateBreakLegalRuleRequest;
import com.daf360.rh.dto.break_.CreateBreakTemplateRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.BreakLegalRuleRepository;
import com.daf360.rh.repository.BreakTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class BreakTemplateService {

    private final BreakTemplateRepository templateRepo;
    private final BreakLegalRuleRepository legalRuleRepo;

    // ── Break Templates ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BreakTemplateDto> getTemplatesForRegime(Long regimeId) {
        return templateRepo.findByRegimeIdAndIsActiveTrueOrderBySortOrderAsc(regimeId)
                .stream().map(this::toTemplateDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BreakTemplateDto> getTemplatesForPays(Long paysId) {
        return templateRepo.findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(paysId)
                .stream().map(this::toTemplateDto).collect(Collectors.toList());
    }

    public BreakTemplateDto createTemplate(CreateBreakTemplateRequest req) {
        if (req.getPaysId() == null || req.getRegimeId() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "paysId et regimeId sont obligatoires");
        }
        if (!templateRepo.regimeBelongsToPays(req.getRegimeId(), req.getPaysId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le régime sélectionné n'appartient pas à cette entité. "
                    + "Impossible de créer une pause pour un régime d'une autre entité.");
        }
        BreakTemplate template = BreakTemplate.builder()
                .paysId(req.getPaysId())
                .regimeId(req.getRegimeId())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .deductionType(req.getDeductionType() != null ? req.getDeductionType() : "AUTO")
                .durationMin(req.getDurationMin())
                .appliesToDays(req.getAppliesToDays() != null ? req.getAppliesToDays() : "ALL")
                .minWorkHoursTrigger(req.getMinWorkHoursTrigger())
                .breakTimeStart(req.getBreakTimeStart())
                .breakTimeEnd(req.getBreakTimeEnd())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        return toTemplateDto(templateRepo.save(template));
    }

    public void deleteTemplate(Long id) {
        BreakTemplate t = templateRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Template introuvable: " + id));
        t.setIsActive(false);
        templateRepo.save(t);
    }

    // ── Legal Rules ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BreakLegalRuleDto> getLegalRules(Long paysId) {
        return legalRuleRepo.findByPaysIdAndIsActiveTrueOrderByMinWorkHoursAsc(paysId)
                .stream().map(this::toRuleDto).collect(Collectors.toList());
    }

    public BreakLegalRuleDto createLegalRule(CreateBreakLegalRuleRequest req) {
        if (req.getEffectiveFrom() == null) req.setEffectiveFrom(LocalDate.now());
        BreakLegalRule rule = BreakLegalRule.builder()
                .paysId(req.getPaysId())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .minWorkHours(req.getMinWorkHours())
                .maxWorkHours(req.getMaxWorkHours())
                .deductionMin(req.getDeductionMin())
                .appliesToDays(req.getAppliesToDays() != null ? req.getAppliesToDays() : "ALL")
                .effectiveFrom(req.getEffectiveFrom())
                .effectiveTo(req.getEffectiveTo())
                .build();
        return toRuleDto(legalRuleRepo.save(rule));
    }

    public void deleteLegalRule(Long id) {
        BreakLegalRule r = legalRuleRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Règle introuvable: " + id));
        r.setIsActive(false);
        legalRuleRepo.save(r);
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private BreakTemplateDto toTemplateDto(BreakTemplate t) {
        return BreakTemplateDto.builder()
                .id(t.getId()).paysId(t.getPaysId()).regimeId(t.getRegimeId())
                .labelFr(t.getLabelFr()).labelEn(t.getLabelEn())
                .deductionType(t.getDeductionType()).durationMin(t.getDurationMin())
                .appliesToDays(t.getAppliesToDays())
                .minWorkHoursTrigger(t.getMinWorkHoursTrigger())
                .breakTimeStart(t.getBreakTimeStart())
                .breakTimeEnd(t.getBreakTimeEnd())
                .sortOrder(t.getSortOrder()).isActive(t.getIsActive()).build();
    }

    private BreakLegalRuleDto toRuleDto(BreakLegalRule r) {
        return BreakLegalRuleDto.builder()
                .id(r.getId()).paysId(r.getPaysId())
                .labelFr(r.getLabelFr()).labelEn(r.getLabelEn())
                .minWorkHours(r.getMinWorkHours()).maxWorkHours(r.getMaxWorkHours())
                .deductionMin(r.getDeductionMin()).appliesToDays(r.getAppliesToDays())
                .effectiveFrom(r.getEffectiveFrom()).effectiveTo(r.getEffectiveTo())
                .isActive(r.getIsActive()).build();
    }
}
