package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "document_template_versions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "html_content", nullable = false, columnDefinition = "nvarchar(max)")
    private String htmlContent;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false, columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime changedAt;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;
}
