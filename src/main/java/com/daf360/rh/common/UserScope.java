package com.daf360.rh.common;

/**
 * The single predicate that separates real people from accounts that must never appear in a
 * list of people: test logins, duplicated imports, machine accounts.
 *
 * WHY IT IS BINARY
 * -----------------------------------------------------------------------------
 * An earlier version carried a three-value vocabulary (EMPLOYEE | TEST | SERVICE). The third
 * value bought exactly one thing: because SERVICE meant "not a person but must keep working",
 * the replica feed consumed by finance and payroll had to filter differently from the pickers.
 * Two predicates that must never be confused, for a distinction nothing else read. One
 * question is enough — is this a person? — and the replica no longer needs an opinion, because
 * `/api/hr/users-for-sync` mirrors accounts rather than listing people.
 *
 * WHY NOT "has an employee profile"
 * -----------------------------------------------------------------------------
 * Requiring an `employee_profiles` row looks like the obvious filter and is wrong. On this
 * database 155 of 258 active users have no profile, and the overwhelming majority are REAL —
 * the DRH, the PDG, the IT lead, two HR assistants, some 25 site managers and around a hundred
 * collaborators whose HR file simply has not been filled in yet. Profile absence means "the HR
 * file is incomplete", never "this is not a person", so that filter would have hidden the
 * company's own management from every picker.
 *
 * The honest signal is therefore carried by the account itself, on [dbo].[Users].is_employee,
 * which defaults to 1 — so adding the column changed nothing until rows were deliberately
 * reclassified from Administration → Utilisateurs.
 */
public final class UserScope {

    private UserScope() {}

    /**
     * SQL fragment for a query that lists PEOPLE.
     *
     * Deliberately NOT applied to single-row lookups ("who did this?"): the author of an action
     * must still be nameable after their account is reclassified, or an audit trail starts
     * losing names. Nor to Administration → Utilisateurs, which is the one screen where a
     * non-employee account must be visible, since that is where it gets classified.
     *
     * @param alias the table alias used for [dbo].[Users] in the query
     */
    public static String realPeople(String alias) {
        return alias + ".is_employee = 1";
    }
}
