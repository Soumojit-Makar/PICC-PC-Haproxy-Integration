package com.nnp.haproxy;

import com.nnp.haproxy.controller.HAProxyController;
import com.nnp.haproxy.exception.GlobalExceptionHandler;
import com.nnp.haproxy.feign.HAProxyFeignClient;
import com.nnp.haproxy.feign.model.ACL;
import com.nnp.haproxy.feign.model.BESwitchRule;
import com.nnp.haproxy.feign.model.Backend;
import com.nnp.haproxy.feign.model.BackendServer;
import com.nnp.haproxy.feign.model.Frontend;
import com.nnp.haproxy.feign.model.HTTPRequestRule;
import com.nnp.haproxy.feign.model.Reload;
import com.nnp.haproxy.feign.model.Transaction;
import com.nnp.haproxy.model.ApiResponse;
import com.nnp.haproxy.model.BackendType;
import com.nnp.haproxy.model.HAProxyIn;
import com.nnp.haproxy.service.HAProxyConfigParser;
import com.nnp.haproxy.service.HAProxyConfigParser.ConfigInventory;
import com.nnp.haproxy.service.HAProxyService;
import com.nnp.haproxy.service.K8SService;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive Unit and Integration-style mock tests for haproxy-integration.
 */
@ExtendWith(MockitoExtension.class)
class HaproxyIntegrationApplicationTests {

    private static final String TXN_ID = "txn-abc-123";
    private static final String FE = "http_front";

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private HAProxyIn buildRequest() {
        HAProxyIn req = new HAProxyIn();
        req.setCompName("test-svc");
        req.setNamespace("test-ns");
        req.setInternalPort(8080);
        req.setDomain("test-svc.example.com");
        req.setParentFE(FE);
        req.setLineIndex(5);
        return req;
    }

    private Transaction buildTransaction(String status) {
        Transaction t = new Transaction();
        t.setId(TXN_ID);
        t.setStatus(status);
        return t;
    }

    private Reload buildReload(String status) {
        Reload r = new Reload();
        r.setId("reload-1");
        r.setStatus(status);
        return r;
    }

    private ACL buildAcl(String aclName) {
        ACL acl = new ACL();
        acl.setAcl_name(aclName);
        acl.setCriterion("hdr(host)");
        acl.setValue("-i " + aclName + ".example.com");
        return acl;
    }

    private BESwitchRule buildRule(String ruleName) {
        BESwitchRule rule = new BESwitchRule();
        rule.setName(ruleName);
        rule.setCond("if");
        rule.setCond_test("is_" + ruleName);
        return rule;
    }

    private Backend buildBackend(String name) {
        Backend be = new Backend();
        be.setName(name);
        be.setMode("http");
        return be;
    }

    private Frontend buildFrontend(String name) {
        Frontend fe = new Frontend();
        fe.setName(name);
        fe.setMode("http");
        return fe;
    }

    private FeignException badRequest(String msg) {
        return new FeignException.BadRequest(msg, httpRequest(), new byte[0], Map.of());
    }

    private FeignException notFound(String msg) {
        return new FeignException.NotFound(msg, httpRequest(), new byte[0], Map.of());
    }

