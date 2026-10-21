package com.acme.assetmanagement.operations;

import com.acme.assetmanagement.security.DingTalkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DingTalkOaClientTest {
    DingTalkProperties login=new DingTalkProperties(true,"fake-client","fake-secret","http://localhost/api/v1/auth/dingtalk/callback",600,10);
    DingTalkOaProperties props=new DingTalkOaProperties(true,false,"corp","issue","return","it",123L);
    @Test void createUsesOfficialEndpointAndCorrelatedFieldsRatherThanOverridingApprovers() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"appKey\":\"fake-client\",\"appSecret\":\"fake-secret\"}"))
                .andRespond(withSuccess("{\"accessToken\":\"token\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances")).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-acs-dingtalk-access-token","token"))
                .andExpect(jsonPath("$.originatorUserId").value("employee"))
                .andExpect(jsonPath("$.processCode").value("issue"))
                .andExpect(jsonPath("$.deptId").value(1))
                .andExpect(jsonPath("$.microappAgentId").value(123))
                .andExpect(jsonPath("$.approvers").doesNotExist())
                .andExpect(jsonPath("$.formComponentValues[0].name").value("资产业务单号"))
                .andExpect(jsonPath("$.formComponentValues[0].value").value("key"))
                .andExpect(jsonPath("$.formComponentValues[1].value").value("immutable detail"))
                .andRespond(withSuccess("{\"instanceId\":\"real-instance\"}",MediaType.APPLICATION_JSON));
        var client=new DingTalkOaClient(login,props,builder.build());
        assertEquals("real-instance",client.start("issue",new DingTalkOaClient.Employee("employee",1),"key","immutable detail").instanceId());
        server.verify();
    }
    @Test void detailUsesOfficialGetAndDoesNotTreatFailedEnvelopeAsApproval() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andRespond(withSuccess("{\"accessToken\":\"token\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.dingtalk.com/v1.0/workflow/processInstances?processInstanceId=instance"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("x-acs-dingtalk-access-token","token"))
                .andRespond(withSuccess("{\"success\":false,\"result\":{\"status\":\"COMPLETED\",\"result\":\"agree\"}}",MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class,() -> new DingTalkOaClient(login,props,builder.build()).detail("instance")); server.verify();
    }
    @Test void mapsUnionIdentityToActiveEnterpriseMemberAndDepartment() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andRespond(withSuccess("{\"accessToken\":\"token\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/user/getbyunionid?access_token=token"))
                .andExpect(content().json("{\"unionid\":\"union\"}"))
                .andRespond(withSuccess("{\"errcode\":0,\"result\":{\"userid\":\"employee\",\"contact_type\":0}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/get?access_token=token"))
                .andRespond(withSuccess("{\"errcode\":0,\"result\":{\"userid\":\"employee\",\"active\":true,\"dept_id_list\":[1,2]}}",MediaType.APPLICATION_JSON));
        assertEquals(new DingTalkOaClient.Employee("employee",1),new DingTalkOaClient(login,props,builder.build()).employee("union")); server.verify();
    }
    @Test void enabledConfigurationRequiresDifferentTemplatesAndItApprovers() {
        assertThrows(IllegalArgumentException.class,() -> new DingTalkOaProperties(true,true,"corp","same","same","it",null));
        assertThrows(IllegalArgumentException.class,() -> new DingTalkOaProperties(true,true,"corp","issue","return","",null));
        assertDoesNotThrow(() -> new DingTalkOaProperties(false,false,null,null,null,null,null));
        assertFalse(login.toString().contains("fake-secret"));
    }
    @Test void disabledServiceDoesNotContactProviderOrTouchRepositories() {
        var client=org.mockito.Mockito.mock(DingTalkOaClient.class);
        var tasks=org.mockito.Mockito.mock(OperationRepository.class);
        var assets=org.mockito.Mockito.mock(com.acme.assetmanagement.asset.AssetRepository.class);
        var users=org.mockito.Mockito.mock(com.acme.assetmanagement.user.UserRepository.class);
        var service=new DingTalkOaService(new DingTalkOaProperties(false,false,null,null,null,null,null),login,client,tasks,assets,users,
                org.mockito.Mockito.mock(com.acme.assetmanagement.workflow.WorkflowAccess.class),org.mockito.Mockito.mock(OperationService.class),
                org.mockito.Mockito.mock(com.acme.assetmanagement.workflow.TaskNotifications.class),new tools.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        assertThrows(com.acme.assetmanagement.common.ApiException.class,() -> service.create(null));
        org.mockito.Mockito.verifyNoInteractions(client,tasks,assets,users);
    }
    @Test void accessTokenIsReusedUntilItsExpiryInsteadOfRequestedForEveryPoll() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andRespond(withSuccess("{\"accessToken\":\"token\",\"expireIn\":7200}",MediaType.APPLICATION_JSON));
        var client=new DingTalkOaClient(login,props,builder.build());
        assertEquals("token",client.token()); assertEquals("token",client.token()); server.verify();
    }
    @Test void composeOaReferencesParseAndRemainDisabledByDefault() throws Exception {
        try(var input=java.nio.file.Files.newInputStream(java.nio.file.Path.of("..","compose.yaml"))) {
            java.util.Map<?,?> root=new org.yaml.snakeyaml.Yaml().load(input);
            var services=(java.util.Map<?,?>)root.get("services");
            var backend=(java.util.Map<?,?>)services.get("backend");
            var environment=(java.util.Map<?,?>)backend.get("environment");
            assertEquals("${DINGTALK_OA_ENABLED:-false}",environment.get("DINGTALK_OA_ENABLED"));
            assertEquals(7,environment.keySet().stream().filter(k -> k.toString().startsWith("DINGTALK_OA_")).count());
            var mysql=(java.util.Map<?,?>)services.get("mysql");
            assertTrue(mysql.get("volumes").toString().contains("mysql_data:/var/lib/mysql"));
        }
    }
}
