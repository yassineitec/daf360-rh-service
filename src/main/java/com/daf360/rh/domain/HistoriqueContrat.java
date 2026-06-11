package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "historique_contrat_collaborateur")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HistoriqueContrat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_historique_contrat")
    private Long id;

    @Column(name = "id_collaborateur", nullable = false)
    private Long idCollaborateur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_type_contrat", nullable = false)
    private TypeContrat typeContrat;

    /** CONTRAT_INITIAL or AVENANT */
    @Column(name = "type_document", nullable = false, length = 20)
    private String typeDocument;

    @Column(name = "date_effet", nullable = false)
    private LocalDate dateEffet;

    @Column(name = "date_fin")
    private LocalDate dateFin;

    @Column(name = "salaire_net", precision = 10, scale = 3)
    private BigDecimal salaireNet;

    @Column(name = "motif", length = 255, columnDefinition = "nvarchar(255)")
    private String motif;

    @Column(name = "commentaire", length = 1000, columnDefinition = "nvarchar(1000)")
    private String commentaire;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "date_creation", nullable = false, columnDefinition = "datetimeoffset")
    @Builder.Default
    private OffsetDateTime dateCreation = OffsetDateTime.now();
}
