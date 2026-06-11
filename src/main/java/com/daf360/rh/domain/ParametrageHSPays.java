package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "parametrage_heures_supp_pays")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ParametrageHSPays {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_parametrage")
    private Long idParametrage;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** WEEKEND_ONLY | AFTER_WORK_HOURS | MIXTE */
    @Column(name = "type_calcul_hs", nullable = false, length = 20)
    private String typeCalculHs;

    @Column(name = "heure_debut_travail")
    private LocalTime heureDebutTravail;

    @Column(name = "heure_fin_travail")
    private LocalTime heureFinTravail;

    /** First working day of the week — ex: MONDAY */
    @Column(name = "jour_debut_semaine", length = 10)
    private String jourDebutSemaine;

    /** Last working day of the week — ex: FRIDAY */
    @Column(name = "jour_fin_semaine", length = 10)
    private String jourFinSemaine;

    @Column(name = "actif", nullable = false)
    @Builder.Default
    private Boolean actif = true;

    @Column(name = "date_creation", nullable = false, columnDefinition = "datetimeoffset")
    @Builder.Default
    private OffsetDateTime dateCreation = OffsetDateTime.now();

    @Column(name = "date_modification", columnDefinition = "datetimeoffset")
    private OffsetDateTime dateModification;

    @Column(name = "created_by")
    private Long createdBy;
}
