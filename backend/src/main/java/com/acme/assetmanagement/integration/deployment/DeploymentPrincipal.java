package com.acme.assetmanagement.integration.deployment;

import java.security.Principal;

public record DeploymentPrincipal(String credentialId, String label) implements Principal {
    public static final String AUTHORITY = "AD_DEPLOYMENT_REGISTER";
    @Override public String getName() { return "ad-deployment:" + credentialId; }
}
