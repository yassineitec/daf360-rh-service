package com.daf360.rh.lists;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ListValueResponse {

    private Long           id;
    private Long           listTypeId;
    private Long           paysId;
    private String         valueCode;
    private String         labelFr;
    private String         labelEn;
    private Integer        sortOrder;
    private Boolean        isActive;
    private Boolean        isSystem;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
