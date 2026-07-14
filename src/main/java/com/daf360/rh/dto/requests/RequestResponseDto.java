package com.daf360.rh.dto.requests;

import com.daf360.rh.domain.enums.RequestStatus;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class RequestResponseDto {
    private Long                    id;
    private Long                    employeeProfileId;
    private Long                    requestTypeId;
    private String                  typeCode;
    private String                  typeDisplayNameFr;
    private String                  employeeName;
    private Long                    paysId;
    private OffsetDateTime          submissionDate;
    private String                  submissionChannel;
    private RequestStatus           status;
    private Long                    assignedOfficerId;
    private String                  assignedOfficerName;
    private OffsetDateTime          resolutionDate;
    private String                  closureComment;
    private String                  attachmentUrl;
    private OffsetDateTime          createdAt;
    private List<ApprovalSummaryDto> approvals;
}
