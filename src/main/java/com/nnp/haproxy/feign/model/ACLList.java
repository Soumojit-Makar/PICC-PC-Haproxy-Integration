package com.nnp.haproxy.feign.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Wrapper for HAProxy DataPlane API GET /configuration/frontends/{parent_name}/acls response.
 * The response contains a list of ACL entries with pagination info.
 * Supports both v2 ("data") and v3 ("value") field names.
 */
@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ACLList {

	@JsonProperty("data")
	@JsonAlias({"value", "data"})
	private List<ACL> data;

	@JsonProperty("_version")
	private int version;
}
