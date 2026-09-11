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
public class Transaction {

	/**
	 * HAProxy DataPlane API returns "_version" (underscore prefix).
	 * Jackson needs @JsonProperty to bind it correctly.
	 */
	@JsonProperty("_version")
	private int version;

	@JsonProperty("id")
	private String id;

	@JsonProperty("status")
	private String status;

	/**
	 * The ID of the reload triggered by committing this transaction with
	 * {@code force_reload=true}. Present only on a committed transaction response;
	 * null when the transaction is still open or was committed without a reload.
	 */
	@JsonProperty("reload_id")
	private String reloadId;
}
