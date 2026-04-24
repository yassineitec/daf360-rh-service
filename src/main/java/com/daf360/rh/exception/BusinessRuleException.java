package com.daf360.rh.exception;

public class BusinessRuleException extends RuntimeException {

    private final String rule;

    public BusinessRuleException(String message) {
        super(message);
        this.rule = null;
    }

    public BusinessRuleException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    public String getRule() {
        return rule;
    }
}
