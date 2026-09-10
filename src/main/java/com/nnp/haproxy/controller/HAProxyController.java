package com.nnp.haproxy.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nnp.haproxy.model.ApiResponse;
import com.nnp.haproxy.model.BackendType;
import com.nnp.haproxy.model.HAProxyIn;
import com.nnp.haproxy.service.HAProxyConfigParser;
import com.nnp.haproxy.service.HAProxyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller exposing HAProxy DataPlane API integration endpoints.
 *
 * <p>All registration and deregistration calls are <em>asynchronous</em>  -  the
 * HTTP response is returned immediately (202 Accepted) and the actual HAProxy
 * update happens in the background with retry logic.
 */
@RestController
@Slf4j
@Tag(name = "HAProxy Integration", description = "Endpoints for registering, deregistering, syncing, and inspecting HAProxy configurations")
public class HAProxyController {

	@Autowired
	private HAProxyService proxyService;

	// -------------------------------------------------------------
	// Register
	// -------------------------------------------------------------

	/**
	 * Triggers async registration of a component in HAProxy.
	 *
	 * <p>Creates a Backend, BackendServer, ACL, and BESwitchingRule
	 * on the parent frontend within a single HAProxy transaction.
	 *
	 * @param haproxyIn registration details (component name, namespace, port, domain, frontend)
	 * @return 202 Accepted  -  operation runs asynchronously in background
	 */
	@PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Register a component in HAProxy",
			description = """
					Triggers asynchronous registration of a backend service in HAProxy using the DataPlane API.
					
					### What this operation performs:
					1. Validates the component's Pod status in Kubernetes (if in-cluster).
					2. Opens an atomic transaction on HAProxy DataPlane API.
					3. Creates a **Backend** matching the requested `backendType`.
					4. Adds upstream **BackendServer** (with check, SSL, or timeout settings).
					5. Inserts an **ACL** rule (`hdr(host) -i <domain>`) and **use_backend** switching rule.
					6. Commits the transaction and waits for HAProxy graceful reload to succeed.
					7. Automatically retries up to 3 times if transient transaction or reload errors occur.
					"""
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "202",
					description = "Registration successfully queued for asynchronous execution",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400",
					description = "Invalid payload or missing mandatory fields for the specified backend type",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "HAProxy DataPlane API or Kubernetes API communication failure",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			)
	})
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			description = "Registration payload specifying component routing and upstream parameters",
			required = true,
			content = @Content(
					schema = @Schema(implementation = HAProxyIn.class),
					examples = {
							@ExampleObject(
									name = "Standard K8s Service (K8S_DNS)",
									summary = "Standard Kubernetes in-cluster service",
									value = """
											{
											  "compName": "order-service",
											  "namespace": "default",
											  "internalPort": 8080,
											  "domain": "orders.example.com",
											  "parentFE": "http_front",
											  "backendType": "K8S_DNS"
											}
											"""
							),
							@ExampleObject(
									name = "External Host / NodePort (EXTERNAL_IP)",
									summary = "External server address & port",
									value = """
											{
											  "compName": "dms-service",
											  "serverAddress": "192.0.2.10",
											  "serverPort": 9001,
											  "domain": "dms.example.com",
											  "parentFE": "http_front",
											  "backendType": "EXTERNAL_IP"
											}
											"""
							),
							@ExampleObject(
									name = "TLS Upstream (SSL)",
									summary = "Upstream connecting over HTTPS/TLS",
									value = """
											{
											  "compName": "keycloak",
											  "namespace": "auth",
											  "internalPort": 8443,
											  "domain": "auth.example.com",
											  "parentFE": "http_front",
											  "backendType": "SSL",
											  "ssl": true
											}
											"""
							),
							@ExampleObject(
									name = "Path Rewriting (PATH_REWRITE)",
									summary = "Strips URL prefix before proxying upstream",
									value = """
											{
											  "compName": "gateway",
											  "namespace": "default",
											  "internalPort": 8080,
											  "domain": "api.example.com",
											  "parentFE": "http_front",
											  "backendType": "PATH_REWRITE",
											  "pathPrefix": "/mgw",
											  "pathReplacement": "/\\\\2"
											}
											"""
							),
							@ExampleObject(
									name = "WebSocket / Streaming (WEBSOCKET)",
									summary = "Extended tunnel timeout for WebSocket / SSE",
									value = """
											{
											  "compName": "nats-sse",
											  "namespace": "default",
											  "internalPort": 4222,
											  "domain": "stream.example.com",
											  "parentFE": "http_front",
											  "backendType": "WEBSOCKET",
											  "timeoutTunnel": 3600000
											}
											"""
							)
					}
			)
	)
	public ResponseEntity<ApiResponse<Void>> registerComponent(@RequestBody HAProxyIn haproxyIn) {
		validateRegister(haproxyIn);

		log.info("Received register request for component: {}", haproxyIn);
		proxyService.registerComponent(haproxyIn);
		return ResponseEntity
				.status(HttpStatus.ACCEPTED)
				.body(ApiResponse.accepted(
						"HAProxy registration triggered for component: " + haproxyIn
				));
	}

	// -------------------------------------------------------------
	// Deregister
	// -------------------------------------------------------------

	/**
	 * Triggers async deregistration of a component from HAProxy.
	 *
	 * <p>Removes the BESwitchingRule, ACL, BackendServer, and Backend
	 * for the given component within a single HAProxy transaction.
	 *
	 * @param haproxyIn deregistration details (component name, parent frontend)
	 * @return 202 Accepted  -  operation runs asynchronously in background
	 */
	@PostMapping(path = "/deregister", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Deregister a component from HAProxy",
			description = """
					Triggers asynchronous deregistration of a component from HAProxy.
					
					### What this operation performs:
					1. Opens an atomic transaction on HAProxy DataPlane API.
					2. Removes frontend `use_backend` switching rules and associated `hdr(host)` ACLs.
					3. Removes upstream `BackendServer` entries.
					4. Deletes the `Backend` definition.
					5. Commits transaction and waits for HAProxy graceful reload to succeed.
					"""
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "202",
					description = "Deregistration successfully queued for asynchronous execution",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400",
					description = "Invalid payload or missing mandatory compName/parentFE fields",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "HAProxy DataPlane API communication failure",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			)
	})
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			description = "Deregistration payload specifying the component name and frontend",
			required = true,
			content = @Content(
					schema = @Schema(implementation = HAProxyIn.class),
					examples = {
							@ExampleObject(
									name = "Deregister Component",
									value = """
											{
											  "compName": "order-service",
											  "parentFE": "http_front"
											}
											"""
							)
					}
			)
	)
	public ResponseEntity<ApiResponse<Void>> deregisterComponent(@RequestBody HAProxyIn haproxyIn) {
		validateDeregister(haproxyIn);
		log.info("Received deregister request for component: {}", haproxyIn.getCompName());
		proxyService.deregisterComponent(haproxyIn);
		return ResponseEntity
				.status(HttpStatus.ACCEPTED)
				.body(ApiResponse.accepted(
						"HAProxy deregistration triggered for component: " + haproxyIn.getCompName()
				));
	}

	// -------------------------------------------------------------
	// Sync (reconcile model with file)
	// -------------------------------------------------------------

	/**
	 * Reconciles the DataPlane model with the on-disk haproxy.cfg in a single
	 * transaction. Every record that exists in the file but is missing from the
	 * model (e.g. manual edits) is migrated into the model, so a later
	 * register/deregister commit no longer drops them.
	 *
	 * @return 200 OK with the reconcile report (migrated/skipped/unsupported)
	 */
	@PostMapping(path = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Reconcile DataPlane model with on-disk haproxy.cfg",
			description = """
					Synchronizes manual edits made directly on `haproxy.cfg` with the DataPlane API transactional model.
					
					Ensures that manually configured backends, servers, ACLs, and rules are preserved across \
					future dynamic registrations and deregistrations.
					"""
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Configuration successfully reconciled",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "HAProxy DataPlane API communication failure during reconciliation",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			)
	})
	public ResponseEntity<ApiResponse<HAProxyService.ReconcileReport>> syncConfig() {
		log.info("Received manual sync request  -  reconciling DataPlane model with haproxy.cfg...");
		HAProxyService.ReconcileReport report = proxyService.syncConfigWithModel();
		return ResponseEntity.ok(ApiResponse.success("Config reconciled with DataPlane model", report));
	}

	// -------------------------------------------------------------
	// Register Status
	// -------------------------------------------------------------

	/**
	 * Returns the last known result of the (async) registration/deregistration
	 * attempt for a component, e.g. {@code "SUCCESS"} or {@code "FAILED: ..."}.
	 *
	 * @param compName the component name used in the register/deregister call
	 * @return 200 OK with {@code { "compName": ..., "status": ... }}
	 */
	@GetMapping(path = "/register-status/{compName}", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Get last registration/deregistration status",
			description = "Queries the in-memory registry for the last recorded result of the asynchronous registration or deregistration operation for the specified component."
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Status returned successfully",
					content = @Content(
							schema = @Schema(example = "{\"compName\": \"order-service\", \"status\": \"SUCCESS\"}")
					)
			)
	})
	public ResponseEntity<Map<String, String>> registerStatus(
			@Parameter(description = "Component / Service name to check status for", example = "order-service", required = true)
			@PathVariable String compName) {
		return ResponseEntity.ok(Map.of(
				"compName", compName,
				"status", proxyService.getLastResult(compName)
		));
	}

	// -------------------------------------------------------------
	// Inventory
	// -------------------------------------------------------------

	/**
	 * Returns full parsed inventory of haproxy.cfg (backends, servers, ACLs, switching rules).
	 */
	@GetMapping(path = "/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Get full parsed HAProxy inventory",
			description = "Reads and parses the active HAProxy configuration from disk, returning an inventory of all backends, servers, ACLs, and switching rules."
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Full parsed inventory of HAProxy configuration",
					content = @Content(schema = @Schema(implementation = HAProxyConfigParser.ConfigInventory.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "Failed to fetch raw configuration from HAProxy DataPlane API",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			)
	})
	public ResponseEntity<com.nnp.haproxy.service.HAProxyConfigParser.ConfigInventory> getInventory() {
		return ResponseEntity.ok(proxyService.getInventory());
	}

	// -------------------------------------------------------------
	// Reload
	// -------------------------------------------------------------

	/**
	 * Explicitly triggers a HAProxy reload and waits for its completion.
	 *
	 * <p>Useful after manual edits to haproxy.cfg on the NFS volume, after a
	 * Runtime API fallback, or when a previous reload is known to have failed.
	 * The DataPlane API already reloads automatically on every committed transaction;
	 * this endpoint is for operator-driven or CI/CD-driven reloads.
	 *
	 * @return 200 OK when reload succeeded; 503 Service Unavailable when it failed
	 */
	@PostMapping(path = "/reload", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Trigger HAProxy reload and wait for completion",
			description = "Explicitly triggers a graceful reload of the HAProxy daemon via the DataPlane API and polls until reload reaches a terminal status ('succeeded' or 'failed')."
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "HAProxy reload confirmed: succeeded",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "503",
					description = "HAProxy reload reported failure (e.g. invalid syntax in manual edits)",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "HAProxy DataPlane API communication error",
					content = @Content(schema = @Schema(implementation = ApiResponse.class))
			)
	})
	public ResponseEntity<ApiResponse<Void>> triggerReload() {
		log.info("Received manual reload request.");
		boolean ok = proxyService.triggerAndSyncReload("ManualReload");
		if (ok) {
			return ResponseEntity.ok(ApiResponse.success("HAProxy reload confirmed: succeeded", null));
		} else {
			return ResponseEntity
					.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
					.body(ApiResponse.accepted("HAProxy reload reported failure  -  check HAProxy logs"));
		}
	}

	// ---------------------------------------------------------------
	// Health
	// ---------------------------------------------------------------

	/**
	 * Simple liveness health check endpoint.
	 *
	 * @return 200 OK with {@code { "status": "UP" }}
	 */
	@GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary     = "Service health check",
			description = "Returns current application liveness status. Used by Kubernetes probes and load balancer health checks.",
			tags        = {"Diagnostics & Health"}
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Service is healthy and ready to receive traffic",
					content = @Content(
							schema = @Schema(example = "{\"status\": \"UP\", \"service\": \"haproxy-integration\"}")
					)
			)
	})
	public ResponseEntity<Map<String, String>> health() {
		return ResponseEntity.ok(Map.of("status", "UP", "service", "haproxy-integration"));
	}

	// -------------------------------------------------------------
	// Validation
	// -------------------------------------------------------------

	private void validateRegister(HAProxyIn in) {
		if (in == null) {
			throw new IllegalArgumentException("request body is required");
		}
		if (in.getCompName() == null || in.getCompName().isBlank()) {
			throw new IllegalArgumentException("compName is required");
		}
		if (in.getParentFE() == null || in.getParentFE().isBlank()) {
			throw new IllegalArgumentException("parentFE is required");
		}

		BackendType type = in.getBackendType() != null ? in.getBackendType() : BackendType.K8S_DNS;

		switch (type) {
			case EXTERNAL_IP -> {
				if (in.getServerAddress() == null || in.getServerAddress().isBlank()) {
					throw new IllegalArgumentException("serverAddress is required for EXTERNAL_IP backend type");
				}
				if (in.getServerPort() <= 0 || in.getServerPort() > 65535) {
					throw new IllegalArgumentException("serverPort must be between 1 and 65535 for EXTERNAL_IP backend type");
				}
				if (in.getDomain() == null || in.getDomain().isBlank()) {
					throw new IllegalArgumentException("domain is required");
				}
			}
			case PATH_REWRITE -> {
				requireK8sFields(in);
				if (in.getPathPrefix() == null || in.getPathPrefix().isBlank()) {
					throw new IllegalArgumentException("pathPrefix is required for PATH_REWRITE backend type");
				}
				if (in.getDomain() == null || in.getDomain().isBlank()) {
					throw new IllegalArgumentException("domain is required");
				}
			}
			case TCP -> {
				// TCP: no domain / ACL required
				requireK8sFields(in);
			}
			default -> {
				// K8S_DNS, SSL, WEBSOCKET, AI_ML
				requireK8sFields(in);
				if (in.getDomain() == null || in.getDomain().isBlank()) {
					throw new IllegalArgumentException("domain is required");
				}
			}
		}
	}

	private void requireK8sFields(HAProxyIn in) {
		if (in.getNamespace() == null || in.getNamespace().isBlank()) {
			String typeName = in.getBackendType() != null ? in.getBackendType().name() : "K8S_DNS";
			throw new IllegalArgumentException("namespace is required for " + typeName + " backend type");
		}
		if (in.getInternalPort() <= 0 || in.getInternalPort() > 65535) {
			throw new IllegalArgumentException("internalPort must be between 1 and 65535");
		}
	}

	private void validateDeregister(HAProxyIn in) {
		if (in == null) {
			throw new IllegalArgumentException("request body is required");
		}
		if (in.getCompName() == null || in.getCompName().isBlank()) {
			throw new IllegalArgumentException("compName is required");
		}
		if (in.getParentFE() == null || in.getParentFE().isBlank()) {
			throw new IllegalArgumentException("parentFE is required");
		}
	}
}
