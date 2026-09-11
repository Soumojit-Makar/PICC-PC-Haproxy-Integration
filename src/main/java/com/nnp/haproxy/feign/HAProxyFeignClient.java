package com.nnp.haproxy.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.nnp.haproxy.config.HaproxyFeignConfig;
import com.nnp.haproxy.feign.model.ACL;
import com.nnp.haproxy.feign.model.BESwitchRule;
import com.nnp.haproxy.feign.model.Backend;
import com.nnp.haproxy.feign.model.BackendServer;
import com.nnp.haproxy.feign.model.Frontend;
import com.nnp.haproxy.feign.model.HTTPRequestRule;
import com.nnp.haproxy.feign.model.Reload;
import com.nnp.haproxy.feign.model.Transaction;

/**
 * Feign client for HAProxy DataPlane API v3.
 * The base URL is composed from {@code haproxy.feign.url} + {@code feign.url.api}
 * (e.g. {@code http://haproxy:5555} + {@code /v3/services/haproxy}).
 * Covers transaction management, backend/server creation, ACL and
 * backend-switching-rule management (create + delete + list).
 */
@FeignClient(
        name = "${haproxy.feign.name:haproxy-data-plane}",
        url  = "${haproxy.feign.url:http://localhost:5555}${feign.url.api:/v3/services/haproxy}",
        configuration = HaproxyFeignConfig.class
)
public interface HAProxyFeignClient {

    // ---------------------------------------------
    // Configuration Version
    // ---------------------------------------------

