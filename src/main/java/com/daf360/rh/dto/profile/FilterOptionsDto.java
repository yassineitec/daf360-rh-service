package com.daf360.rh.dto.profile;

import java.util.List;

public record FilterOptionsDto(
        List<String> departments,
        List<String> grades,
        List<String> pays
) {}
