package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.security.DingTalkProperties;
import com.dingtalk.open.app.api.*;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class DingTalkOaStream {
    private static final Logger log=LoggerFactory.getLogger(DingTalkOaStream.class);
    private final DingTalkOaProperties props;
    private final DingTalkProperties login;
    private final DingTalkOaService service;
    private OpenDingTalkClient client;
    private Instant nextConnect=Instant.EPOCH;
    public DingTalkOaStream(DingTalkOaProperties props,DingTalkProperties login,DingTalkOaService service) {
        this.props=props; this.login=login; this.service=service;
    }
    @Scheduled(fixedDelay=5000,initialDelay=10000,scheduler="dingtalkOaScheduler")
    public void tick() {
        if (!props.enabled() || !login.enabled()) return;
        connect();
        for (Long id:service.pending()) {
            try { service.refresh(id); }
            catch (Exception e) { log.warn("OA 工单 #{} 同步暂未完成，将重试（不输出钉钉响应或凭证）",id); }
        }
    }
    private synchronized void connect() {
        if (!props.streamEnabled() || client!=null || Instant.now().isBefore(nextConnect)) return;
        nextConnect=Instant.now().plusSeconds(60);
        OpenDingTalkClient candidate=null;
        try {
            candidate=OpenDingTalkStreamClientBuilder.custom().credential(new AuthClientCredential(login.clientId(),login.clientSecret()))
                    .consumeThreads(1).registerAllEventListener(event -> {
                        if (!"bpms_instance_change".equals(event.getEventType()) && !"bpms_task_change".equals(event.getEventType())) return EventAckStatus.SUCCESS;
                        try {
                            var data=event.getData();
                            if (data!=null) service.signal(event.getEventCorpId(),data.getString("processCode"),data.getString("processInstanceId"));
                            return EventAckStatus.SUCCESS; // signal is committed before ack; repeated events are harmless.
                        } catch (Exception e) { return EventAckStatus.LATER; }
                    }).build();
            candidate.start(); client=candidate; log.info("钉钉 OA Stream 已启动；仅处理本系统已关联的审批实例");
        } catch (Exception e) {
            if (candidate!=null) try { candidate.stop(); } catch (Exception ignored) { }
            log.warn("钉钉 OA Stream 启动失败，60秒后重试；请检查出站网络和应用事件订阅权限");
        }
    }
    @PreDestroy public synchronized void stop() {
        if (client!=null) try { client.stop(); } catch (Exception ignored) { }
        client=null;
    }
}
