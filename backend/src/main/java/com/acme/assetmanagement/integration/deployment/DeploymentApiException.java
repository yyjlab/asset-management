package com.acme.assetmanagement.integration.deployment;

import org.springframework.http.HttpStatus;
import java.util.List;

public class DeploymentApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<String> fields;
    public DeploymentApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }
    public DeploymentApiException(HttpStatus status, String code, String message, List<String> fields) {
        super(message); this.status = status; this.code = code; this.fields = List.copyOf(fields);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<String> fields() { return fields; }
}
