package com.nnp.haproxy.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Incoming registration / deregistration payload for the HAProxy integration service.
 *
 * <p>The {@link #backendType} field selects the backend configuration pattern. When not
 * supplied it defaults to {@link BackendType#K8S_DNS} (the original behaviour), so all
 * existing callers continue to work without modification.
 *
 * <h3>Required fields by type</h3>
 * <table border="1">
 *   <tr><th>BackendType</th><th>Required fields</th></tr>
 *   <tr><td>K8S_DNS (default)</td><td>compName, namespace, internalPort, domain, parentFE</td></tr>
 *   <tr><td>EXTERNAL_IP</td><td>compName, serverAddress, serverPort, domain, parentFE</td></tr>
 *   <tr><td>SSL</td><td>compName, namespace, internalPort, domain, parentFE</td></tr>
 *   <tr><td>PATH_REWRITE</td><td>compName, namespace, internalPort, domain, parentFE, pathPrefix</td></tr>
 *   <tr><td>WEBSOCKET</td><td>compName, namespace, internalPort, domain, parentFE</td></tr>
 *   <tr><td>TIME_CONFIGURABLE</td><td>compName, namespace, internalPort, domain, parentFE</td></tr>
 *   <tr><td>TCP</td><td>compName, namespace, internalPort, parentFE (no domain/ACL)</td></tr>
 * </table>
 */
@Getter
@Setter
@ToString
@Schema(description = "Payload for registering or deregistering a service/component in HAProxy")
public class HAProxyIn {

    // -- Core (required for ALL types) ------------------------------------------
    /** Service / component name. Used as backend name, server name and ACL prefix ({@code is_<compName>}). */
    @Schema(
            description = "Unique service or component name (used as backend name, server name, and ACL identifier)",
            example = "order-service",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String compName;

    /** HAProxy frontend that receives the ACL and switching rule (e.g. {@code http_front}). */
    @Schema(
            description = "Target HAProxy frontend name that receives the ACL and use_backend switching rule",
            example = "http_front",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String parentFE;

    /**
     * Backend configuration pattern. Defaults to {@link BackendType#K8S_DNS} when {@code null}.
     * Set explicitly to use a non-default pattern (EXTERNAL_IP, SSL, PATH_REWRITE, TIME_CONFIGURABLE, etc.).
     */
    @Schema(
            description = "Backend configuration pattern. Determines how the upstream server and routing rules are constructed. Defaults to K8S_DNS.",
            example = "K8S_DNS",
            defaultValue = "K8S_DNS"
    )
    private BackendType backendType;

    // -- K8S_DNS / SSL / PATH_REWRITE / WEBSOCKET / TIME_CONFIGURABLE (K8s targets) --------
    /**
     * Kubernetes namespace where the service runs.
     * Used to build the FQDN: {@code <compName>.<namespace>.svc.cluster.local}.
     * Not required for EXTERNAL_IP or TCP backends.
     */
    @Schema(
            description = "Kubernetes namespace where the target service is running. Required for K8S_DNS, SSL, PATH_REWRITE, WEBSOCKET, and TIME_CONFIGURABLE types.",
            example = "default"
    )
    private String namespace;

    /**
     * Port on the Kubernetes service (or internal port for K8s-based types).
     * Not required for EXTERNAL_IP (use {@link #serverPort} instead).
     */
    @Schema(
            description = "Internal service port on Kubernetes. Required for K8s-based backend types (1-65535).",
            example = "8080"
    )
    private int internalPort;

    // -- EXTERNAL_IP ------------------------------------------------------------
    /**
     * Raw IP address or external hostname for {@link BackendType#EXTERNAL_IP} backends.
     * Example: {@code 192.0.2.10} or {@code my-host.example.com}.
     */
    @Schema(
            description = "Raw IP address or external hostname for EXTERNAL_IP backend type.",
            example = "192.0.2.10"
    )
    private String serverAddress;

    /**
     * Port for the external server for {@link BackendType#EXTERNAL_IP} backends.
     * Example: {@code 9001} (NodePort) or {@code 3007}.
     */
    @Schema(
            description = "Port for the external server for EXTERNAL_IP backend type (1-65535).",
            example = "9001"
    )
    private int serverPort;

    // -- ACL ---------------------------------------------------------------------
    /**
     * Public domain used in the {@code hdr(host) -i} ACL.
     * Example: {@code my-service.example.com}.
     * Not used for {@link BackendType#TCP} (no ACL is created).
     */
    @Schema(
            description = "Public domain or hostname matched in the frontend ACL rule (hdr(host) -i <domain>). Required for HTTP-based backend types.",
            example = "order-service.example.com"
    )
    private String domain;

    /**
     * Requested insert index for the ACL and switching rule (0 = first position).
     * The service auto-adjusts this to ensure the rule lands before any existing deny rules.
     */
    @Schema(
            description = "Desired 0-based insert index for the ACL and use_backend rule. The service auto-positions it before any default/deny rules.",
            example = "0",
            defaultValue = "0"
    )
    private int lineIndex;

    // -- SSL ---------------------------------------------------------------------
    /**
     * When {@code true}, or when {@code backendType == SSL}, the upstream connection
     * uses TLS with {@code ssl verify none} (self-signed certificates are accepted).
     */
    @Schema(
            description = "When true (or when backendType is SSL), communicates with upstream backend over TLS using 'ssl verify none'.",
            example = "false",
            defaultValue = "false"
    )
    private boolean ssl;

    // -- PATH_REWRITE -------------------------------------------------------------
    /**
     * Path prefix to strip before forwarding. Required for {@link BackendType#PATH_REWRITE}.
     * Example: {@code /mgw} generates {@code http-request replace-path /mgw(/)?(.*) /\2}.
     */
    @Schema(
            description = "Path prefix to strip or match before forwarding upstream. Required when backendType is PATH_REWRITE.",
            example = "/mgw"
    )
    private String pathPrefix;

    /**
     * Replacement expression used in the generated {@code http-request replace-path} rule.
     * Defaults to {@code /\2} (strip prefix, keep the rest of the path).
     * Override for advanced rewrites, e.g. {@code /dms/\2} (strip prefix and prepend {@code /dms/}).
     */
    @Schema(
            description = "Replacement expression for http-request replace-path rule. Defaults to '/\\2' (strips prefix).",
            example = "/\\2",
            defaultValue = "/\\2"
    )
    private String pathReplacement;

    // -- WEBSOCKET / TIME_CONFIGURABLE ---------------------------------------------
    /**
     * Custom {@code timeout server} value in <b>milliseconds</b>.
     * Example: {@code 600000} for 600 s. Overrides the inherited default when set.
     * TIME_CONFIGURABLE type uses {@code 600000} ms when this field is omitted.
     */
    @Schema(
            description = "Custom server response timeout in milliseconds (e.g. 600000 for 10m). Used for long-running AI/ML tasks.",
            example = "600000"
    )
    private Long timeoutServer;

    /**
     * Custom {@code timeout tunnel} value in <b>milliseconds</b> for
     * WebSocket / streaming / long-running connections.
     * Example: {@code 600000} for 600 s.
     * TIME_CONFIGURABLE type uses {@code 600000} ms when this field is omitted.
     */
    @Schema(
            description = "Custom bidirectional tunnel timeout in milliseconds (e.g. 3600000 for 1h). Essential for WebSocket / SSE streaming connections.",
            example = "3600000"
    )
    private Long timeoutTunnel;

    // -- K8S_DNS with CoreDNS resolver --------------------------------------------
    /**
     * Name of the HAProxy {@code resolvers} section to attach for dynamic DNS resolution.
     * Example: {@code k8s_dns}. Optional; omit for static address resolution (most cases).
     */
    @Schema(
            description = "Name of the HAProxy resolvers section to attach for dynamic DNS resolution at runtime.",
            example = "k8s_dns"
    )
    private String resolvers;
}

