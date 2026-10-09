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
     * The contract natures the lifecycle engine has rules for (contract_type_config rows,
     * CDD renewal, CIVP eligibility, STAGE/FREELANCE initial status…). Every CONTRACT_TYPE
     * value maps to one of them through {@link ConfigurableListValue#getLifecycleNature}.
     */
    public static final java.util.Set<String> NATURES =
            java.util.Set.of("CDI", "CDD", "CIVP", "STAGE", "FREELANCE", "DETACHEMENT");
    public static final String DEFAULT_NATURE = "CDI";

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

    /**
     * The lifecycle nature (CDI, CDD…) of a stored value — an id, or a legacy code. Falls back
     * to the code itself when it is a nature, then to CDI: a contract is never left without rules.
     */
    public String natureOf(String stored, Long paysId) {
        return find(stored, paysId).map(ContractTypeRefs::natureOf)
                .orElseGet(() -> legacyNature(stored));
    }

    /** The nature of a list value: its lifecycle_nature, else its own code if it is one, else CDI. */
    public static String natureOf(ConfigurableListValue v) {
        String nature = v.getLifecycleNature();
        if (nature != null && NATURES.contains(nature.trim().toUpperCase())) return nature.trim().toUpperCase();
        return legacyNature(v.getValueCode());
    }

    private static String legacyNature(String code) {
        if (code == null) return DEFAULT_NATURE;
        String c = code.trim().toUpperCase();
        if (NATURES.contains(c)) return c;
        return switch (c) {
            case "PORTAGE", "CONSULTANT" -> "FREELANCE";
            case "FIXED_TERM"            -> "CDD";
            case "INTERN"                -> "STAGE";
            default                      -> DEFAULT_NATURE;
        };
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

    /** configurable_list_types.id of CONTRACT_TYPE. */
    public Long listTypeId() {
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