    private Request httpRequest() {
        return Request.create(Request.HttpMethod.PUT,
                "http://localhost/v3/services/haproxy/transactions/txn",
                Map.of(), new byte[0], java.nio.charset.StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // HAProxyService  -  Register Flow (All Backend Types)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HAProxyService  -  Register Flow (All Backend Types)")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class HAProxyServiceRegisterTests {

        @Mock
        private HAProxyFeignClient proxyClient;

        @Mock
        private K8SService k8sService;

        @InjectMocks
        private HAProxyService haProxyService;

        private final List<ACL> activeAcls = new ArrayList<>();
        private final List<BESwitchRule> activeRules = new ArrayList<>();

        @BeforeEach
        void setUpHappyPath() {
            activeAcls.clear();
            activeRules.clear();

            when(k8sService.checkCompStatus(anyString(), anyString())).thenReturn(true);

            // Raw re-import (syncConfigFromFile)
            when(proxyClient.getVersion()).thenReturn(ResponseEntity.ok(42));
            when(proxyClient.getRawConfig()).thenReturn(ResponseEntity.ok("# raw config\nbackend pre-svc"));
            when(proxyClient.postRawConfig(anyInt(), anyBoolean(), anyString())).thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());

            // Reconcile: the model already contains the file's only backend
            when(proxyClient.listBackends()).thenReturn(ResponseEntity.ok(List.of(buildBackend("pre-svc"))));
            when(proxyClient.listBEServers(anyString())).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.listFrontends()).thenReturn(ResponseEntity.ok(List.of()));

            // Transaction
            when(proxyClient.createTransaction(42)).thenReturn(ResponseEntity.ok(buildTransaction("open")));
            when(proxyClient.commitTransaction(anyString(), any())).thenReturn(ResponseEntity.ok(buildTransaction("success")));

            // Backend + server dynamic returns
            when(proxyClient.addBE(eq(TXN_ID), any(Backend.class))).thenAnswer(inv -> {
                Backend b = inv.getArgument(1);
                return ResponseEntity.ok(b);
            });
            when(proxyClient.addBEServer(eq(TXN_ID), anyString(), any(BackendServer.class))).thenAnswer(inv -> {
                BackendServer s = inv.getArgument(2);
                return ResponseEntity.ok(s);
            });

            // Backend HTTP Request Rule (for PATH_REWRITE)
            when(proxyClient.createBackendHTTPRequestRule(eq(TXN_ID), anyString(), anyInt(), any(HTTPRequestRule.class)))
                    .thenReturn(ResponseEntity.ok(new HTTPRequestRule()));

            // Frontend HTTP rules
            when(proxyClient.listHTTPRequestRules(FE)).thenReturn(ResponseEntity.ok(List.of()));

            // ACL + switching rule dynamic returns
            when(proxyClient.createACL(eq(TXN_ID), eq(FE), anyInt(), any(ACL.class))).thenAnswer(inv -> {
                ACL a = inv.getArgument(3);
                activeAcls.add(a);
                return ResponseEntity.ok(a);
            });
            when(proxyClient.createBESwitchingRule(eq(TXN_ID), eq(FE), anyInt(), any(BESwitchRule.class))).thenAnswer(inv -> {
                BESwitchRule r = inv.getArgument(3);
                activeRules.add(r);
                return ResponseEntity.ok(r);
            });

            // Post-commit verification
            when(proxyClient.listACLs(FE)).thenAnswer(inv -> ResponseEntity.ok(new ArrayList<>(activeAcls)));
            when(proxyClient.listBESwitchingRules(FE)).thenAnswer(inv -> ResponseEntity.ok(new ArrayList<>(activeRules)));
            when(proxyClient.getBackend(anyString())).thenAnswer(inv -> {
                String name = inv.getArgument(0);
                return ResponseEntity.ok(buildBackend(name));
            });
            when(proxyClient.getReloads()).thenReturn(ResponseEntity.ok(List.of(buildReload("succeeded"))));
        }

