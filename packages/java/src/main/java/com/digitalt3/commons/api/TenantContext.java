package com.digitalt3.commons.api;

/**
 * Multi-tenant context. First-class concept in DT3 Commons.
 *
 * @since 0.1.0
 */
public class TenantContext {
    private String tenantId;
    private String tenantRegion;
    private String tenantEnvironment;

    public TenantContext() {}

    public TenantContext(String tenantId, String tenantRegion, String tenantEnvironment) {
        this.tenantId = tenantId;
        this.tenantRegion = tenantRegion;
        this.tenantEnvironment = tenantEnvironment;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTenantRegion() { return tenantRegion; }
    public void setTenantRegion(String tenantRegion) { this.tenantRegion = tenantRegion; }

    public String getTenantEnvironment() { return tenantEnvironment; }
    public void setTenantEnvironment(String tenantEnvironment) { this.tenantEnvironment = tenantEnvironment; }
}
