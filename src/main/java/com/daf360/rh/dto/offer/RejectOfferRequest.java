package com.daf360.rh.dto.offer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Payload when a candidate declines (or RH withdraws) an offer. */
@Data
public class RejectOfferRequest {

    @NotBlank @Size(max = 500)
    private String rejectionReason;
}
