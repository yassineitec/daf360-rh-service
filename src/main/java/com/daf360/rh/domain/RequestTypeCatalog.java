package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.RequestCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Maps [request_type_catalog] in DAF360_HR.
 * Schema verified 2026-05-31: 12 cols.
 * NOTE: created_at / updated_at are datetime2 (not datetimeoffset) in this table.
 *
 * FK_ReqTypeCatalog_Pays → pays.id
 */
@Entity
@Table(name = "request_type_catalog",
       uniqueConstraints = @UniqueConstraint(
               name = "UQ_req_type_pays_code",
               columnNames = {"pays_id", "type_code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestTypeCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "type_code", nullable = false, length = 100)
    private String typeCode;

    @Column(name = "display_name_fr", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String displayNameFr;

    @Column(name = "display_name_en", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String displayNameEn;

    @Column(name = "description", length = 500, columnDefinition = "nvarchar(500)")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private RequestCategory category;

    /** L1 = one approval, L2 = two approvals (e.g. BANK_DETAILS). */
    @Column(name = "approval_level", nullable = false, length = 10)
    @Builder.Default
    private String approvalLevel = "L1";

    @Column(name = "default_sla_days", nullable = false)
    @Builder.Default
    private Integer defaultSlaDays = 2;

    @Column(name = "document_template_url", length = 500)
    private String documentTemplateUrl;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetime2")
    private LocalDateTime updatedAt;
}
