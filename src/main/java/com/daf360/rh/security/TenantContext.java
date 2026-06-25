package com.daf360.rh.security;

public final class TenantContext {

    private TenantContext() {}

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long paysId) { HOLDER.set(paysId); }
    public static Long  get()           { return HOLDER.get(); }
    public static void  clear()         { HOLDER.remove(); }
}
