package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "nationalities")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Nationality {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "label_fr", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelEn;

    @Column(name = "iso_code", length = 5)
    private String isoCode;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
