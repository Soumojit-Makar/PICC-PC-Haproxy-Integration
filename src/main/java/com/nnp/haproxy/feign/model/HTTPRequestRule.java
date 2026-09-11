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
public class HTTPRequestRule {

	@JsonProperty("index")
	private Integer index;

	@JsonProperty("type")
	private String type;

	@JsonProperty("deny_status")
	private Integer denyStatus;

	@JsonProperty("cond")
	private String cond;

	@JsonProperty("cond_test")
	private String condTest;

	/**
	 * Regex pattern used in an {@code http-request replace-path} rule.
	 * Example: {@code /mgw(/)?(.*)}
	 */
	@JsonProperty("replace_match")
	private String replaceMatch;

	/**
	 * Replacement expression for the matched path.
	 * Example: {@code /\\2} (strip the prefix and keep the rest).
	 */
	@JsonProperty("replace_value")
	private String replaceValue;
}
