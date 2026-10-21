package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.security.DingTalkProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
@EnableConfigurationProperties(DingTalkOaProperties.class)
public class DingTalkOaClient {
    static final String KEY_FIELD = "资产业务单号", DETAIL_FIELD = "设备交接明细", CONFIRM_FIELD = "员工实物确认";
    static final String ATTESTATION = "本人已核对实物及设备信息，确认收到或交还；请运维核验实物后审批";
    private final DingTalkProperties login;
    private final DingTalkOaProperties props;
    private final RestClient http;
    private String cachedToken;
    private java.time.Instant tokenUntil=java.time.Instant.EPOCH;
    @org.springframework.beans.factory.annotation.Autowired
    public DingTalkOaClient(DingTalkProperties login, DingTalkOaProperties props) {
        this(login, props, createHttp());
    }
    DingTalkOaClient(DingTalkProperties login, DingTalkOaProperties props, RestClient http) {
        this.login=login; this.props=props; this.http=http;
    }
    private static RestClient createHttp() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000); factory.setReadTimeout(15000);
        return RestClient.builder().requestFactory(factory).build();
    }
    record Employee(String userId, long departmentId) {}
    record Start(String instanceId) {}
    synchronized String token() {
        if (cachedToken!=null && java.time.Instant.now().isBefore(tokenUntil)) return cachedToken;
        JsonNode response = http.post().uri("https://api.dingtalk.com/v1.0/oauth2/accessToken")
                .body(Map.of("appKey", login.clientId(), "appSecret", login.clientSecret())).retrieve().body(JsonNode.class);
        cachedToken=required(response,"accessToken");
        long ttl=response.path("expireIn").asLong(120);
        tokenUntil=java.time.Instant.now().plusSeconds(Math.max(0,Math.min(7200,ttl)-60));
        return cachedToken;
    }
    Employee employee(String unionId) {
        String token = token();
        var response = http.post().uri("https://oapi.dingtalk.com/topapi/user/getbyunionid?access_token={token}", token)
                .body(Map.of("unionid", unionId)).retrieve().body(JsonNode.class);
        var result = legacy(response);
        if (result.path("contact_type").asInt(-1) != 0) throw invalid();
        String id = required(result, "userid");
        var user = legacy(http.post().uri("https://oapi.dingtalk.com/topapi/v2/user/get?access_token={token}", token)
                .body(Map.of("userid", id, "language", "zh_CN")).retrieve().body(JsonNode.class));
        if (!id.equals(required(user, "userid")) || !user.path("active").asBoolean(false)
                || !user.path("dept_id_list").isArray() || user.path("dept_id_list").isEmpty()) throw invalid();
        long department = user.path("dept_id_list").get(0).asLong(-1);
        if (department < 1) throw invalid();
        return new Employee(id, department);
    }
    Start start(String code, Employee employee, String key, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originatorUserId", employee.userId()); body.put("deptId", employee.departmentId()); body.put("processCode", code);
        if (props.agentId() != null && props.agentId() > 0) body.put("microappAgentId", props.agentId());
        body.put("formComponentValues", List.of(Map.of("name", KEY_FIELD, "value", key),
                Map.of("name", DETAIL_FIELD, "value", detail), Map.of("name", CONFIRM_FIELD, "value", ATTESTATION)));
        var response = http.post().uri("https://api.dingtalk.com/v1.0/workflow/processInstances")
                .header("x-acs-dingtalk-access-token", token()).body(body).retrieve().body(JsonNode.class);
        return new Start(required(response, "instanceId"));
    }
    JsonNode detail(String id) {
        JsonNode response;
        try {
            response = http.get().uri("https://api.dingtalk.com/v1.0/workflow/processInstances?processInstanceId={id}", id)
                    .header("x-acs-dingtalk-access-token", token()).retrieve().body(JsonNode.class);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            synchronized (this) { cachedToken=null; tokenUntil=java.time.Instant.EPOCH; }
            throw invalid();
        }
        if (response == null || !response.path("result").isObject() || (response.has("success") && !response.path("success").asBoolean())) throw invalid();
        return response.path("result");
    }
    private static JsonNode legacy(JsonNode node) {
        if (node == null || node.path("errcode").asInt(-1) != 0 || !node.path("result").isObject()) throw invalid();
        return node.path("result");
    }
    private static String required(JsonNode node, String key) {
        if (node == null || !node.path(key).isString() || node.path(key).asText().isBlank() || node.path(key).asText().length() > 256) throw invalid();
        return node.path(key).asText();
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_GATEWAY, "钉钉接口返回无效或无权限，请检查应用通讯录与审批权限"); }
}
