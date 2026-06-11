package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "type_contrat")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TypeContrat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelEn;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetimeoffset")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
