package com.nnp.haproxy.exception;

import com.nnp.haproxy.model.ApiResponse;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for the HAProxy Integration service.
 * Catches common exceptions and returns consistent {@link ApiResponse} error payloads.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	/**
	 * Handles client disconnects / aborts gracefully (e.g. browser tab closed, network timeout during static resource download).
	 * No response body can or should be written to a closed connection.
	 */
	@ExceptionHandler({
			org.apache.catalina.connector.ClientAbortException.class,
			java.net.SocketTimeoutException.class
	})
	public void handleClientAbort(Exception ex) {
		log.debug("Client aborted or disconnected connection: {}", ex.getMessage());
	}

	/**
	 * Handles Feign exceptions thrown when the HAProxy DataPlane API is unreachable
	 * or returns an unexpected response.
	 */
	@ExceptionHandler(FeignException.class)
	public ResponseEntity<ApiResponse<Void>> handleFeignException(FeignException ex) {
		log.error("HAProxy DataPlane API call failed [HTTP {}]: {}", ex.status(), ex.getMessage(), ex);
		String message = String.format("HAProxy API error (HTTP %d): %s", ex.status(), ex.getMessage());
		return ResponseEntity
				.status(HttpStatus.BAD_GATEWAY)
				.contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error(message));
	}

	/**
	 * Handles bad/missing input in the request body.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
		log.warn("Invalid request argument: {}", ex.getMessage());
		return ResponseEntity
				.status(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error("Invalid request: " + ex.getMessage()));
	}

	/**
	 * Handles malformed JSON request bodies (e.g. missing commas or quotes).
	 */
	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
		log.warn("Malformed JSON payload received: {}", ex.getMessage());
		return ResponseEntity
				.status(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error("Malformed JSON payload: Please verify JSON syntax (check for missing comma, quote, or bracket)."));
	}

	/**
	 * Handles NullPointerException — typically from missing response bodies returned
	 * by the HAProxy DataPlane API Feign client.
	 */
	@ExceptionHandler(NullPointerException.class)
	public ResponseEntity<ApiResponse<Void>> handleNullPointer(NullPointerException ex) {
		log.error("Null pointer encountered (possible empty HAProxy API response): {}", ex.getMessage(), ex);
		return ResponseEntity
				.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error("Unexpected null response from HAProxy DataPlane API. Check HAProxy connectivity."));
	}

	/**
	 * Catch-all handler for any other unhandled runtime exceptions.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) throws Exception {
		if (ex instanceof org.springframework.web.servlet.resource.NoResourceFoundException) {
			throw ex;
		}

		// Check for broken pipe or client abort wrapped in other exceptions
		String msg = ex.getMessage() != null ? ex.getMessage() : "";
		if (ex.getClass().getName().contains("ClientAbortException")
				|| msg.contains("Broken pipe")
				|| msg.contains("Connection reset")
				|| ex.getCause() instanceof java.net.SocketTimeoutException
				|| (ex.getCause() != null && ex.getCause().getMessage() != null && ex.getCause().getMessage().contains("Broken pipe"))) {
			log.debug("Client connection aborted: {}", ex.getMessage());
			return null;
		}

		log.error("Unhandled exception in HAProxy Integration service: {}", ex.getMessage(), ex);
		return ResponseEntity
				.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error("Internal server error: " + ex.getMessage()));
	}
}
