package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "document_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 500, columnDefinition = "nvarchar(500)")
    private String description;

    @Column(name = "html_content", nullable = false, columnDefinition = "nvarchar(max)")
    private String htmlContent;

    /** JSON array of {{key}} tokens detected at save time, e.g. ["employee.fullName","date.today"] */
    @Column(name = "variables", columnDefinition = "nvarchar(max)")
    private String variables;

    @Column(name = "page_size", nullable = false, length = 10)
    @Builder.Default
    private String pageSize = "A4";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
