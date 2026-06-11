package com.daf360.rh.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Cross-field constraint: contract_end_date is required when contract_type is FIXED_TERM (CDD).
 * Applied at the class level on create/update DTOs.
 */
@Documented
@Constraint(validatedBy = FixedTermContractValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFixedTermContract {
    String message() default "La date de fin de contrat est obligatoire pour un contrat à durée déterminée (CDD)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
