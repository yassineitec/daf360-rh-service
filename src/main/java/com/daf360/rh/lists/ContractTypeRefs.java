package com.daf360.rh.lists;

import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code employee_profiles.contract_type} (varchar) holds the id of a CONTRACT_TYPE row of
 * configurable_list_values (Admin › Listes configurables › Type de contrat), not its code.
 *
 * <p>This is the single place that converts between that stored id and the value code
 * ({@code CDI}, {@code CDD}…) the rest of the application reasons with. A row not yet
 * migrated still holds a code: {@link #find} resolves it by code, so it keeps displaying
 * until sql/2026-10-08_profile_contract_type_id.sql converts it.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractTypeRefs {

    public static final String LIST_CODE = "CONTRACT_TYPE";

    /**
     * For hand-written SQL: joins the list row of {@code <profileAlias>.contract_type} as
     * {@code <valueAlias>}. TRY_CAST, not CAST: a not-yet-migrated row still holds a code.
     */
    public static String sqlJoin(String profileAlias, String valueAlias) {
        return "LEFT JOIN [dbo].[configurable_list_values] " + valueAlias
             + " ON " + valueAlias + ".id = TRY_CAST(" + profileAlias + ".contract_type AS BIGINT) ";
    }

    /** For hand-written SQL, with {@link #sqlJoin}: the value code, or the legacy code still stored. */
    public static String sqlCode(String profileAlias, String valueAlias) {
        return "COALESCE(" + valueAlias + ".value_code, " + profileAlias + ".contract_type)";
    }

    private final ConfigurableListTypeRepository  typeRepo;
    private final ConfigurableListValueRepository valueRepo;

    /** The list row a stored value points at — by id, or by code for a not-yet-migrated row. */
    public Optional<ConfigurableListValue> find(String stored, Long paysId) {
        if (stored == null || stored.isBlank()) return Optional.empty();
        Long id = parseId(stored);
        if (id != null) {
            return valueRepo.findById(id).filter(v -> v.getListTypeId().equals(listTypeId()));
        }
        return findByCode(stored, paysId);
    }

    /** The value code ({@code CDI}…) of a stored value; an unresolvable value is returned as is. */
    public String codeOf(String stored, Long paysId) {
        if (stored == null || stored.isBlank()) return null;
        return find(stored, paysId).map(ConfigurableListValue::getValueCode).orElse(stored);
    }

    /** The list row id of a stored value, or null when it resolves to nothing. */
    public Long idOf(String stored, Long paysId) {
        return find(stored, paysId).map(ConfigurableListValue::getId).orElse(null);
    }

    /**
     * What to write into {@code employee_profiles.contract_type} for a value sent by a client,
     * which may be a code ({@code CDI}) or an id ({@code "42"}). Null/blank stays null.
     *
     * @throws AppException CONTRACT_TYPE_INVALID when it matches no CONTRACT_TYPE row
     */
    public String toStored(String codeOrId, Long paysId) {
        if (codeOrId == null || codeOrId.isBlank()) return null;
        return find(codeOrId.trim(), paysId)
                .map(v -> String.valueOf(v.getId()))
                .orElseThrow(() -> new AppException(ErrorCode.CONTRACT_TYPE_INVALID,
                        "Type de contrat inconnu : " + codeOrId));
    }

    /**
     * Matches a code case-insensitively; the profile's own pays wins over a global value,
     * and an active value over a deactivated one.
     */
    private Optional<ConfigurableListValue> findByCode(String code, Long paysId) {
        String wanted = code.trim();
        return valueRepo.findByListTypeIdOrderBySortOrderAscLabelFrAsc(listTypeId()).stream()
                .filter(v -> wanted.equalsIgnoreCase(v.getValueCode()))
                .filter(v -> v.getPaysId() == null || Objects.equals(v.getPaysId(), paysId))
                .min(Comparator
                        .comparing((ConfigurableListValue v) -> v.getPaysId() == null)
                        .thenComparing(v -> !Boolean.TRUE.equals(v.getIsActive()))
                        .thenComparing(ConfigurableListValue::getId));
    }

    private Long listTypeId() {
        return typeRepo.findByCode(LIST_CODE)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Liste introuvable : code=" + LIST_CODE))
                .getId();
    }

    private static Long parseId(String s) {
        String t = s.trim();
        if (t.isEmpty() || !t.chars().allMatch(Character::isDigit)) return null;
        try {
            return Long.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
