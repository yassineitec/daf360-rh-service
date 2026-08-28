-- =============================================================================
-- V88__sharepoint_delta_state.sql
-- Memorise le jeton delta Microsoft Graph, pour savoir ce qui a change sur le
-- site SharePoint sans interroger un dossier par employe.
--
-- ── LE PROBLEME QUE CA RESOUT ────────────────────────────────────────────────
-- Une photo remplacee DIRECTEMENT dans SharePoint (le flux normal des RH : les
-- fichiers sont deposes a la main) n'etait vue par l'application qu'au bout de
-- 24 h, la fenetre du marqueur .checked. Verifier a chaque affichage coutait un
-- appel Graph PAR EMPLOYE : 200 appels pour un annuaire de 100 personnes, donc
-- un 429 et plusieurs secondes de latence.
--
-- L'API delta renverse le calcul : UN appel sur le drive renvoie tout ce qui a
-- change depuis le jeton precedent — que cinq fichiers aient bouge ou aucun, et
-- quel que soit le nombre d'employes. La fraicheur passe de 24 h a l'intervalle
-- de synchro (quelques dizaines de secondes) pour un cout qui ne depend plus de
-- l'effectif.
--
-- ── POURQUOI UNE TABLE ET PAS UNE VARIABLE ───────────────────────────────────
-- Le jeton doit survivre a un redemarrage. Sans persistance, chaque redeploiement
-- repart de zero : Graph renvoie alors l'ENUMERATION COMPLETE du drive (des
-- milliers d'items pour l'arborescence RH), qui serait traitee comme "tout a
-- change" et viderait tous les caches photo d'un coup. La table est donc ce qui
-- rend la synchro incrementale plutot que destructrice.
--
-- ── FORME ────────────────────────────────────────────────────────────────────
-- Une ligne par PORTEE (scope), pas une ligne unique : le drive du site RH est la
-- seule portee aujourd'hui, mais la paie vise une autre arborescence et devra
-- suivre son propre curseur. Le code cree la ligne a la demande, donc ce script
-- ne seed rien — un scope inconnu n'est pas une erreur, c'est une premiere
-- execution.
--
-- delta_link stocke l'URL COMPLETE renvoyee par Graph (@odata.deltaLink) et non
-- le seul jeton : l'URL porte deja le $select et la portee, et la reconstruire a
-- la main est exactement le genre de detail qui se desynchronise du code appelant.
-- NVARCHAR(MAX) parce qu'un deltaLink depasse regulierement 2000 caracteres.
--
-- Idempotente : creation gardee par IF NOT EXISTS.
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'sharepoint_delta_state'
)
CREATE TABLE [dbo].[sharepoint_delta_state] (
    -- Portee logique, p.ex. 'HR_DRIVE'. Cle naturelle : il n'y a jamais deux
    -- curseurs pour la meme portee, et un id technique n'ajouterait rien.
    [scope]         VARCHAR(40)    NOT NULL,
    [delta_link]    NVARCHAR(MAX)  NULL,
    -- Derniere synchro REUSSIE. Sert au diagnostic ("depuis quand derive-t-on ?"),
    -- pas au declenchement : l'intervalle est tenu en memoire par le service, car
    -- une lecture SQL par requete HTTP pour lire une horloge serait absurde.
    [last_sync_at]  DATETIMEOFFSET NULL,
    -- Compteur cumule d'items traites, pour voir d'un coup d'oeil si une synchro
    -- part en enumeration complete (un pic de plusieurs milliers).
    [items_seen]    BIGINT         NOT NULL CONSTRAINT [DF_sp_delta_items] DEFAULT (0),
    [last_error]    NVARCHAR(500)  NULL,
    CONSTRAINT [PK_sharepoint_delta_state] PRIMARY KEY ([scope])
);
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT scope, last_sync_at, items_seen, last_error,
--        LEN(delta_link) AS delta_link_len
-- FROM sharepoint_delta_state;
--
-- last_sync_at qui n'avance plus = synchro en echec (voir last_error) ; la
-- fenetre de 24 h du marqueur .checked reste alors le filet de securite, donc la
-- panne est une degradation de fraicheur, pas une panne d'affichage.
