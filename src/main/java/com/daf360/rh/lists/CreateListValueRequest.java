package com.daf360.rh.lists;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateListValueRequest {

    @NotNull
    private Long listTypeId;

    /** Nullable — value applies to all countries when absent */
    private Long paysId;

    @NotBlank
    @Size(max = 100)
    private String valueCode;

    @NotBlank
    @Size(max = 255)
    private String labelFr;

    @NotBlank
    @Size(max = 255)
    private String labelEn;

    private Integer sortOrder;
}