    /**
     * Returns the current HAProxy configuration version.
     * Must be passed when creating a transaction.
     */
    @GetMapping(value = "/configuration/version", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Integer> getVersion();

    // ---------------------------------------------
    // Raw Configuration  -  read/re-import the whole config file
    // ---------------------------------------------

    /**
     * Returns the raw haproxy.cfg content as text.
     * This endpoint always reads the latest on-disk file, so it also
     * contains records that were added manually (e.g. edited with nano).
     */
    @GetMapping(value = "/configuration/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> getRawConfig();

    /**
     * Replaces the whole configuration with the given raw text and writes it
     * to the config file. Used to re-import the on-disk file into the
     * DataPlane model before a transaction, so manually added records
     * survive the commit (which otherwise regenerates the file from the
     * stale model and drops them).
     *
     * @param skipReload if {@code true}, HAProxy is not reloaded after the
     *                   re-import (the content is identical  -  only the model
     *                   needs to be refreshed, the real reload happens at commit)
     */
    @PostMapping(value = "/configuration/raw", consumes = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> postRawConfig(
            @RequestParam(name = "version") int version,
            @RequestParam(name = "skip_reload", required = false) Boolean skipReload,
            @RequestBody String rawConfig
    );

    // ---------------------------------------------
    // Transactions
    // ---------------------------------------------

    /**
     * Creates a new transaction against the given config version.
     */
    @PostMapping(value = "/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Transaction> createTransaction(@RequestParam(name = "version") int version);

    /**
     * Commits an open transaction, applying all pending changes to HAProxy.
     * Passes {@code force_reload=true} to ensure DataPlane API immediately reloads
     * HAProxy and activates the newly registered routes without requiring manual restart.
     */
    @PutMapping(value = "/transactions/{transaction_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Transaction> commitTransaction(
            @PathVariable(name = "transaction_id") String trnId,
            @RequestParam(name = "force_reload", required = false) Boolean forceReload
    );

    default ResponseEntity<Transaction> commitTransaction(String trnId) {
        return commitTransaction(trnId, true);
    }

    /**
     * Deletes/discards an open transaction without applying its changes.
     * Used to clean up when a later step of a multi-step operation fails.
     */
    @DeleteMapping(value = "/transactions/{transaction_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteTransaction(@PathVariable(name = "transaction_id") String trnId);

    // ---------------------------------------------
    // Backends  -  Create & Delete
    // ---------------------------------------------

    /**
     * Returns all backend sections known to the DataPlane model.
     * Used by the reconcile step to detect backends that exist only in the
     * on-disk file (manually added) but not yet in the model.
     */
    @GetMapping(value = "/configuration/backends", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<Backend>> listBackends();

    /**
     * Returns all server entries of a backend as known to the DataPlane model.
     * Used by the reconcile step to detect manually added servers.
     */
    @GetMapping(value = "/configuration/backends/{backendName}/servers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<BackendServer>> listBEServers(@PathVariable String backendName);

    /**
     * Creates a new backend section in HAProxy config.
     */
    @PostMapping(value = "/configuration/backends", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Backend> addBE(
            @RequestParam(name = "transaction_id") String trnId,
            @RequestBody Backend backend
    );

    /**
     * Deletes an existing backend section from HAProxy config.
     */
    @DeleteMapping(value = "/configuration/backends/{backendName}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteBE(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable String backendName
    );

    /**
     * Returns a single backend section by name.
     * Used after commit to verify the backend was actually written to the config.
     */
    @GetMapping(value = "/configuration/backends/{backendName}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Backend> getBackend(@PathVariable String backendName);

    // ---------------------------------------------
    // Backend Servers  -  Create & Delete
    // ---------------------------------------------

    @PostMapping(value = "/runtime/backends/{parent_name}/servers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.Map<String, Object>> addRuntimeServer(
            @PathVariable(name = "parent_name") String parentBackend,
            @RequestBody java.util.Map<String, Object> server
    );

    @PutMapping(value = "/runtime/backends/{parent_name}/servers/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.Map<String, Object>> updateRuntimeServer(
            @PathVariable(name = "parent_name") String parentBackend,
            @PathVariable(name = "name") String serverName,
            @RequestBody java.util.Map<String, Object> server
    );

    @DeleteMapping(value = "/runtime/backends/{parent_name}/servers/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteRuntimeServer(
            @PathVariable(name = "parent_name") String parentBackend,
            @PathVariable(name = "name") String serverName
    );

    /**
     * Adds a server entry to an existing backend.
     */
    @PostMapping(value = "/configuration/backends/{backendName}/servers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BackendServer> addBEServer(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable String backendName,
            @RequestBody BackendServer server
    );

    /**
     * Removes a specific server from a backend.
     */
    @DeleteMapping(value = "/configuration/backends/{backendName}/servers/{serverName}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteBEServer(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable String backendName,
            @PathVariable String serverName
    );

    // ---------------------------------------------
    // Frontends  -  List (reconcile support)
    // ---------------------------------------------

    /**
     * Returns all frontend sections known to the DataPlane model.
     * Used by the reconcile step to report frontends that exist only in the
     * on-disk file and therefore cannot be kept by a transaction commit.
     */
    @GetMapping(value = "/configuration/frontends", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<Frontend>> listFrontends();

    // ---------------------------------------------
    // ACLs  -  List, Create & Delete
    // ---------------------------------------------

    /**
     * Lists all ACLs configured on a frontend.
     * Used during deregistration to find the ACL index by name.
     */
    @GetMapping(value = "/configuration/frontends/{parent_name}/acls", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<ACL>> listACLs(
            @PathVariable(name = "parent_name") String parentFrontEnd
    );

    /**
     * Creates an ACL entry on a frontend at a specific position index.
     */
    @PostMapping(value = "/configuration/frontends/{parent_name}/acls/{index}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ACL> createACL(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable(name = "parent_name") String parentFrontEnd,
            @PathVariable(name = "index") int position,
            @RequestBody ACL acl
    );

    /**
     * Deletes an ACL entry from a frontend by its index.
     */
    @DeleteMapping(value = "/configuration/frontends/{parent_name}/acls/{index}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteACL(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable(name = "parent_name") String parentFrontEnd,
            @PathVariable(name = "index") int index
    );

    /**
     * Lists all HTTP request rules on a frontend.
     * Used to locate existing deny rules so new routes are inserted
     * before them (deny rules are typically evaluated first).
     */
    @GetMapping(value = "/configuration/frontends/{parent_name}/http_request_rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<HTTPRequestRule>> listHTTPRequestRules(
            @PathVariable(name = "parent_name") String parentFrontEnd
    );

    // ---------------------------------------------
    // Backend HTTP Request Rules  -  for PATH_REWRITE type
    // ---------------------------------------------

    /**
     * Lists all HTTP request rules on a backend.
     * Used by the reconcile step and deregister to detect / remove path-rewrite rules.
     */
    @GetMapping(value = "/configuration/backends/{parent_name}/http_request_rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<HTTPRequestRule>> listBackendHTTPRequestRules(
            @PathVariable(name = "parent_name") String parentBackend
    );

    /**
     * Creates an HTTP request rule on a backend at a specific position index.
     * Used to add {@code http-request replace-path} rules for {@link com.nnp.haproxy.model.BackendType#PATH_REWRITE} backends.
     */
    @PostMapping(value = "/configuration/backends/{parent_name}/http_request_rules/{index}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HTTPRequestRule> createBackendHTTPRequestRule(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable(name = "parent_name") String parentBackend,
            @PathVariable(name = "index") int position,
            @RequestBody HTTPRequestRule rule
    );

    /**
     * Deletes an HTTP request rule from a backend by its index.
     * Used during deregistration of {@link com.nnp.haproxy.model.BackendType#PATH_REWRITE} backends.
     */
    @DeleteMapping(value = "/configuration/backends/{parent_name}/http_request_rules/{index}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteBackendHTTPRequestRule(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable(name = "parent_name") String parentBackend,
            @PathVariable(name = "index") int index
    );

    // ---------------------------------------------
    // Backend Switching Rules  -  List, Create & Delete
    // ---------------------------------------------

    /**
     * Lists all backend switching rules on a frontend.
     * Used during deregistration to find the rule index by backend name.
     */
    @GetMapping(value = "/configuration/frontends/{parent_name}/backend_switching_rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<BESwitchRule>> listBESwitchingRules(
            @PathVariable(name = "parent_name") String parentFrontEnd
    );

    /**
     * Creates a backend switching rule on a frontend at a specific position index.
     */
    @PostMapping(value = "/configuration/frontends/{parent_name}/backend_switching_rules/{index}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BESwitchRule> createBESwitchingRule(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable(name = "parent_name") String parentFrontEnd,
            @PathVariable(name = "index") int position,
            @RequestBody BESwitchRule rule
    );

    /**
     * Deletes a backend switching rule from a frontend by its index.
     */
    @DeleteMapping(value = "/configuration/frontends/{parent_name}/backend_switching_rules/{index}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteBESwitchingRule(
            @RequestParam(name = "transaction_id") String trnId,
            @PathVariable(name = "parent_name") String parentFrontEnd,
            @PathVariable(name = "index") int index
    );

    // ---------------------------------------------
    // Reloads
    // ---------------------------------------------

    /**
     * Lists recent HAProxy reload attempts with their status.
     * Used to detect that a commit triggered a successful reload.
     */
    @GetMapping(value = "/reloads", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.List<Reload>> getReloads();

    /**
     * Explicitly triggers an immediate graceful HAProxy reload via DataPlane API.
     */
    @PostMapping(value = "/reloads", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Reload> triggerReload();
}
