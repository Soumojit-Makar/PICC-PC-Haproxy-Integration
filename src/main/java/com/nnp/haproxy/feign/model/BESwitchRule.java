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
public class BESwitchRule {

	@JsonProperty("cond")
	private String cond;

	@JsonProperty("cond_test")
	private String cond_test;

	@JsonProperty("name")
	private String name;

	@JsonProperty("index")
	private Integer index;
}
