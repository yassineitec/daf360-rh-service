package com.daf360.rh.dto.ref;

import lombok.Data;

@Data
public class CreateRefDataRequest {

    private Long paysId;
    private String code;
    private String labelFr;
    private String labelEn;
    private Integer sortOrder;
    /** Banks only. */
    private String swiftCode;
    /** Departments only — references parent department. */
    private Long parentId;
}
