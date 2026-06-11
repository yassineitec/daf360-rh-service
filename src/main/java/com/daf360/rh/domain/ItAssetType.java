package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "it_asset_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItAssetType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelEn;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
