package com.daf360.rh.common;

import java.util.*;

public final class PermissionCatalog {
    private PermissionCatalog() {}

    // ── Consultation ──────────────────────────────────────────────────────────
    public static final String VIEW_DASHBOARD          = "VIEW_DASHBOARD";
    public static final String VIEW_CANDIDATES         = "VIEW_CANDIDATES";
    public static final String VIEW_WORKFLOW           = "VIEW_WORKFLOW";
    public static final String VIEW_NOTIFICATIONS      = "VIEW_NOTIFICATIONS";

    // ── Événements ────────────────────────────────────────────────────────────
    public static final String MANAGE_EVENTS           = "MANAGE_EVENTS";

    // ── Utilisateurs ─────────────────────────────────────────────────────────
    public static final String CREATE_USER             = "CREATE_USER";
    public static final String GET_USERS               = "GET_USERS";
    public static final String UPDATE_USER             = "UPDATE_USER";
    public static final String DELETE_USER             = "DELETE_USER";

    // ── Pays ─────────────────────────────────────────────────────────────────
    public static final String GET_PAYS                = "GET_PAYS";
    public static final String CREATE_PAYS             = "CREATE_PAYS";
    public static final String UPDATE_PAYS             = "UPDATE_PAYS";
    public static final String DELETE_PAYS             = "DELETE_PAYS";

    // ── Jours fériés ─────────────────────────────────────────────────────────
    public static final String GET_HOLIDAYS            = "GET_HOLIDAYS";
    public static final String CREATE_HOLIDAY          = "CREATE_HOLIDAY";
    public static final String UPDATE_HOLIDAY          = "UPDATE_HOLIDAY";
    public static final String DELETE_HOLIDAY          = "DELETE_HOLIDAY";

    // ── Rôles & Permissions ───────────────────────────────────────────────────
    public static final String GET_PERMISSIONS         = "GET_PERMISSIONS";
    public static final String GET_ROLES               = "GET_ROLES";
    public static final String CREATE_ROLE             = "CREATE_ROLE";
    public static final String UPDATE_ROLE             = "UPDATE_ROLE";
    public static final String DELETE_ROLE             = "DELETE_ROLE";

    // ── Congés ───────────────────────────────────────────────────────────────
    public static final String GET_LEAVES              = "GET_LEAVES";
    public static final String ADD_LEAVE               = "ADD_LEAVE";
    public static final String RESPONSE_LEAVE          = "RESPONSE_LEAVE";
    public static final String GET_GLOBAL_LEAVES       = "GET_GLOBAL_LEAVES";
    public static final String SETTLE_LEAVES           = "SETTLE_LEAVES";
    public static final String GET_CATEGORIES          = "GET_CATEGORIES";
    public static final String CREATE_CATEGORY         = "CREATE_CATEGORY";
    public static final String UPDATE_CATEGORY         = "UPDATE_CATEGORY";
    public static final String DELETE_CATEGORY         = "DELETE_CATEGORY";

    // ── Timesheets (TSR) ──────────────────────────────────────────────────────
    public static final String GET_TSR                 = "GET_TSR";
    public static final String CREATE_TSR              = "CREATE_TSR";
    public static final String RESPOND_TSR             = "RESPOND_TSR";
    public static final String GET_GLOBAL_TSR          = "GET_GLOBAL_TSR";

    // ── Module RH ────────────────────────────────────────────────────────────
    public static final String HR_CREATE_PROFILE       = "HR_CREATE_PROFILE";
    public static final String HR_UPDATE_PROFILE       = "HR_UPDATE_PROFILE";
    public static final String HR_ARCHIVE_PROFILE      = "HR_ARCHIVE_PROFILE";
    public static final String HR_ONBOARDING           = "HR_ONBOARDING";
    public static final String HR_ADMIN_ROLES          = "HR_ADMIN_ROLES";
    public static final String CREATE_CANDIDATE        = "CREATE_CANDIDATE";
    public static final String EDIT_CANDIDATE          = "EDIT_CANDIDATE";
    public static final String ACCEPT_REJECT_CANDIDATE = "ACCEPT_REJECT_CANDIDATE";
    public static final String RH_HIRE_CANDIDATE       = "RH_HIRE_CANDIDATE";
    public static final String RH_VIEW_RECRUITMENT_DEMAND   = "RH_VIEW_RECRUITMENT_DEMAND";
    public static final String RH_CREATE_RECRUITMENT_DEMAND = "RH_CREATE_RECRUITMENT_DEMAND";
    public static final String RH_APPROVE_RECRUITMENT_DEMAND= "RH_APPROVE_RECRUITMENT_DEMAND";

    // ── Entretiens (V37) ──────────────────────────────────────────────────────
    public static final String RH_ADMIN_INTERVIEW_TYPES     = "RH_ADMIN_INTERVIEW_TYPES";
    public static final String RH_MANAGE_INTERVIEWS         = "RH_MANAGE_INTERVIEWS";

