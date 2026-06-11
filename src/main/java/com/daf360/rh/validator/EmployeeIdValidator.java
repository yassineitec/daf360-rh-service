package com.daf360.rh.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class EmployeeIdValidator implements ConstraintValidator<ValidEmployeeId, String> {

    // <ENTITY 2-8 uppercase letters> - <2-digit year> - <4-digit sequence>
    private static final Pattern PATTERN = Pattern.compile("^[A-Z]{2,8}-\\d{2}-\\d{4}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) return true;   // let @NotBlank handle null
        return PATTERN.matcher(value).matches();
    }
}
