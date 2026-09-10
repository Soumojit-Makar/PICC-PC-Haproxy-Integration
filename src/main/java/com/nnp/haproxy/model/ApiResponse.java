package com.nnp.haproxy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Generic API response wrapper returned by all HAProxy integration endpoints.
 *
 * @param <T> type of the optional data payload
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard API response envelope")
public class ApiResponse<T> {

	/** Outcome of the request: "SUCCESS", "ACCEPTED", "ERROR". */
	@Schema(
			description = "Status of the operation outcome",
			example = "SUCCESS",
			allowableValues = {"SUCCESS", "ACCEPTED", "ERROR"}
	)
	private String status;

	/** Human-readable message describing the outcome. */
	@Schema(
			description = "Descriptive message regarding the operation execution",
			example = "HAProxy registration triggered for component: order-service"
	)
	private String message;

	/** Optional data payload (null for async fire-and-forget responses). */
	@Schema(description = "Optional payload data returned by the operation")
	private T data;

	// ─── Convenience factory methods ────────────────────────────

	public static <T> ApiResponse<T> accepted(String message) {
		return new ApiResponse<>("ACCEPTED", message, null);
	}

	public static <T> ApiResponse<T> success(String message, T data) {
		return new ApiResponse<>("SUCCESS", message, data);
	}

	public static <T> ApiResponse<T> error(String message) {
		return new ApiResponse<>("ERROR", message, null);
	}
}
