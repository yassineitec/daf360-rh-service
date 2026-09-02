package com.daf360.rh.service;

import com.daf360.rh.domain.Mission;
import com.daf360.rh.domain.MissionChangeRequest;
import com.daf360.rh.domain.MissionExpense;
import com.daf360.rh.domain.MissionStatusHistory;
import com.daf360.rh.domain.enums.MissionChangeRequestStatus;
import com.daf360.rh.domain.enums.MissionChangeRequestType;
import com.daf360.rh.domain.enums.MissionScope;
import com.daf360.rh.domain.enums.MissionStatus;
import com.daf360.rh.dto.mission.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.MissionChangeRequestRepository;
import com.daf360.rh.repository.MissionExpenseRepository;
import com.daf360.rh.repository.MissionRepository;
import com.daf360.rh.repository.MissionStatusHistoryRepository;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The whole mission process: a manager plans, RH prices, finance decides, the employee
 * reads and may ask for a change.
 *
 * Two rules are enforced here and NOT by {@code @PreAuthorize}, because both need the row
 * (or the org chart) loaded and an annotation cannot do that:
 *
 *  - **who may plan for whom** — {@link #assertIsSubordinate}: the employee must be a
 *    descendant of the caller's role, in the caller's pays. Holding RH_CREATE_MISSION says
 *    you may plan missions, not that you may plan them for anyone in the company.
 *  - **whose mission this is** — {@link #assertIsOwnMission}: the self-service endpoints
 *    take no id from the client other than the mission's, so without this check any
 *    authenticated user could read or cancel someone else's trip.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class MissionService {

    private final MissionRepository              missionRepo;
    private final MissionExpenseRepository       expenseRepo;
    private final MissionChangeRequestRepository changeRepo;
    private final MissionStatusHistoryRepository historyRepo;
    private final TenantService                  tenantService;
    private final JdbcTemplate                   jdbcTemplate;

    private static final Set<String> TRANSPORT_MODES =
            Set.of("AVION", "TRAIN", "BUS", "VOITURE", "BATEAU", "AUTRE");
    private static final Set<String> PAYMENT_METHODS =
            Set.of("ESPECES", "VIREMENT", "CARTE", "AUTRE");

    // ── Manager side ──────────────────────────────────────────────────────────

    /**
     * The people the caller may plan a mission for: the holders of every role BELOW theirs
     * in the org chart, restricted to their own pays.
     *
     * Same query as {@code UserSyncController.subordinatesForUser}, but anchored on the
     * caller from the JWT instead of a {@code userId} path variable — that endpoint is an
     * unauthenticated service-to-service one, and exposing it to a browser would let anyone
     * enumerate any manager's team.
     */
    @Transactional(readOnly = true)
    public List<MissionEligibleEmployeeDto> eligibleEmployees(Long callerId) {
        String sql = """
                WITH descendants AS (
                    SELECT r.id
                    FROM Roles r
                    WHERE r.parent_role_id = (SELECT cu.role_id FROM Users cu WHERE cu.id = ?)
                      AND (r.deleted = 0 OR r.deleted IS NULL)
                    UNION ALL
                    SELECT c.id
                    FROM Roles c
                    JOIN descendants d ON c.parent_role_id = d.id
                    WHERE (c.deleted = 0 OR c.deleted IS NULL)
                )
                SELECT u.id, u.fullName, COALESCE(u.email, u.username) AS email,
                       r.frenchName AS role_name, u.pays_id
                FROM Users u
                LEFT JOIN Roles r ON r.id = u.role_id
                WHERE u.isActive = 1
                  AND u.is_employee = 1
                  AND u.role_id IN (SELECT id FROM descendants)
                  AND u.id <> ?
                  AND (
                      u.pays_id = (SELECT cu2.pays_id FROM Users cu2 WHERE cu2.id = ?)
                      OR (SELECT cu3.pays_id FROM Users cu3 WHERE cu3.id = ?) IS NULL
                  )
                ORDER BY u.fullName
                """;
        return jdbcTemplate.query(sql, (rs, rn) -> new MissionEligibleEmployeeDto(
                rs.getLong("id"),
                rs.getString("fullName"),
                rs.getString("email"),
                rs.getString("role_name"),
                rs.getObject("pays_id", Long.class)
        ), callerId, callerId, callerId, callerId);
    }

    public MissionDto create(CreateMissionRequest req, Long callerId) {
        assertIsSubordinate(callerId, req.getEmployeeUserId());
        assertPeriod(req.getStartDate(), req.getEndDate());
        assertResponsable(req.getResponsableUserId(), req.getResponsableName());
        assertDestination(req.getScope(), req.getDestinationPaysId(), req.getCountryLabel());
        assertNoOverlap(req.getEmployeeUserId(), req.getStartDate(), req.getEndDate(), null);

        Mission mission = Mission.builder()
                .paysId(paysIdOf(req.getEmployeeUserId()))
                .employeeUserId(req.getEmployeeUserId())
                .createdBy(callerId)
                .responsableUserId(req.getResponsableUserId())
                .responsableName(trimToNull(req.getResponsableName()))
                .title(req.getTitle().trim())
                .details(trimToNull(req.getDetails()))
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .scope(req.getScope())
                .destinationPaysId(req.getDestinationPaysId())
                .countryLabel(trimToNull(req.getCountryLabel()))
                .city(req.getCity().trim())
                .address(trimToNull(req.getAddress()))
                .status(MissionStatus.PENDING_HR)
                .build();

        Mission saved = missionRepo.save(mission);
        trace(saved, null, MissionStatus.PENDING_HR, callerId, "Mission planifiée");
        return toDto(saved, false);
    }

    /** Everything the caller planned, whatever its status. */
    @Transactional(readOnly = true)
    public List<MissionDto> listForManager(Long callerId) {
        return toDtos(missionRepo.findByCreatedByOrderByStartDateDesc(callerId));
    }

    /**
     * The manager calls a mission off. Only while it is still moving: once finance has
     * decided, the money has been committed and the cancellation goes through RH — the
     * employee's cancellation request, or a direct RH decision.
     */
    public MissionDto cancelByManager(Long missionId, String reason, Long callerId) {
        Mission mission = load(missionId);
        if (!Objects.equals(mission.getCreatedBy(), callerId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Seul le manager qui a planifié cette mission peut l'annuler");
        }
        if (mission.getStatus() != MissionStatus.PENDING_HR) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Cette mission est déjà en cours de traitement — passez par RH");
        }
        return cancel(mission, reason, callerId);
    }

    // ── RH side (billeterie) ──────────────────────────────────────────────────

    /** The billeterie queue: everything a manager submitted and RH has not answered yet. */
    @Transactional(readOnly = true)
    public List<MissionDto> listPendingHr() {
        return toDtos(pendingFor(MissionStatus.PENDING_HR));
    }

    /**
     * Saves the expense sheet. Creates the row on first call, so the frontend never has to
     * distinguish "prepare" from "update".
     *
     * Allowed on PENDING_HR only: once the sheet is validated it is the figure finance is
     * deciding on, and letting RH move it underneath them would make the approval
     * meaningless.
     */
    public MissionDto saveExpenses(Long missionId, MissionExpenseDto dto, Long callerId) {
        Mission mission = load(missionId);
        if (mission.getStatus() != MissionStatus.PENDING_HR) {
            throw new AppException(ErrorCode.MISSION_EXPENSES_LOCKED,
                    "Les frais ne sont plus modifiables (statut = " + mission.getStatus() + ")");
        }
        assertCode(dto.getTransportMode(), TRANSPORT_MODES, "Mode de transport");
        assertCode(dto.getPaymentMethod(), PAYMENT_METHODS, "Mode de paiement");

        MissionExpense expense = expenseRepo.findByMissionId(missionId)
                .orElseGet(() -> MissionExpense.builder().missionId(missionId).build());

        if (StringUtils.hasText(dto.getCurrency())) {
            expense.setCurrency(dto.getCurrency().trim().toUpperCase());
        }
        expense.setAllowanceDailyRate(dto.getAllowanceDailyRate());
        expense.setMissionAllowance(dto.getMissionAllowance());
        expense.setLodgingCost(dto.getLodgingCost());
        expense.setHotelName(trimToNull(dto.getHotelName()));
        expense.setNights(dto.getNights());
        expense.setReservationNumber(trimToNull(dto.getReservationNumber()));
        expense.setTransportMode(trimToNull(dto.getTransportMode()));
        expense.setTransportCarrier(trimToNull(dto.getTransportCarrier()));
        expense.setTicketReference(trimToNull(dto.getTicketReference()));
        expense.setTicketCost(dto.getTicketCost());
        expense.setOutboundAt(dto.getOutboundAt());
        expense.setReturnAt(dto.getReturnAt());
        expense.setVisaFees(dto.getVisaFees());
        expense.setInsuranceFees(dto.getInsuranceFees());
        expense.setOtherFees(dto.getOtherFees());
        expense.setOtherFeesLabel(trimToNull(dto.getOtherFeesLabel()));
        expense.setAdvanceAmount(dto.getAdvanceAmount());
        expense.setPaymentMethod(trimToNull(dto.getPaymentMethod()));
        expense.setCashPickupDate(dto.getCashPickupDate());
        expense.setDocumentPickupDate(dto.getDocumentPickupDate());
        expense.setHrNotes(trimToNull(dto.getHrNotes()));
        expense.setPreparedBy(callerId);
        if (expense.getPreparedAt() == null) expense.setPreparedAt(OffsetDateTime.now());
        // Never the client's figure — see MissionExpenseDto.
        expense.recomputeTotal();

        expenseRepo.save(expense);
        return toDto(mission, true);
    }

    /**
     * RH may adjust the plan while pricing it — the dates and the destination are what the
     * travel agency actually managed to book, and bouncing the whole file back to the
     * manager for a one-day shift would stall it. Every adjustment is traced.
     */
    public MissionDto adjustByHr(Long missionId, CreateMissionRequest req, Long callerId) {
        Mission mission = load(missionId);
        if (mission.getStatus() != MissionStatus.PENDING_HR) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Cette mission n'est plus en attente RH (statut = " + mission.getStatus() + ")");
        }
        assertPeriod(req.getStartDate(), req.getEndDate());
        assertResponsable(req.getResponsableUserId(), req.getResponsableName());
        assertDestination(req.getScope(), req.getDestinationPaysId(), req.getCountryLabel());
        assertNoOverlap(mission.getEmployeeUserId(), req.getStartDate(), req.getEndDate(), missionId);

        String before = periodLabel(mission);
        mission.setResponsableUserId(req.getResponsableUserId());
        mission.setResponsableName(trimToNull(req.getResponsableName()));
        mission.setTitle(req.getTitle().trim());
        mission.setDetails(trimToNull(req.getDetails()));
        mission.setStartDate(req.getStartDate());
        mission.setEndDate(req.getEndDate());
        mission.setScope(req.getScope());
        mission.setDestinationPaysId(req.getDestinationPaysId());
        mission.setCountryLabel(trimToNull(req.getCountryLabel()));
        mission.setCity(req.getCity().trim());
        mission.setAddress(trimToNull(req.getAddress()));
        Mission saved = missionRepo.save(mission);

        trace(saved, MissionStatus.PENDING_HR, MissionStatus.PENDING_HR, callerId,
              "Ajustement RH — période " + before + " → " + periodLabel(saved));
        return toDto(saved, true);
    }

    /** RH validates and hands the file to finance. Refused until the sheet exists. */
    public MissionDto validateByHr(Long missionId, String notes, Long callerId) {
        Mission mission = load(missionId);
        if (mission.getStatus() != MissionStatus.PENDING_HR) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Cette mission n'est plus en attente RH (statut = " + mission.getStatus() + ")");
        }
        // Sending an unpriced mission to finance would ask them to approve a cost of zero.
        MissionExpense expense = expenseRepo.findByMissionId(missionId)
                .orElseThrow(() -> new AppException(ErrorCode.MISSION_EXPENSES_MISSING,
                        "Renseignez les frais de la mission avant de la valider"));

        mission.setStatus(MissionStatus.PENDING_FINANCE);
        mission.setHrValidatedBy(callerId);
        mission.setHrValidatedAt(OffsetDateTime.now());
        mission.setHrNotes(trimToNull(notes));
        Mission saved = missionRepo.save(mission);

        trace(saved, MissionStatus.PENDING_HR, MissionStatus.PENDING_FINANCE, callerId,
              "Validation RH — coût estimé " + expense.getTotalEstimatedCost() + " " + expense.getCurrency());
        return toDto(saved, true);
    }

    /** RH refuses the mission outright. Terminal — the manager re-plans if needed. */
    public MissionDto rejectByHr(Long missionId, String notes, Long callerId) {
        Mission mission = load(missionId);
        if (mission.getStatus() != MissionStatus.PENDING_HR) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Cette mission n'est plus en attente RH (statut = " + mission.getStatus() + ")");
        }
        assertReason(notes, "Motif de refus obligatoire");

        mission.setStatus(MissionStatus.REJECTED_HR);
        mission.setHrValidatedBy(callerId);
        mission.setHrValidatedAt(OffsetDateTime.now());
        mission.setHrNotes(notes.trim());
        Mission saved = missionRepo.save(mission);

        trace(saved, MissionStatus.PENDING_HR, MissionStatus.REJECTED_HR, callerId, notes.trim());
        return toDto(saved, true);
    }

    // ── Finance side ──────────────────────────────────────────────────────────

    /** The finance queue: RH-validated missions awaiting the final decision. */
    @Transactional(readOnly = true)
    public List<MissionDto> listPendingFinance() {
        return toDtos(pendingFor(MissionStatus.PENDING_FINANCE));
    }

    public MissionDto approveByFinance(Long missionId, String notes, Long callerId) {
        Mission mission = requirePendingFinance(missionId);
        mission.setStatus(MissionStatus.APPROVED);
        stampFinance(mission, callerId, notes);
        Mission saved = missionRepo.save(mission);
        trace(saved, MissionStatus.PENDING_FINANCE, MissionStatus.APPROVED, callerId, trimToNull(notes));
        return toDto(saved, true);
    }

    public MissionDto rejectByFinance(Long missionId, String notes, Long callerId) {
        Mission mission = requirePendingFinance(missionId);
        assertReason(notes, "Motif de refus obligatoire");
        mission.setStatus(MissionStatus.REJECTED_FINANCE);
        stampFinance(mission, callerId, notes);
        Mission saved = missionRepo.save(mission);
        trace(saved, MissionStatus.PENDING_FINANCE, MissionStatus.REJECTED_FINANCE, callerId, notes.trim());
        return toDto(saved, true);
    }

    // ── Employee side (self-service + calendar) ───────────────────────────────

    /** The employee's own missions, every status, most recent first. */
    @Transactional(readOnly = true)
    public List<MissionDto> listMine(Long callerId) {
        return toDtos(missionRepo.findByEmployeeUserIdOrderByStartDateDesc(callerId));
    }

    /**
     * The shell's home calendar feed — APPROVED only. A mission that finance has not
     * cleared is not something the employee should be planning their week around.
     */
    @Transactional(readOnly = true)
    public List<MissionCalendarEventDto> myCalendar(Long callerId, LocalDate from, LocalDate to) {
        return missionRepo.findApprovedOverlapping(callerId, from, to).stream().map(m -> {
            MissionCalendarEventDto dto = new MissionCalendarEventDto();
            dto.setId(m.getId());
            dto.setTitle(m.getTitle());
            dto.setStartDate(m.getStartDate());
            dto.setEndDate(m.getEndDate());
            dto.setScope(m.getScope());
            dto.setCity(m.getCity());
            dto.setCountryLabel(m.getCountryLabel());
            return dto;
        }).toList();
    }

    @Transactional(readOnly = true)
    public MissionDto getForEmployee(Long missionId, Long callerId) {
        Mission mission = load(missionId);
        assertIsOwnMission(mission, callerId);
        return toDto(mission, true);
    }

    /**
     * The employee asks for something on their own mission. Only on a mission that is
     * actually running towards them (validated, or awaiting finance): asking to move the
     * dates of a rejected mission has no meaning, and asking on a PENDING_HR one is a
     * conversation with their manager, not a request.
     *
     * One open ask at a time — a second one would give RH two contradictory instructions
     * on the same file.
     */
    public MissionChangeRequestDto requestChange(Long missionId,
                                                 MissionChangeRequestType type,
                                                 MissionChangeRequestCreate req,
                                                 Long callerId) {
        Mission mission = load(missionId);
        assertIsOwnMission(mission, callerId);
        if (mission.getStatus() != MissionStatus.APPROVED
                && mission.getStatus() != MissionStatus.PENDING_FINANCE) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Aucune demande possible sur une mission au statut " + mission.getStatus());
        }
        if (changeRepo.existsByMissionIdAndStatus(missionId, MissionChangeRequestStatus.PENDING)) {
            throw new AppException(ErrorCode.MISSION_CHANGE_REQUEST_PENDING,
                    "Une demande est déjà en attente sur cette mission");
        }
        if (type == MissionChangeRequestType.PERIOD_CHANGE) {
            if (req.getRequestedStartDate() == null || req.getRequestedEndDate() == null) {
                throw new AppException(ErrorCode.MISSION_INVALID_PERIOD,
                        "Indiquez la nouvelle période souhaitée");
            }
            assertPeriod(req.getRequestedStartDate(), req.getRequestedEndDate());
        }

        MissionChangeRequest saved = changeRepo.save(MissionChangeRequest.builder()
                .missionId(missionId)
                .requestedBy(callerId)
                .requestType(type)
                // Dates on a cancellation would be dead data the RH screen might display.
                .requestedStartDate(type == MissionChangeRequestType.PERIOD_CHANGE ? req.getRequestedStartDate() : null)
                .requestedEndDate(type == MissionChangeRequestType.PERIOD_CHANGE ? req.getRequestedEndDate() : null)
                .reason(req.getReason().trim())
                .status(MissionChangeRequestStatus.PENDING)
                .build());

        trace(mission, mission.getStatus(), mission.getStatus(), callerId,
              (type == MissionChangeRequestType.CANCELLATION
                      ? "Demande d'annulation : " : "Demande de modification de période : ")
              + saved.getReason());
        return toChangeDto(saved, mission);
    }

    // ── RH: resolving the employees' asks ─────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MissionChangeRequestDto> listPendingChangeRequests() {
        List<MissionChangeRequest> requests =
                changeRepo.findByStatusOrderByCreatedAtAsc(MissionChangeRequestStatus.PENDING);
        Map<Long, Mission> missions = missionRepo.findAllById(
                requests.stream().map(MissionChangeRequest::getMissionId).distinct().toList())
                .stream().collect(Collectors.toMap(Mission::getId, m -> m));
        return requests.stream().map(r -> toChangeDto(r, missions.get(r.getMissionId()))).toList();
    }

    /**
     * RH's answer. Accepting is what actually moves the mission: a PERIOD_CHANGE writes the
     * new dates, a CANCELLATION cancels it. Refusing only closes the request.
     */
    public MissionChangeRequestDto resolveChangeRequest(Long requestId,
                                                        ResolveChangeRequestDto dto,
                                                        Long callerId) {
        MissionChangeRequest request = changeRepo.findById(requestId)
                .orElseThrow(() -> new AppException(ErrorCode.MISSION_CHANGE_REQUEST_NOT_FOUND,
                        "Demande introuvable : id=" + requestId));
        if (request.getStatus() != MissionChangeRequestStatus.PENDING) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Cette demande a déjà été traitée");
        }
        Mission mission = load(request.getMissionId());

        if (Boolean.TRUE.equals(dto.getAccept())) {
            if (request.getRequestType() == MissionChangeRequestType.CANCELLATION) {
                cancel(mission, request.getReason(), callerId);
            } else {
                assertNoOverlap(mission.getEmployeeUserId(), request.getRequestedStartDate(),
                                request.getRequestedEndDate(), mission.getId());
                String before = periodLabel(mission);
                mission.setStartDate(request.getRequestedStartDate());
                mission.setEndDate(request.getRequestedEndDate());
                Mission saved = missionRepo.save(mission);
                trace(saved, saved.getStatus(), saved.getStatus(), callerId,
                      "Période modifiée à la demande de l'employé : " + before + " → " + periodLabel(saved));
            }
        } else {
            assertReason(dto.getNotes(), "Motif de refus obligatoire");
            trace(mission, mission.getStatus(), mission.getStatus(), callerId,
                  "Demande refusée : " + dto.getNotes().trim());
        }

        request.setStatus(Boolean.TRUE.equals(dto.getAccept())
                ? MissionChangeRequestStatus.ACCEPTED : MissionChangeRequestStatus.REJECTED);
        request.setResolvedBy(callerId);
        request.setResolvedAt(OffsetDateTime.now());
        request.setResolutionNotes(trimToNull(dto.getNotes()));
        return toChangeDto(changeRepo.save(request), mission);
    }

    // ── Shared read path ──────────────────────────────────────────────────────

    /**
     * Detail view. Not gated on ownership: the three permissions that reach this endpoint
     * (manager, RH, finance) are all legitimate readers of any mission they can see in
     * their queue. The employee's own path goes through {@link #getForEmployee}.
     */
    @Transactional(readOnly = true)
    public MissionDto getById(Long missionId) {
        return toDto(load(missionId), true);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private List<Mission> pendingFor(MissionStatus status) {
        Long paysId = tenantService.getEffectivePaysId();
        // Null for a global admin — see TenantService: they see every country's queue.
        return paysId == null
                ? missionRepo.findByStatusOrderByStartDateAsc(status)
                : missionRepo.findByStatusAndPaysIdOrderByStartDateAsc(status, paysId);
    }

    private Mission requirePendingFinance(Long missionId) {
        Mission mission = load(missionId);
        if (mission.getStatus() != MissionStatus.PENDING_FINANCE) {
            throw new AppException(ErrorCode.MISSION_INVALID_TRANSITION,
                    "Cette mission n'attend pas de décision finance (statut = " + mission.getStatus() + ")");
        }
        return mission;
    }

    private void stampFinance(Mission mission, Long callerId, String notes) {
        mission.setFinanceDecidedBy(callerId);
        mission.setFinanceDecidedAt(OffsetDateTime.now());
        mission.setFinanceNotes(trimToNull(notes));
    }

    private MissionDto cancel(Mission mission, String reason, Long callerId) {
        MissionStatus from = mission.getStatus();
        mission.setStatus(MissionStatus.CANCELLED);
        mission.setCancelledBy(callerId);
        mission.setCancelledAt(OffsetDateTime.now());
        mission.setCancellationReason(trimToNull(reason));
        Mission saved = missionRepo.save(mission);
        trace(saved, from, MissionStatus.CANCELLED, callerId, trimToNull(reason));
        return toDto(saved, true);
    }

    private Mission load(Long id) {
        return missionRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.MISSION_NOT_FOUND,
                        "Mission introuvable : id=" + id));
    }

    private void trace(Mission mission, MissionStatus from, MissionStatus to,
                       Long actorId, String notes) {
        historyRepo.save(MissionStatusHistory.builder()
                .missionId(mission.getId())
                .fromStatus(from == null ? null : from.name())
                .toStatus(to.name())
                .actorUserId(actorId)
                .notes(notes)
                .build());
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    /**
     * The employee must be a role-descendant of the caller, in the caller's pays. Reuses
     * {@link #eligibleEmployees} rather than a second query, so the picker the manager sees
     * and the set the server accepts cannot drift apart.
     */
    private void assertIsSubordinate(Long callerId, Long employeeUserId) {
        boolean allowed = eligibleEmployees(callerId).stream()
                .anyMatch(e -> Objects.equals(e.id(), employeeUserId));
        if (!allowed) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette personne ne fait pas partie de votre équipe");
        }
    }

    private void assertIsOwnMission(Mission mission, Long callerId) {
        if (!Objects.equals(mission.getEmployeeUserId(), callerId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette mission n'est pas la vôtre");
        }
    }

    private void assertPeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new AppException(ErrorCode.MISSION_INVALID_PERIOD,
                    "La date de fin doit être postérieure ou égale à la date de début");
        }
    }

    private void assertResponsable(Long responsableUserId, String responsableName) {
        if (responsableUserId == null && !StringUtils.hasText(responsableName)) {
            throw new AppException(ErrorCode.MISSION_RESPONSABLE_REQUIRED,
                    "Indiquez un responsable de mission (collaborateur ou contact externe)");
        }
    }

    /**
     * An international mission has to say WHERE. The city alone is not enough: "Le Caire"
     * and "Cairo, Illinois" are not the same visa.
     */
    private void assertDestination(MissionScope scope, Long destinationPaysId, String countryLabel) {
        if (scope == MissionScope.INTERNATIONAL
                && destinationPaysId == null && !StringUtils.hasText(countryLabel)) {
            throw new AppException(ErrorCode.MISSION_DESTINATION_REQUIRED,
                    "Précisez le pays de destination pour une mission internationale");
        }
    }

    /** Nobody can be in two places at once. */
    private void assertNoOverlap(Long employeeUserId, LocalDate start, LocalDate end, Long excludeId) {
        List<Mission> clashes = missionRepo.findOverlapping(
                employeeUserId, start, end,
                excludeId == null ? MissionRepository.NO_EXCLUSION : excludeId);
        if (!clashes.isEmpty()) {
            Mission first = clashes.get(0);
            throw new AppException(ErrorCode.MISSION_OVERLAP,
                    "Cette personne est déjà en mission du " + first.getStartDate()
                            + " au " + first.getEndDate() + " (« " + first.getTitle() + " »)");
        }
    }

    private void assertReason(String notes, String message) {
        if (!StringUtils.hasText(notes)) {
            throw new AppException(ErrorCode.MISSION_REASON_REQUIRED, message);
        }
    }

    private void assertCode(String value, Set<String> allowed, String label) {
        if (StringUtils.hasText(value) && !allowed.contains(value.trim().toUpperCase())) {
            throw new AppException(ErrorCode.MISSION_INVALID_CODE,
                    label + " inconnu : " + value);
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    /**
     * Batch mapper for the list screens. One query for the names and one for the expense
     * sheets, instead of two per row — the queues are the hot path here.
     */
    private List<MissionDto> toDtos(List<Mission> missions) {
        if (missions.isEmpty()) return List.of();

        List<Long> ids = missions.stream().map(Mission::getId).toList();
        Map<Long, MissionExpense> expenses = expenseRepo.findByMissionIdIn(ids).stream()
                .collect(Collectors.toMap(MissionExpense::getMissionId, e -> e));
        Map<Long, MissionChangeRequest> pending = changeRepo.findByMissionIdInOrderByCreatedAtDesc(ids).stream()
                .filter(r -> r.getStatus() == MissionChangeRequestStatus.PENDING)
                .collect(Collectors.toMap(MissionChangeRequest::getMissionId, r -> r, (a, b) -> a));
        Set<Long> userIds = new LinkedHashSet<>();
        for (Mission m : missions) {
            addIfPresent(userIds, m.getEmployeeUserId(), m.getCreatedBy(), m.getResponsableUserId(),
                         m.getHrValidatedBy(), m.getFinanceDecidedBy());
        }
        Map<Long, UserRow> users = userRows(userIds);

        return missions.stream()
                .map(m -> map(m, expenses.get(m.getId()), pending.get(m.getId()), users, null))
                .toList();
    }

    /** Single-mission mapper. {@code withHistory} is off on the create path — there is one entry. */
    private MissionDto toDto(Mission mission, boolean withHistory) {
        MissionExpense expense = expenseRepo.findByMissionId(mission.getId()).orElse(null);
        MissionChangeRequest pending = changeRepo.findByMissionIdOrderByCreatedAtDesc(mission.getId())
                .stream()
                .filter(r -> r.getStatus() == MissionChangeRequestStatus.PENDING)
                .findFirst().orElse(null);

        Set<Long> userIds = new LinkedHashSet<>();
        addIfPresent(userIds, mission.getEmployeeUserId(), mission.getCreatedBy(),
                     mission.getResponsableUserId(), mission.getHrValidatedBy(),
                     mission.getFinanceDecidedBy());

        List<MissionStatusHistory> history = withHistory
                ? historyRepo.findByMissionIdOrderByCreatedAtAsc(mission.getId())
                : List.of();
        history.stream().map(MissionStatusHistory::getActorUserId)
               .filter(Objects::nonNull).forEach(userIds::add);

        Map<Long, UserRow> users = userRows(userIds);
        return map(mission, expense, pending, users, history);
    }

    private MissionDto map(Mission m, MissionExpense expense, MissionChangeRequest pending,
                           Map<Long, UserRow> users, List<MissionStatusHistory> history) {
        MissionDto dto = new MissionDto();
        dto.setId(m.getId());
        dto.setPaysId(m.getPaysId());

        dto.setEmployeeUserId(m.getEmployeeUserId());
        dto.setEmployeeName(nameOf(users, m.getEmployeeUserId()));
        UserRow employee = users.get(m.getEmployeeUserId());
        dto.setEmployeeRoleName(employee == null ? null : employee.roleName());

        dto.setCreatedBy(m.getCreatedBy());
        dto.setCreatedByName(nameOf(users, m.getCreatedBy()));

        dto.setResponsableUserId(m.getResponsableUserId());
        // The colleague wins over the free text: when both are set, the user row is the
        // authoritative spelling of the name.
        dto.setResponsableDisplayName(m.getResponsableUserId() != null
                ? nameOf(users, m.getResponsableUserId())
                : m.getResponsableName());

        dto.setTitle(m.getTitle());
        dto.setDetails(m.getDetails());
        dto.setStartDate(m.getStartDate());
        dto.setEndDate(m.getEndDate());
        dto.setDurationDays((int) ChronoUnit.DAYS.between(m.getStartDate(), m.getEndDate()) + 1);

        dto.setScope(m.getScope());
        dto.setDestinationPaysId(m.getDestinationPaysId());
        dto.setCountryLabel(m.getCountryLabel());
        dto.setCity(m.getCity());
        dto.setAddress(m.getAddress());

        dto.setStatus(m.getStatus());
        dto.setHrValidatedBy(m.getHrValidatedBy());
        dto.setHrValidatedByName(nameOf(users, m.getHrValidatedBy()));
        dto.setHrValidatedAt(m.getHrValidatedAt());
        dto.setHrNotes(m.getHrNotes());

        dto.setFinanceDecidedBy(m.getFinanceDecidedBy());
        dto.setFinanceDecidedByName(nameOf(users, m.getFinanceDecidedBy()));
        dto.setFinanceDecidedAt(m.getFinanceDecidedAt());
        dto.setFinanceNotes(m.getFinanceNotes());

        dto.setCancelledAt(m.getCancelledAt());
        dto.setCancellationReason(m.getCancellationReason());
        dto.setCreatedAt(m.getCreatedAt());
        dto.setUpdatedAt(m.getUpdatedAt());

        if (expense != null) dto.setExpenses(toExpenseDto(expense));
        if (pending != null) dto.setPendingChangeRequest(toChangeDto(pending, m));
        if (history != null && !history.isEmpty()) {
            dto.setHistory(history.stream().map(h -> {
                MissionHistoryEntryDto entry = new MissionHistoryEntryDto();
                entry.setId(h.getId());
                entry.setFromStatus(h.getFromStatus());
                entry.setToStatus(h.getToStatus());
                entry.setActorUserId(h.getActorUserId());
                entry.setActorName(nameOf(users, h.getActorUserId()));
                entry.setNotes(h.getNotes());
                entry.setCreatedAt(h.getCreatedAt());
                return entry;
            }).toList());
        }
        return dto;
    }

    private MissionExpenseDto toExpenseDto(MissionExpense e) {
        MissionExpenseDto dto = new MissionExpenseDto();
        dto.setCurrency(e.getCurrency());
        dto.setAllowanceDailyRate(e.getAllowanceDailyRate());
        dto.setMissionAllowance(e.getMissionAllowance());
        dto.setLodgingCost(e.getLodgingCost());
        dto.setHotelName(e.getHotelName());
        dto.setNights(e.getNights());
        dto.setReservationNumber(e.getReservationNumber());
        dto.setTransportMode(e.getTransportMode());
        dto.setTransportCarrier(e.getTransportCarrier());
        dto.setTicketReference(e.getTicketReference());
        dto.setTicketCost(e.getTicketCost());
        dto.setOutboundAt(e.getOutboundAt());
        dto.setReturnAt(e.getReturnAt());
        dto.setVisaFees(e.getVisaFees());
        dto.setInsuranceFees(e.getInsuranceFees());
        dto.setOtherFees(e.getOtherFees());
        dto.setOtherFeesLabel(e.getOtherFeesLabel());
        dto.setAdvanceAmount(e.getAdvanceAmount());
        dto.setPaymentMethod(e.getPaymentMethod());
        dto.setCashPickupDate(e.getCashPickupDate());
        dto.setDocumentPickupDate(e.getDocumentPickupDate());
        dto.setHrNotes(e.getHrNotes());
        dto.setTotalEstimatedCost(e.getTotalEstimatedCost());
        dto.setPreparedBy(e.getPreparedBy());
        dto.setPreparedAt(e.getPreparedAt());
        return dto;
    }

    private MissionChangeRequestDto toChangeDto(MissionChangeRequest r, Mission mission) {
        Set<Long> ids = new LinkedHashSet<>();
        if (r.getRequestedBy() != null) ids.add(r.getRequestedBy());
        if (r.getResolvedBy() != null)  ids.add(r.getResolvedBy());
        if (mission != null && mission.getEmployeeUserId() != null) ids.add(mission.getEmployeeUserId());
        Map<Long, UserRow> users = userRows(ids);

        MissionChangeRequestDto dto = new MissionChangeRequestDto();
        dto.setId(r.getId());
        dto.setMissionId(r.getMissionId());
        dto.setMissionTitle(mission == null ? null : mission.getTitle());
        dto.setEmployeeName(mission == null ? null : nameOf(users, mission.getEmployeeUserId()));
        dto.setRequestedBy(r.getRequestedBy());
        dto.setRequestedByName(nameOf(users, r.getRequestedBy()));
        dto.setRequestType(r.getRequestType());
        dto.setRequestedStartDate(r.getRequestedStartDate());
        dto.setRequestedEndDate(r.getRequestedEndDate());
        dto.setReason(r.getReason());
        dto.setStatus(r.getStatus());
        dto.setResolvedBy(r.getResolvedBy());
        dto.setResolvedByName(nameOf(users, r.getResolvedBy()));
        dto.setResolvedAt(r.getResolvedAt());
        dto.setResolutionNotes(r.getResolutionNotes());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }

    // ── Users lookup ──────────────────────────────────────────────────────────

    private record UserRow(Long id, String fullName, String roleName, Long paysId) {}

    /** id → name/role for a batch of users, in one query. Same shape used across this service. */
    private Map<Long, UserRow> userRows(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<UserRow> rows = jdbcTemplate.query(
                "SELECT u.id, u.fullName, u.pays_id, r.frenchName AS role_name "
                        + "FROM Users u LEFT JOIN Roles r ON r.id = u.role_id "
                        + "WHERE u.id IN (" + placeholders + ")",
                (rs, rn) -> new UserRow(rs.getLong("id"), rs.getString("fullName"),
                                        rs.getString("role_name"), rs.getObject("pays_id", Long.class)),
                userIds.toArray());
        Map<Long, UserRow> byId = new LinkedHashMap<>();
        rows.forEach(u -> byId.put(u.id(), u));
        return byId;
    }

    private Long paysIdOf(Long userId) {
        List<Long> found = jdbcTemplate.query(
                "SELECT pays_id FROM Users WHERE id = ?",
                (rs, rn) -> rs.getObject("pays_id", Long.class), userId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String nameOf(Map<Long, UserRow> users, Long id) {
        if (id == null) return null;
        UserRow row = users.get(id);
        return row == null ? null : row.fullName();
    }

    /** Adds the non-null ids to the set — the user columns are mostly nullable. */
    private static void addIfPresent(Set<Long> target, Long... ids) {
        for (Long id : ids) {
            if (id != null) target.add(id);
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String periodLabel(Mission m) {
        return m.getStartDate() + " → " + m.getEndDate();
    }
}
