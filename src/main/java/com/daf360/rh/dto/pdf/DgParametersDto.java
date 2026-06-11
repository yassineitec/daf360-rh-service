package com.daf360.rh.dto.pdf;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DgParametersDto {
    private String dgName;
    private String dgCin;
    private String dgCinCity;
    private String dgCinDate;
    private String dgTitle;
}
