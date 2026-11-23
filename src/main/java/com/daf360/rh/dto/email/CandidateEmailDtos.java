package com.daf360.rh.dto.email;

import com.daf360.rh.domain.CandidateEmailTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** Read / write models of the candidate email templates (V134) and of the email preview. */
public final class CandidateEmailDtos {

    private CandidateEmailDtos() {}

    public record TemplateDto(Long id, String code, Long paysId, String subject, String body,
                              boolean active, OffsetDateTime updatedAt) {
        public static TemplateDto from(CandidateEmailTemplate t) {
            return new TemplateDto(t.getId(), t.getCode(), t.getPaysId(), t.getSubject(), t.getBody(),
                    Boolean.TRUE.equals(t.getIsActive()),
                    t.getUpdatedAt() != null ? t.getUpdatedAt() : t.getCreatedAt());
        }
    }

    public record SaveTemplateRequest(
            @NotBlank String code,
            @NotNull Long paysId,
            @NotBlank @Size(max = 500) String subject,
            @NotBlank String body,
            Boolean active) {}

    /** The built-in text of a code — what « Nouveau modèle » opens on, and what is sent without one. */
    public record DefaultTemplateDto(String code, String subject, String body, List<String> tokens) {}

    /** The email as it would leave now: the recipient, and the rendered subject and body. */
    public record EmailPreviewDto(String to, String subject, String body) {}

    /** RH's edited version of the email, for this one send. Absent ⇒ the rendered template. */
    public record SendOfferRequest(@Size(max = 500) String emailSubject, String emailBody) {}
}
