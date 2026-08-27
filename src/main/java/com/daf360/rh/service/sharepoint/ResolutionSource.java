package com.daf360.rh.service.sharepoint;

/** How a cached employee folder segment was obtained. */
public enum ResolutionSource {

    /** Found by listing the tree and matching the employee's name. Safe to recompute and
     *  overwrite at any time — it is derived data. */
    DISCOVERED,

    /** Entered by an administrator for a folder the convention cannot predict — the real
     *  tree contains at least one ({@code "Bilel ZEDINI-CDI-ARX tunisie"}).
     *
     *  <p>NEVER overwritten by discovery. Without that rule a hand-entered correction
     *  disappears at the next cache refresh and the same incident replays, which makes the
     *  override worse than useless: it would look like it worked, then silently stop. */
    MANUAL
}
