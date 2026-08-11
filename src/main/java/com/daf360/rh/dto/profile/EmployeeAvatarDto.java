package com.daf360.rh.dto.profile;

/**
 * Avatar reference for one user — the minimum another module needs to render a face.
 *
 * <p>Exists because the other modules know a person by their <b>user</b> id (that is what
 * their own tables store: {@code affaires.responsable_user_id} in facturation, for one),
 * while the photo lives on the RH <b>profile</b> and is served by profile id. Resolving
 * that mapping used to require either a name search or a full profile fetch.
 *
 * <p>{@code photoUrl} is returned <b>as stored</b> and is only ever a presence flag: the
 * bytes come from {@code GET /api/hr/profiles/{profileId}/photo}, and plenty of rows carry
 * a {@code photo_url} whose file is missing from storage. Callers must therefore treat a
 * non-null value as "there may be a photo" and keep an initials fallback.
 *
 * @param userId       the id the caller asked for
 * @param profileId    the RH profile id — build the photo URL from this, never from userId
 * @param photoUrl     raw {@code employee_profiles.photo_url}, null when no photo was uploaded
 * @param photoVersion {@code updated_at} in epoch millis, or null. Cache-buster: the photo
 *                     endpoint answers with a 7-day {@code Cache-Control}, and
 *                     {@code photo_url} is a <b>constant</b> per profile
 *                     ({@code /api/hr/profiles/{id}/photo}, rewritten to the same value on
 *                     every upload), so it cannot serve as a version. Without this a
 *                     replaced photo keeps showing the old one for a week.
 * @param fullName     convenience for initials, so a caller with only an id needn't join
 * @param gender       lets a caller apply the same gendered-placeholder rule as rh-frontend
 */
public record EmployeeAvatarDto(
        Long   userId,
        Long   profileId,
        String photoUrl,
        Long   photoVersion,
        String fullName,
        String gender
) {}
