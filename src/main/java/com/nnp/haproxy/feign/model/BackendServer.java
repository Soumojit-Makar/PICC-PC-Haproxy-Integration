package com.nnp.haproxy.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackendServer {

	@JsonProperty("address")
	private String address;

	@JsonProperty("name")
	private String name;

	@JsonProperty("port")
	private Integer port;

	@JsonProperty("check")
	private String check;

	@JsonProperty("resolvers")
	private String resolvers;

	/**
	 * Set to {@code "enabled"} to connect to the upstream server over TLS.
	 * Used for {@link com.nnp.haproxy.model.BackendType#SSL} backends.
	 */
	@JsonProperty("ssl")
	private String ssl;

	/**
	 * SSL certificate verification mode. Set to {@code "none"} to accept
	 * self-signed certificates (equivalent to {@code ssl verify none} in haproxy.cfg).
	 */
	@JsonProperty("verify")
	private String verify;
}
