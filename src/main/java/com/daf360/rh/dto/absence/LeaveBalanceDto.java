package com.daf360.rh.dto.absence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalanceDto {

    private String leaveType;
    private double joursAcquis;
    private double joursPris;
    private double joursRestants;
}