        @Test
        @DisplayName("1. K8S_DNS type (default)  -  builds FQDN address with check enabled")
        void testRegister_k8sDns_success() {
            HAProxyIn req = buildRequest();
            req.setBackendType(BackendType.K8S_DNS);

            haProxyService.registerComponent(req);

            verify(proxyClient).addBE(eq(TXN_ID), argThat(be -> "http".equals(be.getMode()) && "test-svc".equals(be.getName())));
            verify(proxyClient).addBEServer(eq(TXN_ID), eq("test-svc"), argThat(s ->
                    "test-svc.test-ns.svc.cluster.local".equals(s.getAddress())
                            && s.getPort() == 8080
                            && "enabled".equals(s.getCheck())
                            && s.getSsl() == null));
            verify(proxyClient).createACL(eq(TXN_ID), eq(FE), eq(5), any());
            verify(proxyClient).createBESwitchingRule(eq(TXN_ID), eq(FE), eq(5), any());
            assertThat(haProxyService.getLastResult("test-svc")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("2. EXTERNAL_IP type  -  uses raw serverAddress/serverPort and skips K8s check")
        void testRegister_externalIp_success() {
            HAProxyIn req = new HAProxyIn();
            req.setCompName("dms-service");
            req.setBackendType(BackendType.EXTERNAL_IP);
            req.setServerAddress("192.0.2.10");
            req.setServerPort(9001);
            req.setDomain("dms.example.com");
            req.setParentFE(FE);
            req.setLineIndex(0);

            haProxyService.registerComponent(req);

            verifyNoInteractions(k8sService); // skipped for EXTERNAL_IP
            verify(proxyClient).addBEServer(eq(TXN_ID), eq("dms-service"), argThat(s ->
                    "192.0.2.10".equals(s.getAddress())
                            && s.getPort() == 9001
                            && "enabled".equals(s.getCheck())));
            assertThat(haProxyService.getLastResult("dms-service")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("3. SSL type  -  configures server with ssl enabled and verify none")
        void testRegister_sslType_success() {
            HAProxyIn req = buildRequest();
            req.setCompName("keycloak");
            req.setBackendType(BackendType.SSL);
            req.setInternalPort(8443);

            haProxyService.registerComponent(req);

            verify(proxyClient).addBEServer(eq(TXN_ID), eq("keycloak"), argThat(s ->
                    "enabled".equals(s.getSsl())
                            && "none".equals(s.getVerify())
                            && s.getPort() == 8443));
            assertThat(haProxyService.getLastResult("keycloak")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("4. PATH_REWRITE type  -  creates http-request replace-path rule on backend")
        void testRegister_pathRewrite_success() {
            HAProxyIn req = buildRequest();
            req.setCompName("mgw_backend");
            req.setBackendType(BackendType.PATH_REWRITE);
            req.setPathPrefix("/mgw");

            haProxyService.registerComponent(req);

            verify(proxyClient).createBackendHTTPRequestRule(eq(TXN_ID), eq("mgw_backend"), eq(0), argThat(r ->
                    "replace-path".equals(r.getType())
                            && "/mgw(/)?(.*)$".equals(r.getReplaceMatch())
                            && "/\\2".equals(r.getReplaceValue())));
            assertThat(haProxyService.getLastResult("mgw_backend")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("5. WEBSOCKET type  -  sets custom timeoutTunnel on backend")
        void testRegister_websocket_success() {
            HAProxyIn req = buildRequest();
            req.setCompName("nats-sse");
            req.setBackendType(BackendType.WEBSOCKET);
            req.setTimeoutTunnel(3_600_000L);

            haProxyService.registerComponent(req);

            verify(proxyClient).addBE(eq(TXN_ID), argThat(be ->
                    Long.valueOf(3_600_000L).equals(be.getTimeoutTunnel())));
            assertThat(haProxyService.getLastResult("nats-sse")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("6. TIME_CONFIGURABLE type  -  defaults to 600s server and tunnel timeouts")
        void testRegister_timeConfigurable_success() {
            HAProxyIn req = buildRequest();
            req.setCompName("ai-service");
            req.setBackendType(BackendType.TIME_CONFIGURABLE);

            haProxyService.registerComponent(req);

            verify(proxyClient).addBE(eq(TXN_ID), argThat(be ->
                    Long.valueOf(600_000L).equals(be.getTimeoutServer())
                            && Long.valueOf(600_000L).equals(be.getTimeoutTunnel())));
            assertThat(haProxyService.getLastResult("ai-service")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("7. TCP type  -  sets mode=tcp, skips ACL and switching rule creation")
        void testRegister_tcpType_success() {
            HAProxyIn req = buildRequest();
            req.setCompName("spoe-auth");
            req.setBackendType(BackendType.TCP);

            haProxyService.registerComponent(req);

            verifyNoInteractions(k8sService); // skipped for TCP
            verify(proxyClient).addBE(eq(TXN_ID), argThat(be -> "tcp".equals(be.getMode())));
            verify(proxyClient, never()).createACL(any(), any(), anyInt(), any());
            verify(proxyClient, never()).createBESwitchingRule(any(), any(), anyInt(), any());
            verify(proxyClient).commitTransaction(eq(TXN_ID), eq(true));
            assertThat(haProxyService.getLastResult("spoe-auth")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("addToHAProxy  -  waitForReload waits until status is succeeded")
        void testRegister_waitForReloadSucceeded() {
            haProxyService.registerComponent(buildRequest());

            verify(proxyClient).commitTransaction(eq(TXN_ID), eq(true));
            verify(proxyClient, atLeastOnce()).getReloads();
            assertThat(haProxyService.getLastResult("test-svc")).isEqualTo("SUCCESS");
        }
    }

    // -------------------------------------------------------------------------
    // HAProxyService  -  Deregister Flow
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HAProxyService  -  Deregister Flow")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class HAProxyServiceDeregisterTests {

        @Mock
        private HAProxyFeignClient proxyClient;

        @Mock
        private K8SService k8sService;

        @InjectMocks
        private HAProxyService haProxyService;

        @BeforeEach
        void setUpDeregisterHappyPath() {
            when(proxyClient.getVersion()).thenReturn(ResponseEntity.ok(42));
            when(proxyClient.getRawConfig()).thenReturn(ResponseEntity.ok("# raw config\nbackend pre-svc"));
            when(proxyClient.postRawConfig(anyInt(), anyBoolean(), anyString())).thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());
            when(proxyClient.listBackends()).thenReturn(ResponseEntity.ok(List.of(buildBackend("pre-svc"))));
            when(proxyClient.listBEServers(anyString())).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.listFrontends()).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.createTransaction(42)).thenReturn(ResponseEntity.ok(buildTransaction("open")));
            when(proxyClient.listBESwitchingRules(FE)).thenReturn(ResponseEntity.ok(List.of(buildRule("test-svc"), buildRule("other-svc"))));
            when(proxyClient.listACLs(FE)).thenReturn(ResponseEntity.ok(List.of(buildAcl("is_test-svc"), buildAcl("is_other-svc"))));
            when(proxyClient.deleteBESwitchingRule(anyString(), anyString(), anyInt())).thenReturn(ResponseEntity.ok(null));
            when(proxyClient.deleteACL(anyString(), anyString(), anyInt())).thenReturn(ResponseEntity.ok(null));
            when(proxyClient.deleteBEServer(anyString(), anyString(), anyString())).thenReturn(ResponseEntity.ok(null));
            when(proxyClient.deleteBE(anyString(), anyString())).thenReturn(ResponseEntity.ok(null));
            when(proxyClient.commitTransaction(anyString(), any())).thenReturn(ResponseEntity.ok(buildTransaction("success")));
            when(proxyClient.getReloads()).thenReturn(ResponseEntity.ok(List.of(buildReload("succeeded"))));
        }

        @Test
        @DisplayName("deregisterComponent  -  deletes rule/ACL at list position, backend, server, and waits for reload")
        void testDeregister_success() {
            haProxyService.deregisterComponent(buildRequest());

            verify(proxyClient).deleteBESwitchingRule(TXN_ID, FE, 0);
            verify(proxyClient).deleteACL(TXN_ID, FE, 0);
            verify(proxyClient).deleteBEServer(TXN_ID, "test-svc", "test-svc");
            verify(proxyClient).deleteBE(TXN_ID, "test-svc");
            verify(proxyClient).commitTransaction(eq(TXN_ID), eq(true));
            verify(proxyClient, atLeastOnce()).getReloads();
            assertThat(haProxyService.getLastResult("test-svc")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("deregisterComponent  -  404 deleting server/backend is tolerated")
        void testDeregister_404Tolerated() {
            when(proxyClient.deleteBEServer(TXN_ID, "test-svc", "test-svc")).thenThrow(notFound("not found"));
            when(proxyClient.deleteBE(TXN_ID, "test-svc")).thenThrow(notFound("not found"));

            haProxyService.deregisterComponent(buildRequest());

            verify(proxyClient).commitTransaction(eq(TXN_ID), eq(true));
            assertThat(haProxyService.getLastResult("test-svc")).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("deregisterComponent  -  flow error discards transaction")
        void testDeregister_flowError_discardsTransaction() {
            when(proxyClient.listBESwitchingRules(FE)).thenThrow(badRequest("boom"));

            haProxyService.deregisterComponent(buildRequest());

            verify(proxyClient).deleteTransaction(TXN_ID);
            assertThat(haProxyService.getLastResult("test-svc")).startsWith("FAILED");
        }
    }

    // -------------------------------------------------------------------------
    // HAProxyService  -  Manual Sync & Reconcile (Sync Feature)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HAProxyService  -  Manual Sync & Reconcile")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class HAProxyServiceSyncTests {

        @Mock
        private HAProxyFeignClient proxyClient;

        @InjectMocks
        private HAProxyService haProxyService;

        @Test
        @DisplayName("syncConfigWithModel  -  primary path: postRawConfig succeeds and returns clean report")
        void testSync_primaryPath_reimportsSuccessfully() {
            String manualConfig = "backend manual-be\n  server srv 10.0.0.1:80 check\n";
            when(proxyClient.getVersion()).thenReturn(ResponseEntity.ok(42));
            when(proxyClient.getRawConfig()).thenReturn(ResponseEntity.ok(manualConfig));
            when(proxyClient.postRawConfig(eq(42), eq(true), eq(manualConfig)))
                    .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());

            HAProxyService.ReconcileReport report = haProxyService.syncConfigWithModel();

            verify(proxyClient).postRawConfig(eq(42), eq(true), eq(manualConfig));
            verify(proxyClient, never()).createTransaction(anyInt());
            assertThat(report.isClean()).isTrue();
        }

        @Test
        @DisplayName("syncConfigWithModel  -  fallback path: when postRawConfig fails, migrates records via transaction")
        void testSync_fallbackPath_migratesViaTransaction() {
            String manualConfig = """
                    backend ssl-backend
                      mode http
                      timeout server 600s
                      timeout tunnel 1h
                      http-request replace-path /api(/)?(.*) /v1/\\2
                      server keycloak-srv 10.0.0.10:8443 check ssl verify none resolvers k8s_dns
                    frontend http_front
                      acl is_ssl hdr(host) -i ssl.example.com
                      use_backend ssl-backend if is_ssl
                    """;
            when(proxyClient.getVersion()).thenReturn(ResponseEntity.ok(42));
            when(proxyClient.getRawConfig()).thenReturn(ResponseEntity.ok(manualConfig));
            // Simulate postRawConfig failure so fallback runs
            when(proxyClient.postRawConfig(anyInt(), anyBoolean(), anyString()))
                    .thenThrow(badRequest("raw import unsupported"));
            when(proxyClient.createTransaction(42)).thenReturn(ResponseEntity.ok(buildTransaction("open")));
            when(proxyClient.listBackends()).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.listBEServers(anyString())).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.listFrontends()).thenReturn(ResponseEntity.ok(List.of(buildFrontend("http_front"))));
            when(proxyClient.listACLs(anyString())).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.listBESwitchingRules(anyString())).thenReturn(ResponseEntity.ok(List.of()));
            when(proxyClient.commitTransaction(anyString(), any())).thenReturn(ResponseEntity.ok(buildTransaction("success")));

            HAProxyService.ReconcileReport report = haProxyService.syncConfigWithModel();

            // Backend with timeouts migrated
            verify(proxyClient).addBE(eq(TXN_ID), argThat(be ->
                    "ssl-backend".equals(be.getName())
                            && Long.valueOf(600_000L).equals(be.getTimeoutServer())
                            && Long.valueOf(3_600_000L).equals(be.getTimeoutTunnel())));

            // Replace path rule migrated
            verify(proxyClient).createBackendHTTPRequestRule(eq(TXN_ID), eq("ssl-backend"), eq(0), argThat(rule ->
                    "replace-path".equals(rule.getType())
                            && "/api(/)?(.*)".equals(rule.getReplaceMatch())
                            && "/v1/\\2".equals(rule.getReplaceValue())));

            // Server with SSL, verify none, resolvers migrated
            verify(proxyClient).addBEServer(eq(TXN_ID), eq("ssl-backend"), argThat(srv ->
                    "keycloak-srv".equals(srv.getName())
                            && "10.0.0.10".equals(srv.getAddress())
                            && srv.getPort() == 8443
                            && "enabled".equals(srv.getCheck())
                            && "enabled".equals(srv.getSsl())
                            && "none".equals(srv.getVerify())
                            && "k8s_dns".equals(srv.getResolvers())));

            // ACL and switching rule migrated
            verify(proxyClient).createACL(eq(TXN_ID), eq("http_front"), eq(0), argThat(acl ->
                    "is_ssl".equals(acl.getAcl_name())));
            verify(proxyClient).createBESwitchingRule(eq(TXN_ID), eq("http_front"), eq(0), argThat(rule ->
                    "ssl-backend".equals(rule.getName())));

            verify(proxyClient).commitTransaction(eq(TXN_ID), any());

            assertThat(report.getMigratedBackends()).containsExactly("ssl-backend");
            assertThat(report.getMigratedServers()).containsExactly("ssl-backend/keycloak-srv");
            assertThat(report.getMigratedAcls()).containsExactly("http_front/is_ssl");
            assertThat(report.getMigratedRules()).containsExactly("http_front/ssl-backend");
        }
    }

    // -------------------------------------------------------------------------
    // HAProxyConfigParser  -  Unit Tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HAProxyConfigParser  -  Raw haproxy.cfg Parsing")
    class HAProxyConfigParserTests {

        @Test
        @DisplayName("parse  -  accurately extracts backends, timeouts, replace-path, servers with SSL and frontend rules")
        void testParse_allFeatures() {
            String config = """
                    global
                      master-worker
                    defaults unnamed_defaults_1
                      mode http
                    resolvers k8s_dns
                      nameserver dns1 10.96.0.10:53
                    backend mgw_backend
                      mode http
                      timeout server 600s
                      timeout tunnel 1h
                      http-request replace-path /mgw(/)?(.*) /\\2
                      server mgw 10.244.0.5:8080 check
                    backend keycloak_be
                      mode http
                      server keycloak keycloak.auth.svc.cluster.local:8443 ssl verify none resolvers k8s_dns
                    frontend http_front
                      acl is_mgw hdr(host) -i gateway.example.com
                      use_backend mgw_backend if is_mgw
                    """;

            ConfigInventory inventory = HAProxyConfigParser.parse(config);

            assertThat(inventory.getBackends()).hasSize(2);

            // Backend 1: mgw_backend
            HAProxyConfigParser.BackendRecord mgw = inventory.getBackends().get(0);
            assertThat(mgw.getName()).isEqualTo("mgw_backend");
            assertThat(mgw.getTimeoutServer()).isEqualTo(600_000L);
            assertThat(mgw.getTimeoutTunnel()).isEqualTo(3_600_000L);
            assertThat(mgw.getReplacePathRules()).hasSize(1);
            assertThat(mgw.getReplacePathRules().get(0).getMatch()).isEqualTo("/mgw(/)?(.*)");
            assertThat(mgw.getReplacePathRules().get(0).getReplacement()).isEqualTo("/\\2");
            assertThat(mgw.getServers().get(0).getAddress()).isEqualTo("10.244.0.5");
            assertThat(mgw.getServers().get(0).getPort()).isEqualTo(8080);
            assertThat(mgw.getServers().get(0).isCheck()).isTrue();

            // Backend 2: keycloak_be
            HAProxyConfigParser.BackendRecord kc = inventory.getBackends().get(1);
            assertThat(kc.getName()).isEqualTo("keycloak_be");
            assertThat(kc.getServers().get(0).isSsl()).isTrue();
            assertThat(kc.getServers().get(0).getVerify()).isEqualTo("none");
            assertThat(kc.getServers().get(0).getResolvers()).isEqualTo("k8s_dns");

            // Frontend ACLs and rules
            assertThat(inventory.getAcls()).hasSize(1);
            assertThat(inventory.getAcls().get(0).getAclName()).isEqualTo("is_mgw");
            assertThat(inventory.getSwitchingRules()).hasSize(1);
            assertThat(inventory.getSwitchingRules().get(0).getBackend()).isEqualTo("mgw_backend");

            // Unsupported sections captured cleanly
            assertThat(inventory.getUnsupported()).anyMatch(u -> "resolvers".equals(u.getType()));
        }

        @Test
        @DisplayName("parseTimeoutToMs  -  handles s, m, h, d, ms and plain numbers")
        void testParseTimeoutToMs() {
            assertThat(HAProxyConfigParser.parseTimeoutToMs("60s")).isEqualTo(60_000L);
            assertThat(HAProxyConfigParser.parseTimeoutToMs("10m")).isEqualTo(600_000L);
            assertThat(HAProxyConfigParser.parseTimeoutToMs("1h")).isEqualTo(3_600_000L);
            assertThat(HAProxyConfigParser.parseTimeoutToMs("1d")).isEqualTo(86_400_000L);
            assertThat(HAProxyConfigParser.parseTimeoutToMs("500ms")).isEqualTo(500L);
            assertThat(HAProxyConfigParser.parseTimeoutToMs("5000")).isEqualTo(5000L);
            assertThat(HAProxyConfigParser.parseTimeoutToMs(null)).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // HAProxyController  -  HTTP Endpoints
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HAProxyController  -  HTTP Endpoints")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class HAProxyControllerTests {

        private MockMvc mockMvc;

        @Mock
        private HAProxyService haProxyService;

        @InjectMocks
        private HAProxyController haProxyController;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.standaloneSetup(haProxyController)
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("POST /register  -  202 Accepted")
        void testRegister_returns202Accepted() throws Exception {
            doNothing().when(haProxyService).registerComponent(any());

            mockMvc.perform(post("/register")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "compName":     "test-svc",
                                      "namespace":    "test-ns",
                                      "internalPort": 8080,
                                      "domain":       "test-svc.example.com",
                                      "parentFE":     "http_front",
                                      "lineIndex":    5
                                    }"""))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.message").value(containsString("test-svc")));
        }

        @Test
        @DisplayName("POST /register  -  EXTERNAL_IP requires serverAddress and serverPort")
        void testRegister_externalIpValidation() throws Exception {
            mockMvc.perform(post("/register")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "compName":     "dms",
                                      "backendType":  "EXTERNAL_IP",
                                      "domain":       "dms.example.com",
                                      "parentFE":     "http_front"
                                    }"""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /register  -  PATH_REWRITE requires pathPrefix")
        void testRegister_pathRewriteValidation() throws Exception {
            mockMvc.perform(post("/register")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "compName":     "gateway",
                                      "backendType":  "PATH_REWRITE",
                                      "namespace":    "api-ns",
                                      "internalPort": 8080,
                                      "domain":       "gw.example.com",
                                      "parentFE":     "http_front"
                                    }"""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /reload  -  returns 200 OK when reload succeeds")
        void testReload_success() throws Exception {
            when(haProxyService.triggerAndSyncReload(anyString())).thenReturn(true);

            mockMvc.perform(post("/reload"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.message").value(containsString("succeeded")));
        }

        @Test
        @DisplayName("POST /deregister  -  202 Accepted")
        void testDeregister_returns202Accepted() throws Exception {
            doNothing().when(haProxyService).deregisterComponent(any());

            mockMvc.perform(post("/deregister")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "compName": "test-svc",
                                      "parentFE": "http_front"
                                    }"""))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("POST /sync  -  returns 200 OK with report")
        void testSync_returns200() throws Exception {
            HAProxyService.ReconcileReport report = new HAProxyService.ReconcileReport();
            report.getMigratedBackends().add("manual-be");
            when(haProxyService.syncConfigWithModel()).thenReturn(report);

            mockMvc.perform(post("/sync"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.migratedBackends[0]").value("manual-be"));
        }

        @Test
        @DisplayName("GET /register-status/{compName}  -  returns status")
        void testRegisterStatus() throws Exception {
            when(haProxyService.getLastResult("test-svc")).thenReturn("SUCCESS");

            mockMvc.perform(get("/register-status/test-svc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.compName").value("test-svc"))
                    .andExpect(jsonPath("$.status").value("SUCCESS"));
        }

        @Test
        @DisplayName("GET /health  -  returns 200 OK UP")
        void testHealth() throws Exception {
            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("haproxy-integration"));
        }
    }

    // -------------------------------------------------------------------------
    // ApiResponse Factory Tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ApiResponse  -  Factory Methods")
    class ApiResponseTests {

        @Test
        void testAccepted() {
            ApiResponse<Void> response = ApiResponse.accepted("Registration triggered");
            assertThat(response.getStatus()).isEqualTo("ACCEPTED");
            assertThat(response.getMessage()).isEqualTo("Registration triggered");
            assertThat(response.getData()).isNull();
        }

        @Test
        void testSuccess() {
            ApiResponse<String> response = ApiResponse.success("Done", "payload");
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getMessage()).isEqualTo("Done");
            assertThat(response.getData()).isEqualTo("payload");
        }

        @Test
        void testError() {
            ApiResponse<Void> response = ApiResponse.error("Something went wrong");
            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getMessage()).isEqualTo("Something went wrong");
            assertThat(response.getData()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // OpenApiConfig Tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("OpenApiConfig  -  Swagger & OpenAPI Documentation Spec")
    class OpenApiConfigTests {

        @Test
        @DisplayName("customOpenAPI  -  provides expected title, version, tags, and servers")
        void testCustomOpenAPI() {
            com.nnp.haproxy.config.OpenApiConfig config = new com.nnp.haproxy.config.OpenApiConfig();
            io.swagger.v3.oas.models.OpenAPI openAPI = config.customOpenAPI();

            assertThat(openAPI).isNotNull();
            assertThat(openAPI.getInfo()).isNotNull();
            assertThat(openAPI.getInfo().getTitle()).isEqualTo("PICC-PC-Haproxy-Integration REST API");
            assertThat(openAPI.getInfo().getVersion()).isEqualTo("0.0.1-SNAPSHOT");
            assertThat(openAPI.getInfo().getLicense().getName()).isEqualTo("Apache License 2.0");
            assertThat(openAPI.getInfo().getDescription()).contains("DataPlane API");
            assertThat(openAPI.getServers()).isNotEmpty();
            assertThat(openAPI.getTags()).extracting(io.swagger.v3.oas.models.tags.Tag::getName)
                    .contains("HAProxy Integration", "Diagnostics & Health");
        }
    }
}

