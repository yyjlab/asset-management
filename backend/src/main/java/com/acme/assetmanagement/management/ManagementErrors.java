package com.acme.assetmanagement.management;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.Map;

@RestControllerAdvice(basePackages = {"com.acme.assetmanagement.management", "com.acme.assetmanagement.procurement"})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ManagementErrors {
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT) public Map<String, String> conflict() { return Map.of("message", "记录已变化或请求重复，请刷新核对"); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String, String> invalid() { return Map.of("message", "请求参数格式不正确"); }
}
