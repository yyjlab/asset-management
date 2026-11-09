package com.acme.assetmanagement.integration.deployment;

import java.time.Instant;
import java.util.List;

public record DeploymentRegistrationResult(String externalRegistrationId, String source, String result,
        AssetRef asset, List<Difference> differences, List<String> warnings, boolean replayed, Instant observedAt) {
    public record AssetRef(Long id, String assetTag, long version) {}
    public record Difference(String field, String existing, String incoming) {}
    public DeploymentRegistrationResult replay() {
        return new DeploymentRegistrationResult(externalRegistrationId, source, result, asset, differences, warnings, true, observedAt);
    }
    public int httpStatus() {
        return switch (result) { case "created" -> 201; case "already_exists" -> 200; default -> 409; };
    }
}
