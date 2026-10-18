package com.example.ts15quarkusbanking.auth;

import jakarta.enterprise.context.RequestScoped;

/** Tenant of the current request, set by JwtTenantFilter from the validated bearer token. */
@RequestScoped
public class TenantContext {
    private String tenantId;

    public String tenantId() {
        return tenantId;
    }

    void set(String tenantId) {
        this.tenantId = tenantId;
    }
}
