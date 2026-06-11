package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "banks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "code", nullable = false, length = 50, columnDefinition = "nvarchar(50)")
    private String code;

    @Column(name = "label_fr", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelEn;

    @Column(name = "swift_code", length = 11)
    private String swiftCode;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
