package com.daf360.rh.dto.leave;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the request modal needs, in one call.
 *
 * Ported from the timesheet's {@code GET /absence/headers/{userId}/{lang}}, which returned
 * the same four things and for the same reason: the form cannot render until it knows the
 * balances (which gate the type dropdown), the options, and who may approve.
 */
public record LeaveHeadersDto(
        LeaveBalancesDto balances,
        List<LeaveTypeOptionDto> types,
        List<LeaveOptionDto> categories,
        List<LeaveApproverDto> approvers,
        List<LeaveBlockingRangeDto> blockingRanges,
        /**
         * ISO date -> holiday name, for the country the employee belongs to. Feeds the date
         * picker's greyed-out days and the form's cost preview. Without it the picker offers
         * public holidays as bookable and the preview over-counts them.
         */
        Map<String, String> holidays,
        /**
         * The employee's own rest days, as DayOfWeek names.
         *
         * Tunisia rests Saturday and Sunday, Egypt Friday and Saturday. The form used to assume
         * Sat/Sun, so an Egyptian employee saw a preview a day or two out from what they were
         * actually charged. The server has always been right; now the form can be too.
         */
        Set<String> weekendDays
) {}
