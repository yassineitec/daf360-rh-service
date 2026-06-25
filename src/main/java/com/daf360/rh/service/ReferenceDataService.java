package com.daf360.rh.service;

import com.daf360.rh.domain.*;
import com.daf360.rh.dto.ref.CreateRefDataRequest;
import com.daf360.rh.dto.ref.RefDataItemDto;
import com.daf360.rh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ReferenceDataService {

    private final GradeRepository        gradeRepo;
    private final DisciplineRepository   disciplineRepo;
    private final NogLevelRepository     nogLevelRepo;
    private final HrDepartmentRepository deptRepo;
    private final BankRepository         bankRepo;
    private final NationalityRepository  natRepo;
    private final ItAssetTypeRepository  assetTypeRepo;

    // ── Grades ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getGrades(Long paysId) {
        var list = (paysId != null)
                ? gradeRepo.findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(paysId)
                : gradeRepo.findByIsActiveTrueOrderBySortOrderAsc();
        return list.stream().map(this::toDto).collect(Collectors.toList());
    }

    public RefDataItemDto createGrade(CreateRefDataRequest req) {
        Grade g = Grade.builder()
                .paysId(req.getPaysId())
                .code(req.getCode() != null ? req.getCode() : req.getLabelFr())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        return toDto(gradeRepo.save(g));
    }

    public void deleteGrade(Long id) {
        gradeRepo.findById(id).ifPresent(g -> { g.setIsActive(false); gradeRepo.save(g); });
    }

    // ── Disciplines ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getDisciplines(Long paysId) {
        var list = (paysId != null)
                ? disciplineRepo.findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(paysId)
                : disciplineRepo.findByIsActiveTrueOrderBySortOrderAsc();
        return list.stream().map(this::toDto).collect(Collectors.toList());
    }

    public RefDataItemDto createDiscipline(CreateRefDataRequest req) {
        Discipline d = Discipline.builder()
                .paysId(req.getPaysId())
                .code(req.getCode() != null ? req.getCode() : req.getLabelFr())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        return toDto(disciplineRepo.save(d));
    }

    public void deleteDiscipline(Long id) {
        disciplineRepo.findById(id).ifPresent(d -> { d.setIsActive(false); disciplineRepo.save(d); });
    }

    // ── NogLevels ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getNogLevels(Long paysId) {
        var list = (paysId != null)
                ? nogLevelRepo.findByPaysIdAndIsActiveTrueOrderByLevelOrderAsc(paysId)
                : nogLevelRepo.findByIsActiveTrueOrderByLevelOrderAsc();
        return list.stream().map(this::toNogDto).collect(Collectors.toList());
    }

    public RefDataItemDto createNogLevel(CreateRefDataRequest req) {
        NogLevel nl = NogLevel.builder()
                .paysId(req.getPaysId())
                .code(req.getCode() != null ? req.getCode() : req.getLabelFr())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .levelOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        return toNogDto(nogLevelRepo.save(nl));
    }

    public void deleteNogLevel(Long id) {
        nogLevelRepo.findById(id).ifPresent(n -> { n.setIsActive(false); nogLevelRepo.save(n); });
    }

    // ── Departments ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getDepartments(Long paysId) {
        var list = (paysId != null)
                ? deptRepo.findByPaysIdAndIsActiveTrueOrderByLabelFrAsc(paysId)
                : deptRepo.findByIsActiveTrueOrderByLabelFrAsc();
        return list.stream().map(this::toDeptDto).collect(Collectors.toList());
    }

    public RefDataItemDto createDepartment(CreateRefDataRequest req) {
        HrDepartment d = HrDepartment.builder()
                .paysId(req.getPaysId())
                .code(req.getCode() != null ? req.getCode() : req.getLabelFr())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .parentId(req.getParentId())
                .build();
        return toDeptDto(deptRepo.save(d));
    }

    public void deleteDepartment(Long id) {
        deptRepo.findById(id).ifPresent(d -> { d.setIsActive(false); deptRepo.save(d); });
    }

    // ── Banks ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getBanks(Long paysId) {
        var list = (paysId != null)
                ? bankRepo.findByPaysIdAndIsActiveTrueOrderByLabelFrAsc(paysId)
                : bankRepo.findByIsActiveTrueOrderByLabelFrAsc();
        return list.stream().map(this::toBankDto).collect(Collectors.toList());
    }

    public RefDataItemDto createBank(CreateRefDataRequest req) {
        Bank b = Bank.builder()
                .paysId(req.getPaysId())
                .code(req.getCode() != null ? req.getCode() : req.getLabelFr())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .swiftCode(req.getSwiftCode())
                .build();
        return toBankDto(bankRepo.save(b));
    }

    public void deleteBank(Long id) {
        bankRepo.findById(id).ifPresent(b -> { b.setIsActive(false); bankRepo.save(b); });
    }

    // ── Nationalities (global — no paysId) ───────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getNationalities() {
        return natRepo.findByIsActiveTrueOrderByLabelFrAsc()
                .stream().map(n -> RefDataItemDto.builder()
                        .id(n.getId())
                        .code(n.getIsoCode())
                        .labelFr(n.getLabelFr())
                        .labelEn(n.getLabelEn())
                        .isActive(n.getIsActive())
                        .build())
                .collect(Collectors.toList());
    }

    public RefDataItemDto createNationality(CreateRefDataRequest req) {
        Nationality n = Nationality.builder()
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .isoCode(req.getCode())
                .build();
        Nationality saved = natRepo.save(n);
        return RefDataItemDto.builder()
                .id(saved.getId())
                .code(saved.getIsoCode())
                .labelFr(saved.getLabelFr())
                .labelEn(saved.getLabelEn())
                .isActive(saved.getIsActive())
                .build();
    }

    public void deleteNationality(Long id) {
        natRepo.findById(id).ifPresent(n -> { n.setIsActive(false); natRepo.save(n); });
    }

    // ── IT Asset Types (global — read-only via ref API, managed via V23 seed) ──

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getItAssetTypes() {
        return assetTypeRepo.findAllByIsActiveTrueOrderBySortOrderAsc()
                .stream().map(this::toAssetTypeDto).collect(Collectors.toList());
    }

    public RefDataItemDto createItAssetType(CreateRefDataRequest req) {
        com.daf360.rh.domain.ItAssetType a = com.daf360.rh.domain.ItAssetType.builder()
                .code(req.getCode() != null ? req.getCode().toUpperCase().replace(" ", "_")
                        : req.getLabelFr().toUpperCase().replace(" ", "_"))
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 99)
                .build();
        return toAssetTypeDto(assetTypeRepo.save(a));
    }

    public void deleteItAssetType(Long id) {
        assetTypeRepo.findById(id).ifPresent(a -> { a.setIsActive(false); assetTypeRepo.save(a); });
    }

    private RefDataItemDto toAssetTypeDto(com.daf360.rh.domain.ItAssetType a) {
        return RefDataItemDto.builder()
                .id(a.getId()).code(a.getCode())
                .labelFr(a.getLabelFr()).labelEn(a.getLabelEn())
                .sortOrder(a.getSortOrder()).isActive(a.getIsActive())
                .build();
    }

    // ── Private mappers ───────────────────────────────────────────────────────

    private RefDataItemDto toDto(Grade g) {
        return RefDataItemDto.builder()
                .id(g.getId()).paysId(g.getPaysId())
                .code(g.getCode()).labelFr(g.getLabelFr()).labelEn(g.getLabelEn())
                .sortOrder(g.getSortOrder()).isActive(g.getIsActive())
                .build();
    }

    private RefDataItemDto toDto(Discipline d) {
        return RefDataItemDto.builder()
                .id(d.getId()).paysId(d.getPaysId())
                .code(d.getCode()).labelFr(d.getLabelFr()).labelEn(d.getLabelEn())
                .sortOrder(d.getSortOrder()).isActive(d.getIsActive())
                .build();
    }

    private RefDataItemDto toNogDto(NogLevel n) {
        return RefDataItemDto.builder()
                .id(n.getId()).paysId(n.getPaysId())
                .code(n.getCode()).labelFr(n.getLabelFr()).labelEn(n.getLabelEn())
                .sortOrder(n.getLevelOrder()).isActive(n.getIsActive())
                .build();
    }

    private RefDataItemDto toDeptDto(HrDepartment d) {
        return RefDataItemDto.builder()
                .id(d.getId()).paysId(d.getPaysId())
                .code(d.getCode()).labelFr(d.getLabelFr()).labelEn(d.getLabelEn())
                .parentId(d.getParentId()).isActive(d.getIsActive())
                .build();
    }

    private RefDataItemDto toBankDto(Bank b) {
        return RefDataItemDto.builder()
                .id(b.getId()).paysId(b.getPaysId())
                .code(b.getCode()).labelFr(b.getLabelFr()).labelEn(b.getLabelEn())
                .swiftCode(b.getSwiftCode()).isActive(b.getIsActive())
                .build();
    }
}
