package com.nnp.haproxy.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Backend {

	@JsonProperty("mode")
	private String mode;

	@JsonProperty("name")
	private String name;

	@JsonProperty("default_server")
	private Map<String, String> defaultServer;

	/**
	 * Per-backend server timeout in <b>milliseconds</b>.
	 * When set, overrides the value inherited from the defaults section.
	 * Example: {@code 600000} for 600 s (AI/ML workloads).
	 */
	@JsonProperty("timeout_server")
	private Long timeoutServer;

	/**
	 * Per-backend tunnel timeout in <b>milliseconds</b>.
	 * Controls how long an idle WebSocket / streaming tunnel is kept open.
	 * Example: {@code 600000} for 600 s.
	 */
	@JsonProperty("timeout_tunnel")
	private Long timeoutTunnel;

	/**
	 * Per-backend connect timeout in <b>milliseconds</b>.
	 * Rarely overridden; inherits from defaults when not set.
	 */
	@JsonProperty("timeout_connect")
	private Long timeoutConnect;
}
