package com.daf360.rh.service.pdf;

public class PdfGenerationException extends RuntimeException {

    public PdfGenerationException(String msg) {
        super(msg);
    }

    public PdfGenerationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
