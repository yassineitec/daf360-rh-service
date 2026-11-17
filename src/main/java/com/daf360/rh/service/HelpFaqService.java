package com.daf360.rh.service;

import com.daf360.rh.domain.HelpFaq;
import com.daf360.rh.dto.help.HelpFaqDto;
import com.daf360.rh.dto.help.SaveHelpFaqRequest;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.HelpFaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Admin › Assistant & FAQ. Writes need HR_ADMIN_ROLES (same as the document templates card);
 * the published questions are readable by every signed-in user, since the assistant and the
 * help page are open to everyone.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class HelpFaqService {

    private final HelpFaqRepository repo;

    /** Every question, published or not — the admin card. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'HR_ADMIN_ROLES')")
    public List<HelpFaqDto> listAll() {
        return repo.findAllByOrderByPageKeyAscOrderIndexAscIdAsc().stream().map(this::toDto).toList();
    }

    /** Published questions only — the assistant and the help page. */
    @Transactional(readOnly = true)
    public List<HelpFaqDto> listActive() {
        return repo.findByIsActiveTrueOrderByPageKeyAscOrderIndexAscIdAsc().stream().map(this::toDto).toList();
    }

    @PreAuthorize("hasPermission(null, 'HR_ADMIN_ROLES')")
    public HelpFaqDto create(SaveHelpFaqRequest req, Long actorId) {
        HelpFaq faq = HelpFaq.builder()
                .createdAt(OffsetDateTime.now())
                .createdBy(actorId)
                .build();
        apply(faq, req);
        return toDto(repo.save(faq));
    }

    @PreAuthorize("hasPermission(null, 'HR_ADMIN_ROLES')")
    public HelpFaqDto update(Long id, SaveHelpFaqRequest req, Long actorId) {
        HelpFaq faq = find(id);
        apply(faq, req);
        faq.setUpdatedAt(OffsetDateTime.now());
        faq.setUpdatedBy(actorId);
        return toDto(repo.save(faq));
    }

    @PreAuthorize("hasPermission(null, 'HR_ADMIN_ROLES')")
    public HelpFaqDto setActive(Long id, boolean active, Long actorId) {
        HelpFaq faq = find(id);
        faq.setIsActive(active);
        faq.setUpdatedAt(OffsetDateTime.now());
        faq.setUpdatedBy(actorId);
        return toDto(repo.save(faq));
    }

    @PreAuthorize("hasPermission(null, 'HR_ADMIN_ROLES')")
    public void delete(Long id) {
        repo.delete(find(id));
    }

    private HelpFaq find(Long id) {
        return repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("HelpFaq", id));
    }

    private void apply(HelpFaq faq, SaveHelpFaqRequest req) {
        faq.setPageKey(req.pageKey().trim());
        faq.setQuestionFr(req.questionFr().trim());
        faq.setAnswerFr(req.answerFr().trim());
        faq.setQuestionEn(blankToNull(req.questionEn()));
        faq.setAnswerEn(blankToNull(req.answerEn()));
        faq.setQuestionAr(blankToNull(req.questionAr()));
        faq.setAnswerAr(blankToNull(req.answerAr()));
        if (req.orderIndex() != null) faq.setOrderIndex(req.orderIndex());
        if (req.isActive() != null) faq.setIsActive(req.isActive());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private HelpFaqDto toDto(HelpFaq f) {
        return new HelpFaqDto(f.getId(), f.getPageKey(),
                f.getQuestionFr(), f.getAnswerFr(),
                f.getQuestionEn(), f.getAnswerEn(),
                f.getQuestionAr(), f.getAnswerAr(),
                f.getOrderIndex(), f.getIsActive(),
                f.getCreatedAt(), f.getUpdatedAt());
    }
}
