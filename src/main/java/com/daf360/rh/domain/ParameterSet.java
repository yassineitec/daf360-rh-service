package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [parameter_sets] in DAF360_HR.
 * Schema verified 2026-05-31: updated_at is datetimeoffset (DEFAULT sysdatetimeoffset()).
 *
 * cle examples: TAUX_CNSS_EMPLOYE, TAUX_CSS, IRPP_BRACKETS, DEVISE
 * valeur: nvarchar(500) — may store JSON for complex values (e.g. IRPP bracket array)
 */
@Entity
@Table(name = "parameter_sets",
       uniqueConstraints = @UniqueConstraint(
               name = "UQ_param_pays_cle",
               columnNames = {"pays_id", "cle"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "cle", nullable = false, length = 100)
    private String cle;

    @Column(name = "valeur", nullable = false, length = 500, columnDefinition = "nvarchar(500)")
    private String valeur;

    @Column(name = "description", length = 500, columnDefinition = "nvarchar(500)")
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;
}
