package com.daf360.rh.repository;

import com.daf360.rh.domain.AbsenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * The leave-type catalogue.
 *
 * Every read filters out soft-deleted rows. A retired type must still RESOLVE — the requests
 * already filed under it keep its code and still need a label and a balance rule — but it
 * must not be offered on a new request. {@link #findSelectable()} is the offer list;
 * {@link #findByCode} is the resolve path and deliberately ignores {@code active}.
 */
public interface AbsenceTypeRepository extends JpaRepository<AbsenceType, Long> {

    /**
     * Resolve a code stored on an existing request.
     *
     * Does NOT filter on `active`: deactivating a type must not break the history, the
     * balance refund on archive, or the label in a report.
     */
    @Query("SELECT t FROM AbsenceType t WHERE t.code = :code AND t.deleted = false")
    Optional<AbsenceType> findByCode(@Param("code") String code);

    /** What a new request may choose — active, not deleted, in the admin's chosen order. */
    @Query("""
            SELECT t FROM AbsenceType t
            WHERE t.active = true AND t.deleted = false
            ORDER BY t.displayOrder ASC, t.labelFr ASC
            """)
    List<AbsenceType> findSelectable();

    /** The whole catalogue including inactive, for the admin screen and for label lookups. */
    @Query("SELECT t FROM AbsenceType t WHERE t.deleted = false ORDER BY t.displayOrder ASC")
    List<AbsenceType> findAllLive();

    /**
     * Codes a manager is not allowed to see for their team.
     *
     * Returned as the EXCLUSION list rather than the inclusion one so the queue query stays
     * correct when the catalogue is empty or unreachable: an empty exclusion list hides
     * nothing, whereas an empty inclusion list would hide everything.
     */
    @Query("SELECT t.code FROM AbsenceType t WHERE t.managerCanView = false AND t.deleted = false")
    List<String> findCodesHiddenFromManagers();

    /** Codes the HR statistics report counts. */
    @Query("SELECT t.code FROM AbsenceType t WHERE t.includedInHrStats = true AND t.deleted = false")
    List<String> findCodesInHrStats();
}