    // ── Employee Lifecycle Engine (V32) ───────────────────────────────────────
    public static final String RH_VIEW_CONTRACTS            = "RH_VIEW_CONTRACTS";
    public static final String RH_CREATE_CONTRACT           = "RH_CREATE_CONTRACT";
    public static final String RH_MANAGE_LIFECYCLE          = "RH_MANAGE_LIFECYCLE";
    public static final String RH_VALIDATE_TRIAL_PERIOD     = "RH_VALIDATE_TRIAL_PERIOD";
    public static final String RH_MANAGE_ALERTS             = "RH_MANAGE_ALERTS";
    public static final String RH_MANAGE_OFFBOARDING           = "RH_MANAGE_OFFBOARDING";
    public static final String RH_VALIDATE_OFFBOARDING         = "RH_VALIDATE_OFFBOARDING";
    public static final String RH_COMPLETE_OFFBOARDING_TASK    = "RH_COMPLETE_OFFBOARDING_TASK";
    public static final String RH_CONDUCT_EXIT_INTERVIEW       = "RH_CONDUCT_EXIT_INTERVIEW";
    public static final String RH_SUSPEND_PROFILE              = "RH_SUSPEND_PROFILE";

    // ── Temps de travail (régimes & pauses) ───────────────────────────────────
    public static final String ADMIN_REGIMES           = "ADMIN_REGIMES";
    public static final String ADMIN_BREAKS            = "ADMIN_BREAKS";

    // ── Module IT ────────────────────────────────────────────────────────────
    public static final String IT_PROVISIONING         = "IT_PROVISIONING";

    // ── Administration ────────────────────────────────────────────────────────
    public static final String ADMIN_ROLES             = "ADMIN_ROLES";
    public static final String ADMIN_LISTS             = "ADMIN_LISTS";
    public static final String ADMIN_EVENTS            = "ADMIN_EVENTS";
    public static final String ADMIN_NOTIFICATIONS     = "ADMIN_NOTIFICATIONS";

    // ── Groups for catalog API ────────────────────────────────────────────────
    public record PermGroup(String label, List<String> codes) {}

    public static final List<PermGroup> GROUPS = List.of(
        new PermGroup("Consultation",        List.of(VIEW_DASHBOARD, VIEW_CANDIDATES, VIEW_WORKFLOW, VIEW_NOTIFICATIONS)),
        new PermGroup("Événements",          List.of(MANAGE_EVENTS, ADMIN_EVENTS)),
        new PermGroup("Utilisateurs",        List.of(GET_USERS, CREATE_USER, UPDATE_USER, DELETE_USER)),
        new PermGroup("Pays",                List.of(GET_PAYS, CREATE_PAYS, UPDATE_PAYS, DELETE_PAYS)),
        new PermGroup("Jours fériés",        List.of(GET_HOLIDAYS, CREATE_HOLIDAY, UPDATE_HOLIDAY, DELETE_HOLIDAY)),
        new PermGroup("Rôles & Permissions", List.of(GET_PERMISSIONS, GET_ROLES, CREATE_ROLE, UPDATE_ROLE, DELETE_ROLE, HR_ADMIN_ROLES)),
        new PermGroup("Congés",              List.of(GET_LEAVES, ADD_LEAVE, RESPONSE_LEAVE, GET_GLOBAL_LEAVES, SETTLE_LEAVES)),
        new PermGroup("Catégories",          List.of(GET_CATEGORIES, CREATE_CATEGORY, UPDATE_CATEGORY, DELETE_CATEGORY)),
        new PermGroup("Timesheets",          List.of(GET_TSR, CREATE_TSR, RESPOND_TSR, GET_GLOBAL_TSR)),
        new PermGroup("Module RH",           List.of(HR_CREATE_PROFILE, HR_UPDATE_PROFILE, HR_ARCHIVE_PROFILE, HR_ONBOARDING, CREATE_CANDIDATE, EDIT_CANDIDATE, ACCEPT_REJECT_CANDIDATE, RH_VIEW_RECRUITMENT_DEMAND, RH_CREATE_RECRUITMENT_DEMAND, RH_APPROVE_RECRUITMENT_DEMAND)),
        new PermGroup("Cycle de vie",        List.of(RH_VIEW_CONTRACTS, RH_CREATE_CONTRACT, RH_MANAGE_LIFECYCLE, RH_VALIDATE_TRIAL_PERIOD, RH_MANAGE_ALERTS, RH_MANAGE_OFFBOARDING, RH_VALIDATE_OFFBOARDING, RH_COMPLETE_OFFBOARDING_TASK, RH_CONDUCT_EXIT_INTERVIEW, RH_SUSPEND_PROFILE)),
        new PermGroup("Entretiens",          List.of(RH_ADMIN_INTERVIEW_TYPES, RH_MANAGE_INTERVIEWS)),
        new PermGroup("Temps de travail",    List.of(ADMIN_REGIMES, ADMIN_BREAKS)),
        new PermGroup("Module IT",           List.of(IT_PROVISIONING)),
        new PermGroup("Administration",      List.of(ADMIN_LISTS, ADMIN_NOTIFICATIONS, ADMIN_ROLES))
        // NOTE: facturation (FACT_*) and pointage (POINTAGE_*) codes are deliberately
        // NOT listed here. Each module owns its own catalog and manages its codes in its
        // own admin UI (finance: fact-roles-admin). Keeping them out of RH's catalog means
        // (1) they don't show up / get managed in RH's role-admin, and (2) RH's full-replace
        // updatePermissions — whose DELETE is scoped to ALLOWED_PERMISSIONS (= ALL_CODES) —
        // leaves those foreign grants untouched. Cross-module grants go through the per-code
        // POST/DELETE /roles/{id}/permissions/{code} endpoint, which accepts any code.
    );

    public static final Set<String> ALL_CODES;
    static {
        ALL_CODES = new LinkedHashSet<>();
        GROUPS.forEach(g -> ALL_CODES.addAll(g.codes()));
    }
}
