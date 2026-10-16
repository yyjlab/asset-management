package com.acme.assetmanagement.config;

import com.acme.assetmanagement.workflow.WorkflowNotificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkflowNotificationProperties.class)
public class WorkflowConfiguration {}
