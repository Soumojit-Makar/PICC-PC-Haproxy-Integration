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
public class ACL {

	@JsonProperty("acl_name")
	private String acl_name;

	@JsonProperty("criterion")
	private String criterion;

	@JsonProperty("value")
	private String value;

	/**
	 * Position index of this ACL in the frontend config.
	 * Returned by HAProxy DataPlane API and used during deregistration
	 * to identify which ACL entry to delete.
	 */
	@JsonProperty("index")
	private Integer index;
}
