package com.acme.assetmanagement.integration.deployment;

import com.acme.assetmanagement.common.ApiException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestControllerAdvice(assignableTypes = {DeploymentRegistrationController.class, DeploymentCredentialController.class, DeploymentManagementController.class,
        DeploymentIntakeController.class, DeploymentIntakeManagementController.class, WindowsCollectionController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DeploymentExceptionHandler {
    public record Error(int status, String code, String message, List<String> fields) {}
    @ExceptionHandler(DeploymentApiException.class)
    public ResponseEntity<Error> deployment(DeploymentApiException exception) {
        return ResponseEntity.status(exception.status()).body(new Error(exception.status().value(), exception.code(), exception.getMessage(), exception.fields()));
    }
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Error> asset(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new Error(exception.getStatus().value(), "ASSET_RULE_REJECTED", exception.getMessage(), List.of()));
    }
}
