package com.nnp.haproxy.feign.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Wrapper for HAProxy DataPlane API GET /configuration/frontends/{parent_name}/backend_switching_rules response.
 * Supports both v2 ("data") and v3 ("value") field names.
 */
@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class BESwitchRuleList {

	@JsonProperty("data")
	@JsonAlias({"value", "data"})
	private List<BESwitchRule> data;

	@JsonProperty("_version")
	private int version;
}
