package com.acme.assetmanagement.operations;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class DingTalkOaConfiguration {
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("workflow-"); return scheduler;
    }
    // Remote OA timeouts must not delay existing reminders or notification delivery.
    @Bean ThreadPoolTaskScheduler dingtalkOaScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("dingtalk-oa-"); scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
