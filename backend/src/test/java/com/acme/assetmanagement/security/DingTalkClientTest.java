package com.acme.assetmanagement.security;

import com.acme.assetmanagement.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DingTalkClientTest {
    private final DingTalkProperties properties = new DingTalkProperties(true, "fake-client", "fake-secret",
            "http://asset.wfq.com/api/v1/auth/dingtalk/callback", 600, 10);

    @Test void exchangesCodeOnServerAndRetrievesStableIdentity() {
        var builder = RestClient.builder().baseUrl("https://api.dingtalk.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/userAccessToken"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"clientId\":\"fake-client\",\"clientSecret\":\"fake-secret\",\"code\":\"code\",\"grantType\":\"authorization_code\"}"))
                .andRespond(withSuccess("{\"accessToken\":\"user-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.dingtalk.com/v1.0/contact/users/me"))
                .andExpect(header("x-acs-dingtalk-access-token", "user-token"))
                .andRespond(withSuccess("{\"unionId\":\"stable-identity\",\"nick\":\"admin\"}", MediaType.APPLICATION_JSON));
        assertEquals("stable-identity", new DingTalkClient(properties, builder.build()).unionId("code"));
        server.verify();
    }

    @Test void providerErrorBodyAndMissingIdentityAreNotTrustedOrExposed() {
        var builder = RestClient.builder().baseUrl("https://api.dingtalk.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/userAccessToken"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("fake-secret sensitive-token"));
        var exception = assertThrows(ApiException.class, () -> new DingTalkClient(properties, builder.build()).unionId("code"));
        assertFalse(exception.getMessage().contains("fake-secret"));
        assertNull(exception.getCause());
        server.verify();
    }

    @Test void missingTokenFailsClosed() {
        var builder = RestClient.builder().baseUrl("https://api.dingtalk.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/userAccessToken"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(ApiException.class, () -> new DingTalkClient(properties, builder.build()).unionId("code"));
        server.verify();
    }

    @Test void enabledConfigurationRejectsInvalidCallbackAndNeverPrintsSecret() {
        assertThrows(IllegalArgumentException.class, () -> new DingTalkProperties(true, "id", "secret", "http://asset.wfq.com/other", 600, 10));
        assertThrows(IllegalArgumentException.class, () -> new DingTalkProperties(true, "id", "", properties.redirectUri(), 600, 10));
        assertThrows(IllegalArgumentException.class, () -> new DingTalkProperties(true, "id", "secret", properties.redirectUri(), 600, 0));
        assertFalse(properties.toString().contains("fake-secret"));
    }

    @Test void employeeNameComesFromEnterpriseDirectoryNotEditableNickname() {
        var builder = RestClient.builder().baseUrl("https://api.dingtalk.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andExpect(content().json("{\"appKey\":\"fake-client\",\"appSecret\":\"fake-secret\"}"))
                .andRespond(withSuccess("{\"accessToken\":\"enterprise-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/user/getbyunionid?access_token=enterprise-token"))
                .andExpect(content().json("{\"unionid\":\"employee-union\"}"))
                .andRespond(withSuccess("{\"errcode\":0,\"result\":{\"contact_type\":0,\"userid\":\"staff-1\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/get?access_token=enterprise-token"))
                .andExpect(content().json("{\"userid\":\"staff-1\",\"language\":\"zh_CN\"}"))
                .andRespond(withSuccess("{\"errcode\":0,\"result\":{\"userid\":\"staff-1\",\"name\":\"张三\",\"active\":true}}", MediaType.APPLICATION_JSON));
        assertEquals("张三", new DingTalkClient(properties, builder.build()).employeeName("employee-union"));
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"errcode\":60121,\"errmsg\":\"sensitive-token\"}",
            "{\"errcode\":0,\"result\":{\"contact_type\":1,\"userid\":\"external\"}}",
            "{\"errcode\":0,\"result\":{\"userid\":\"unknown-type\"}}"})
    void externalUnknownAndDirectoryFailuresAreRejected(String membership) {
        var builder = RestClient.builder().baseUrl("https://api.dingtalk.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andRespond(withSuccess("{\"accessToken\":\"enterprise-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/user/getbyunionid?access_token=enterprise-token"))
                .andRespond(withSuccess(membership, MediaType.APPLICATION_JSON));
        var failure = assertThrows(ApiException.class, () -> new DingTalkClient(properties, builder.build()).employeeName("outsider"));
        assertFalse(failure.getMessage().contains("sensitive-token"));
        server.verify();
    }
}
