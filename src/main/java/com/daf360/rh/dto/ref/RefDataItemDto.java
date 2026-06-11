package com.daf360.rh.dto.ref;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RefDataItemDto {

    private Long id;
    /** Null for global dimension tables (e.g. nationalities). */
    private Long paysId;
    private String code;
    private String labelFr;
    private String labelEn;
    /** Maps to sort_order for most tables; maps to level_order for nog_levels. */
    private Integer sortOrder;
    private Boolean isActive;

    // Extra fields — populated only for specific dimension types
    /** Bank swift code — only populated for banks. */
    private String swiftCode;
    /** Parent department id — only populated for departments. */
    private Long parentId;
}
