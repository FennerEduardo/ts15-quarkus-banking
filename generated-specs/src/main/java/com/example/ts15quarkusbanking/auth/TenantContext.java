package com.example.ts15quarkusbanking.auth;

import jakarta.enterprise.context.RequestScoped;

/** Tenant, subject, roles and permissions of the current request, set by JwtTenantFilter from the validated bearer token. */
@RequestScoped
public class TenantContext {
    private String tenantId;
    private String subject;
    private java.util.List<String> roles = java.util.List.of();
    private java.util.List<String> permissions = java.util.List.of();

    public String tenantId() {
        return tenantId;
    }

    public com.example.ts15quarkusbanking.runtime.Contracts.Caller caller() {
        return new com.example.ts15quarkusbanking.runtime.Contracts.Caller(subject, roles, permissions);
    }

    void set(String tenantId) {
        this.tenantId = tenantId;
    }

    void set(String tenantId, String subject, java.util.List<String> roles, java.util.List<String> permissions) {
        this.tenantId = tenantId;
        this.subject = subject;
        this.roles = roles;
        this.permissions = permissions;
    }

    private boolean anonymous;

    /** A read without a token: the default tenant, no roles. Only the public reads of the features accept it. */
    void setAnonymous(String defaultTenant) {
        this.tenantId = defaultTenant;
        this.anonymous = true;
    }

    public boolean anonymous() {
        return anonymous;
    }
}
