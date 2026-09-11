package com.nnp.haproxy.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Describes the backend configuration pattern to generate in HAProxy.
 *
 * <ul>
 * <li>{@link #K8S_DNS} — resolves the upstream via in-cluster Kubernetes DNS
 * ({@code <compName>.<namespace>.svc.cluster.local}). Default for standard K8s
 * services.</li>
 * <li>{@link #EXTERNAL_IP} — routes to a raw IP or external hostname (NodePort
 * / external VM).
 * Requires {@code serverAddress} and {@code serverPort} in the registration
 * request.</li>
 * <li>{@link #SSL} — like K8S_DNS but connects to the upstream over TLS
 * ({@code ssl verify none}). Used for services like Keycloak, NiFi,
 * Passbolt.</li>
 * <li>{@link #PATH_REWRITE} — standard K8s DNS target that additionally strips
 * a path prefix
 * before forwarding (e.g. {@code /mgw → /}). Requires {@code pathPrefix}.</li>
 * <li>{@link #WEBSOCKET} — standard K8s DNS backend with an explicit
 * {@code timeout tunnel} to keep WebSocket / SSE connections alive beyond the
 * global default.
 * Requires {@code timeoutTunnel} (e.g. {@code "3600000"} for 1h).</li>
 * <li>{@link #TIME_CONFIGURABLE} — long-running backend (AI / ML or video
 * generation workloads) with
 * extended {@code timeout server} and {@code timeout tunnel}. Defaults to 600 s
 * when {@code timeoutServer}/{@code timeoutTunnel} are not explicitly set.</li>
 * <li>{@link #TCP} — raw TCP proxy ({@code mode tcp}). No ACL or switching rule
 * is
 * created. Reserved for SPOE-style integrations; prefer manual config.</li>
 * </ul>
 */
@Schema(
        description = "Supported backend architecture patterns in HAProxy",
        enumAsRef = true
)
public enum BackendType {

    /**
     * Default K8s DNS pattern:
     * {@code server <name> <compName>.<namespace>.svc.cluster.local:<port> check}
     */
    K8S_DNS,

    /**
     * External IP / NodePort pattern:
     * {@code server <name> <serverAddress>:<serverPort> check}
     */
    EXTERNAL_IP,

    /**
     * TLS-to-upstream pattern (K8s DNS address):
     * {@code server <name> <compName>.<namespace>.svc.cluster.local:<port> ssl verify none}
     */
    SSL,

    /**
     * Path-rewriting pattern (K8s DNS address + http-request replace-path on the
     * backend):
     * {@code http-request replace-path <pathPrefix>(/)?(.*) <pathReplacement>}
     */
    PATH_REWRITE,

    /**
     * WebSocket / streaming pattern (K8s DNS address + explicit tunnel timeout on
     * the backend).
     */
    WEBSOCKET,

    /**
     * Long-running AI/ML pattern (K8s DNS address + extended server + tunnel
     * timeouts).
     * Default timeout: 600 000 ms (600 s).
     */
    TIME_CONFIGURABLE,

    /**
     * Raw TCP proxy pattern ({@code mode tcp}, no ACL or switching rule).
     */
    TCP
}
