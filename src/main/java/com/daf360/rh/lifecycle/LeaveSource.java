package com.daf360.rh.lifecycle;

import java.time.LocalDate;
import java.util.Set;

/**
 * Qui est en congé aujourd'hui — la seule question que l'automatisation de présence pose
 * aux congés.
 *
 * POURQUOI UNE INTERFACE SANS IMPLÉMENTATION RÉELLE
 * -----------------------------------------------------------------------------
 * Les congés n'ont pas encore de source dans DAF360 : ils arrivent avec l'intégration de
 * l'application Timesheet. Deux façons d'attendre :
 *
 *   · créer une table `leaves` provisoire — et se retrouver, le jour de l'intégration, avec
 *     deux sources de vérité et une reprise de données à faire ;
 *   · nommer le besoin, le brancher, et le laisser répondre « personne » jusqu'à ce que la
 *     vraie source existe.
 *
 * C'est la seconde. {@link NoLeaveSource} renvoie un ensemble vide, le planificateur
 * l'interroge déjà, et l'intégration consistera à écrire UNE classe — sans toucher au
 * planificateur, à la machine à états, ni à l'écran.
 *
 * CE QUE L'IMPLÉMENTATION DEVRA RESPECTER
 * -----------------------------------------------------------------------------
 * Un LOT, pas un appel par employé : le planificateur passe sur tout l'effectif d'une
 * entité, et une question par personne ferait des centaines d'allers-retours vers un
 * service distant chaque matin.
 *
 * Et une DATE explicite, jamais « maintenant » : chaque entité est jugée sur son propre
 * calendrier (voir {@code PaysTimezoneService}), donc l'appelant sait quel jour il demande
 * et l'implémentation n'a pas à le deviner.
 */
public interface LeaveSource {

    /**
     * @param userIds les utilisateurs à examiner — l'effectif d'une entité
     * @param date    le jour à tester, sur le calendrier de cette entité
     * @return ceux qui sont en congé ce jour-là. Jamais null ; vide est une réponse valable.
     */
    Set<Long> usersOnLeave(Set<Long> userIds, LocalDate date);
}
