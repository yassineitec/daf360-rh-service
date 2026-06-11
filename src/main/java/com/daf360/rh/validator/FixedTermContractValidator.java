package com.daf360.rh.validator;

import com.daf360.rh.dto.profile.EmployeeProfileCreateDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FixedTermContractValidator
        implements ConstraintValidator<ValidFixedTermContract, EmployeeProfileCreateDto> {

    private static final String FIXED_TERM = "CDD";

    @Override
    public boolean isValid(EmployeeProfileCreateDto dto, ConstraintValidatorContext ctx) {
        if (dto == null || dto.getContractType() == null) return true;
        if (FIXED_TERM.equalsIgnoreCase(dto.getContractType()) && dto.getContractEndDate() == null) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(ctx.getDefaultConstraintMessageTemplate())
               .addPropertyNode("contractEndDate")
               .addConstraintViolation();
            return false;
        }
        return true;
    }
}
