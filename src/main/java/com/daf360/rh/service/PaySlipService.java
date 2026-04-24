package com.daf360.rh.service;

import com.daf360.rh.domain.PaySlip;
import com.daf360.rh.dto.PaySlipResponseDto;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.PaySlipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PaySlipService {

    private final PaySlipRepository paySlipRepository;

    @Transactional(readOnly = true)
    public List<PaySlipResponseDto> findByEmployee(Long employeeId) {
        return paySlipRepository
                .findByEmployeeIdOrderByYearPeriodDescMonthPeriodDesc(employeeId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public PaySlipResponseDto publish(Long id, String actorId) {
        PaySlip ps = paySlipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PaySlip", id));
        ps.setPublished(true);
        ps.setPublishedAt(LocalDateTime.now());
        return toDto(paySlipRepository.save(ps));
    }

    public List<PaySlipResponseDto> findUnpublished(int month, int year) {
        return paySlipRepository.findByPublishedFalseAndMonthPeriodAndYearPeriod(month, year)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public PaySlipResponseDto toDto(PaySlip ps) {
        PaySlipResponseDto dto = new PaySlipResponseDto();
        dto.setId(ps.getId());
        dto.setEmployeeId(ps.getEmployeeId());
        dto.setMonthPeriod(ps.getMonthPeriod());
        dto.setYearPeriod(ps.getYearPeriod());
        dto.setGrossSalary(ps.getGrossSalary());
        dto.setContributions(ps.getContributions());
        dto.setNetSalary(ps.getNetSalary());
        dto.setPublished(ps.getPublished());
        dto.setPublishedAt(ps.getPublishedAt());
        return dto;
    }
}
