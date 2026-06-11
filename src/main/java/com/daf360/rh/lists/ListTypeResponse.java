package com.daf360.rh.lists;

import lombok.Data;

@Data
public class ListTypeResponse {

    private Long    id;
    private String  code;
    private String  labelFr;
    private String  labelEn;
    private String  description;
    private Boolean isPerPays;
    private Boolean isSystem;
}
