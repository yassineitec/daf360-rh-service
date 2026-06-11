package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "it_assets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provisioning_id", nullable = false)
    private ItProvisioning provisioning;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_type_id", nullable = false)
    private ItAssetType assetType;

    @Column(name = "provided", nullable = false)
    @Builder.Default
    private Boolean provided = false;

    @Column(name = "serial_number", length = 100, columnDefinition = "nvarchar(100)")
    private String serialNumber;

    @Column(name = "brand_model", length = 150, columnDefinition = "nvarchar(150)")
    private String brandModel;

    @Column(name = "asset_tag", length = 100, columnDefinition = "nvarchar(100)")
    private String assetTag;

    @Column(name = "status", nullable = false, length = 50, columnDefinition = "nvarchar(50)")
    @Builder.Default
    private String status = "BON_ETAT";
}
