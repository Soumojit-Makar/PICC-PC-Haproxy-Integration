package com.nnp.haproxy.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.swagger.v3.oas.annotations.media.Schema;
import com.nnp.haproxy.feign.HAProxyFeignClient;
import com.nnp.haproxy.feign.model.ACL;
import com.nnp.haproxy.feign.model.BESwitchRule;
import com.nnp.haproxy.feign.model.Backend;
import com.nnp.haproxy.feign.model.BackendServer;
import com.nnp.haproxy.feign.model.Frontend;
import com.nnp.haproxy.feign.model.HTTPRequestRule;
import com.nnp.haproxy.feign.model.Reload;
import com.nnp.haproxy.feign.model.Transaction;
import com.nnp.haproxy.model.BackendType;
import com.nnp.haproxy.model.HAProxyIn;
import com.nnp.haproxy.service.HAProxyConfigParser.AclRecord;
import com.nnp.haproxy.service.HAProxyConfigParser.BackendRecord;
import com.nnp.haproxy.service.HAProxyConfigParser.ConfigInventory;
import com.nnp.haproxy.service.HAProxyConfigParser.ServerRecord;
import com.nnp.haproxy.service.HAProxyConfigParser.SwitchingRuleRecord;

import feign.FeignException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for all HAProxy DataPlane API operations.
 *
 * <p>
 * Registration flow (async):
 * <ol>
 * <li>Get current config version</li>
 * <li>Open a transaction</li>
 * <li>Add Backend + BackendServer</li>
 * <li>Resolve insert index (never after an existing deny rule)</li>
 * <li>Add ACL on the parent frontend</li>
 * <li>Add BackendSwitchingRule on the parent frontend</li>
 * <li>Commit the transaction</li>
 * <li>Verify the changes are readable from the active config</li>
 * </ol>
 *
 * <p>
 * Deregistration flow (async) — reverse of registration:
 * <ol>
 * <li>Get current config version</li>
 * <li>Open a transaction</li>
 * <li>Find and delete the BESwitchingRule by backend name</li>
 * <li>Find and delete the ACL by acl_name</li>
 * <li>Delete the BackendServer</li>
 * <li>Delete the Backend</li>
 * <li>Commit the transaction</li>
 * </ol>
 */
@Service
@Slf4j
public class HAProxyService {

	@Autowired
	private HAProxyFeignClient proxyClient;

	@Autowired
	private K8SService k8sService;

	@org.springframework.beans.factory.annotation.Value("${haproxy.k8s.strict-mode:false}")
	private boolean k8sStrictMode;

	/** Number of retry attempts if HAProxy API call fails. */
	private static final int RETRY_COUNT = 3;

	/** Delay in milliseconds between retry attempts (1 minute). */
	private static final long RETRY_INTERVAL_MS = 60 * 1000L;

	/**
	 * Maximum time in milliseconds to wait for a HAProxy reload to complete after a
	 * commit.
	 */
	private static final long RELOAD_WAIT_MS = 30_000L;

	/** Poll interval in milliseconds while waiting for a HAProxy reload. */
	private static final long RELOAD_POLL_MS = 1_000L;

	/**
	 * Default timeout (ms) applied to AI_ML backends when no explicit value is
	 * given (600 s).
	 */
	private static final long AI_ML_DEFAULT_TIMEOUT_MS = 600_000L;

	/** Last known result per component: "SUCCESS" or "FAILED: <reason>". */
	private final ConcurrentHashMap<String, String> lastResults = new ConcurrentHashMap<>();

	/**
	 * Serializes all config-modifying operations (register, deregister, manual
	 * sync) so two concurrent flows cannot race on the same transaction and
	 * overwrite each other's changes.
	 */
	private final ReentrantLock configLock = new ReentrantLock(true);

	/**
	 * Returns the last recorded result for the component,
	 * or {@code "UNKNOWN"} if no registration attempt has been recorded yet.
	 */
	public String getLastResult(String compName) {
		return lastResults.getOrDefault(compName, "UNKNOWN");
	}

	// ─────────────────────────────────────────────────────────────
	// Register
	// ─────────────────────────────────────────────────────────────

	/**
	 * Asynchronously registers a component in HAProxy.
	 * Retries up to {@value #RETRY_COUNT} times on failure,
	 * waiting {@value #RETRY_INTERVAL_MS} ms between attempts.
	 *
	 * @param proxyIn the incoming registration request
	 */
	@Async
	public void registerComponent(HAProxyIn proxyIn) {
		// for (HAProxyIn proxyIn : proxyInList) {
		String compName = proxyIn.getCompName();
		log.info("Starting HAProxy registration for component: {}", compName);

		// 1. Verify Kubernetes service/pod is running before registration
		// Skip this check for non-K8s backend types (EXTERNAL_IP, TCP) that have
		// no K8s namespace to validate against.
		BackendType type = effectiveType(proxyIn);
		if (type != BackendType.EXTERNAL_IP && type != BackendType.TCP) {
			try {
				boolean isK8sRunning = k8sService.checkCompStatus(proxyIn.getNamespace(), compName);
				log.info("[K8s Check] Pod status for component '{}' in namespace '{}': running={}",
						compName, proxyIn.getNamespace(), isK8sRunning);

				if (!isK8sRunning && k8sStrictMode) {
					String errorMsg = "Kubernetes component '" + compName + "' in namespace '"
							+ proxyIn.getNamespace()
							+ "' is NOT running (pod status != Running). Registration aborted.";
					log.error("[K8s Check Failed] {}", errorMsg);
					lastResults.put(compName, "FAILED: " + errorMsg);
					return;
				}
			} catch (Exception k8sEx) {
				log.warn("[K8s Check] Could not verify pod status via K8s API ({})", k8sEx.getMessage());
				if (k8sStrictMode) {
					String errorMsg = "Kubernetes API check failed for '" + compName + "': " + k8sEx.getMessage();
					log.error("[K8s Check Failed] {}", errorMsg);
					lastResults.put(compName, "FAILED: " + errorMsg);
					return;
				}
			}
		} // end K8s type check

		boolean success = false;
		int attempt = 0;
		String lastError = null;

		while (!success && attempt < RETRY_COUNT) {
			attempt++;
			try {
				log.info("[Register] Attempt {}/{} for component: {}", attempt, RETRY_COUNT, compName);
				success = addToHAProxy(proxyIn);
				if (success) {
					log.info("[Register] Successfully registered component '{}' in HAProxy on attempt {}.",
							compName, attempt);
				} else {
					log.error("[Register] Verification failed for '{}' — transaction was committed, not retrying.",
							compName);
					break;
				}
			} catch (Exception e) {
				lastError = e.getMessage();
				log.error("[Register] Attempt {}/{} failed for component '{}': {}",
						attempt, RETRY_COUNT, compName, lastError, e);

				if (attempt < RETRY_COUNT) {
					log.info("[Register] Waiting {}ms before retry...", RETRY_INTERVAL_MS);
					try {
						Thread.sleep(RETRY_INTERVAL_MS);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						log.warn("[Register] Retry sleep interrupted for component '{}'", compName);
						break;
					}
				}
			}
		}

		lastResults.put(compName, success ? "SUCCESS"
				: "FAILED: all " + RETRY_COUNT + " attempts failed. Last error: " + lastError);

		if (!success) {
			log.error("[Register] All {} attempts failed for component '{}'. HAProxy registration incomplete.",
					RETRY_COUNT, compName);
		}
		// }
	}

	// ─────────────────────────────────────────────────────────────
	// Deregister
	// ─────────────────────────────────────────────────────────────

	/**
	 * Asynchronously deregisters a component from HAProxy.
	 * Retries up to {@value #RETRY_COUNT} times on failure.
	 *
	 * @param proxyIn the deregistration request — must have compName, parentFE
	 */
	@Async
	public void deregisterComponent(HAProxyIn proxyIn) {
		String compName = proxyIn.getCompName();
		log.info("Starting HAProxy deregistration for component: {}", compName);

		boolean success = false;
		int attempt = 0;
		String lastError = null;

		while (!success && attempt < RETRY_COUNT) {
			attempt++;
			try {
				log.info("[Deregister] Attempt {}/{} for component: {}", attempt, RETRY_COUNT, compName);
				success = removeFromHAProxy(proxyIn);
				if (success) {
					log.info("[Deregister] Successfully deregistered component '{}' from HAProxy on attempt {}.",
							compName, attempt);
				} else {
					log.error("[Deregister] Flow failed for '{}' — transaction discarded, not retrying.",
							compName);
					break;
				}
			} catch (Exception e) {
				lastError = e.getMessage();
				log.error("[Deregister] Attempt {}/{} failed for component '{}': {}",
						attempt, RETRY_COUNT, compName, lastError, e);

				if (attempt < RETRY_COUNT) {
					log.info("[Deregister] Waiting {}ms before retry...", RETRY_INTERVAL_MS);
					try {
						Thread.sleep(RETRY_INTERVAL_MS);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						log.warn("[Deregister] Retry sleep interrupted for component '{}'", compName);
						break;
					}
				}
			}
		}

		lastResults.put(compName, success ? "SUCCESS"
				: "FAILED: all " + RETRY_COUNT + " attempts failed. Last error: " + lastError);

		if (!success) {
			log.error("[Deregister] All {} attempts failed for component '{}'. HAProxy deregistration incomplete.",
					RETRY_COUNT, compName);
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Private: addToHAProxy
	// ─────────────────────────────────────────────────────────────

	/**
	 * Registers a component in HAProxy and writes it to the actual config file.
	 * <p>
	 * Supports all {@link BackendType} patterns:
	 * K8S_DNS, EXTERNAL_IP, SSL, PATH_REWRITE, WEBSOCKET, AI_ML, TCP.
	 * <p>
	 * Before the transaction, the current haproxy.cfg is re-imported into the
	 * DataPlane model so records added manually are preserved — the commit only
	 * ADDS
	 * the new backend and never removes existing records. On any step failure the
	 * open
	 * transaction is discarded and the exception is rethrown for the retry loop.
	 *
	 * @return true if the transaction committed successfully, the reload completed,
	 *         and the new entries were verified in the active config
	 */
	private boolean addToHAProxy(HAProxyIn proxyIn) {
		configLock.lock();
		try {
			return addToHAProxyLocked(proxyIn);
		} finally {
			configLock.unlock();
		}
	}

	private boolean addToHAProxyLocked(HAProxyIn proxyIn) {
		BackendType type = effectiveType(proxyIn);
		log.info("[Register] Backend type resolved to: {}", type);

		// 1. Re-import the actual haproxy.cfg into the DataPlane model.
		boolean synced = syncConfigFromFile(REGISTER_PREFIX);

		String txnId = null;
		try {
			// 2. Get current config version
			int version = call(REGISTER_PREFIX, "get configuration version",
					() -> proxyClient.getVersion().getBody());
			log.debug("[Register] Current HAProxy config version: {}", version);

			// 3. Open transaction
			Transaction txn = call(REGISTER_PREFIX, "create transaction",
					() -> proxyClient.createTransaction(version).getBody());
			final String trn = txn.getId();
			txnId = trn;
			log.debug("[Register] Transaction created: {}", txnId);

			// 3b. Reconcile: migrate manually-added records if raw re-import was not successful.
			if (!synced) {
				reconcileConfigWithModel(trn, REGISTER_PREFIX);
			}

			// 4. Build and create Backend
			Backend backendPayload = buildBackend(proxyIn, type);
			Backend be = call(REGISTER_PREFIX, "create backend '" + proxyIn.getCompName() + "'",
					() -> proxyClient.addBE(trn, backendPayload).getBody());
			log.debug("[Register] Backend created: {} (mode={}, timeoutServer={}, timeoutTunnel={})",
					be.getName(), be.getMode(), be.getTimeoutServer(), be.getTimeoutTunnel());

			// 5. Build and create BackendServer
			BackendServer serverPayload = buildBackendServer(proxyIn, type);
			BackendServer beServer = call(REGISTER_PREFIX, "create server '" + serverPayload.getName() + "'",
					() -> proxyClient.addBEServer(trn, be.getName(), serverPayload).getBody());
			log.debug("[Register] BackendServer created: {} -> {}:{}",
					beServer.getName(), beServer.getAddress(), beServer.getPort());

			// 5b. PATH_REWRITE: add http-request replace-path rule to the backend
			if (type == BackendType.PATH_REWRITE) {
				HTTPRequestRule pathRule = buildPathRewriteRule(proxyIn);
				call(REGISTER_PREFIX, "create path-rewrite rule on backend '" + be.getName() + "'",
						() -> proxyClient.createBackendHTTPRequestRule(trn, be.getName(), 0, pathRule).getBody());
				log.debug("[Register] PATH_REWRITE rule created on backend '{}': {} -> {}",
						be.getName(), pathRule.getReplaceMatch(), pathRule.getReplaceValue());
			}

			// TCP backends have no ACL or switching rule — commit and return
			if (type == BackendType.TCP) {
				Transaction tcpCommitted = proxyClient.commitTransaction(trn, true).getBody();
				txnId = null;
				String tcpReloadId = (tcpCommitted != null) ? tcpCommitted.getReloadId() : null;
				log.info("[Register] TCP backend '{}' committed with force_reload=true. Reload ID: {}", proxyIn.getCompName(), tcpReloadId);
				waitForReload(REGISTER_PREFIX, tcpReloadId);
				return true;
			}

			// 6. Determine insert index — never place a route after an existing deny rule
			int insertIndex = resolveInsertIndex(proxyIn.getParentFE(), proxyIn.getLineIndex());
			log.debug("[Register] Resolved insert index {} for frontend '{}' (requested {})",
					insertIndex, proxyIn.getParentFE(), proxyIn.getLineIndex());

			// 7. Create ACL on parent frontend
			ACL aclPayload = new ACL();
			aclPayload.setAcl_name("is_" + proxyIn.getCompName());
			aclPayload.setCriterion("hdr(host)");
			aclPayload.setValue("-i " + proxyIn.getDomain());
			ACL acl = call(REGISTER_PREFIX, "create ACL 'is_" + proxyIn.getCompName() + "'",
					() -> proxyClient.createACL(trn, proxyIn.getParentFE(), insertIndex, aclPayload).getBody());
			log.debug("[Register] ACL created: {} at index {}", acl.getAcl_name(), insertIndex);

			// 8. Create BackendSwitchingRule on parent frontend
			BESwitchRule rulePayload = new BESwitchRule();
			rulePayload.setName(proxyIn.getCompName());
			rulePayload.setCond_test(acl.getAcl_name());
			rulePayload.setCond("if");
			BESwitchRule beSwRule = call(REGISTER_PREFIX,
					"create backend switching rule '" + rulePayload.getName() + "'",
					() -> proxyClient.createBESwitchingRule(trn, proxyIn.getParentFE(), insertIndex, rulePayload)
							.getBody());
			log.debug("[Register] BESwitchingRule created: {} -> if {}", beSwRule.getName(), beSwRule.getCond_test());

			// 9. Commit — DataPlane API triggers a graceful HAProxy reload automatically
			Transaction committed = proxyClient.commitTransaction(trn, true).getBody();
			txnId = null;
			String reloadId = (committed != null) ? committed.getReloadId() : null;
			log.info("[Register] Transaction {} committed with force_reload=true. Reload ID: {}", trn, reloadId);

			// 10. Wait for the specific reload to confirm
			boolean reloaded = waitForReload(REGISTER_PREFIX, reloadId);
			if (!reloaded) {
				log.warn("[Register] Reload status unconfirmed for '{}' — route will be active on next reload.",
						proxyIn.getCompName());
			}

			// 11. Verify changes are readable from the active config
			if (!verifyRegistration(proxyIn, acl.getAcl_name())) {
				log.error("[Register] Post-commit verification FAILED for '{}' — committed but not readable back.",
						proxyIn.getCompName());
				return false;
			}

			return true;

		} catch (Exception ex) {
			discardTransaction(txnId);
			throw ex;
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Private: removeFromHAProxy
	// ─────────────────────────────────────────────────────────────

	/**
	 * Executes the full deregister flow within a single HAProxy transaction.
	 * Steps: find rule/ACL indexes → delete rule → delete ACL → delete server →
	 * delete backend → commit.
	 * Server/backend deletes are best effort (404 = never existed, still commits
	 * the rest).
	 *
	 * @return true if the transaction committed successfully
	 */
	private boolean removeFromHAProxy(HAProxyIn proxyIn) {
		configLock.lock();
		try {
			return removeFromHAProxyLocked(proxyIn);
		} finally {
			configLock.unlock();
		}
	}

	private boolean removeFromHAProxyLocked(HAProxyIn proxyIn) {
		String txnId = null;
		try {
			// 0. Re-import the actual haproxy.cfg so the model matches the file
			// before deletions, then open a transaction on the fresh version.
			boolean synced = syncConfigFromFile(DEREGISTER_PREFIX);

			// 1. Get current config version
			int version = call(DEREGISTER_PREFIX, "get configuration version",
					() -> proxyClient.getVersion().getBody());
			log.debug("[Deregister] Current HAProxy config version: {}", version);

			// 3. Open transaction
			Transaction txn = call(DEREGISTER_PREFIX, "create transaction",
					() -> proxyClient.createTransaction(version).getBody());
			final String trn = txn.getId();
			txnId = trn;
			log.debug("[Deregister] Transaction created: {}", txnId);

			// 3b. Reconcile if raw re-import was not successful
			if (!synced) {
				reconcileConfigWithModel(trn, DEREGISTER_PREFIX);
			}

			// 3. Find and delete BESwitchingRule by backend name
			List<BESwitchRule> ruleList = proxyClient.listBESwitchingRules(proxyIn.getParentFE()).getBody();
			if (ruleList != null) {
				Optional<BESwitchRule> matchedRule = ruleList.stream()
						.filter(r -> proxyIn.getCompName().equalsIgnoreCase(r.getName()))
						.findFirst();

				if (matchedRule.isPresent()) {
					// The list response does not include an "index" field, so the
					// rule's position in the ordered list IS its index.
					proxyClient.deleteBESwitchingRule(trn, proxyIn.getParentFE(), ruleList.indexOf(matchedRule.get()));
					log.debug("[Deregister] BESwitchingRule '{}' deleted at index {}",
							proxyIn.getCompName(), ruleList.indexOf(matchedRule.get()));
				} else {
					log.warn("[Deregister] BESwitchingRule for '{}' not found on frontend '{}'. Skipping.",
							proxyIn.getCompName(), proxyIn.getParentFE());
				}
			}

			// 4. Find and delete ACL by name
			String expectedAclName = "is_" + proxyIn.getCompName();
			List<ACL> aclList = proxyClient.listACLs(proxyIn.getParentFE()).getBody();
			if (aclList != null) {
				Optional<ACL> matchedAcl = aclList.stream()
						.filter(a -> expectedAclName.equalsIgnoreCase(a.getAcl_name()))
						.findFirst();

				if (matchedAcl.isPresent()) {
					proxyClient.deleteACL(trn, proxyIn.getParentFE(), aclList.indexOf(matchedAcl.get()));
					log.debug("[Deregister] ACL '{}' deleted at index {}", expectedAclName,
							aclList.indexOf(matchedAcl.get()));
				} else {
					log.warn("[Deregister] ACL '{}' not found on frontend '{}'. Skipping.",
							expectedAclName, proxyIn.getParentFE());
				}
			}

			// 5. Delete BackendServer (best effort — 404 means it never existed)
			try {
				proxyClient.deleteBEServer(trn, proxyIn.getCompName(), proxyIn.getCompName());
				log.debug("[Deregister] BackendServer '{}' deleted from backend '{}'",
						proxyIn.getCompName(), proxyIn.getCompName());
			} catch (FeignException e) {
				if (e.status() != 404) {
					throw e;
				}
				log.warn("[Deregister] BackendServer '{}' not found in backend '{}'. Skipping.",
						proxyIn.getCompName(), proxyIn.getCompName());
			}

			// 6. Delete Backend (best effort — 404 means it never existed)
			try {
				proxyClient.deleteBE(trn, proxyIn.getCompName());
				log.debug("[Deregister] Backend '{}' deleted", proxyIn.getCompName());
			} catch (FeignException e) {
				if (e.status() != 404) {
					throw e;
				}
				log.warn("[Deregister] Backend '{}' not found. Skipping.", proxyIn.getCompName());
			}

			// 7. Commit transaction — triggers graceful HAProxy reload automatically
			Transaction deregCommitted = proxyClient.commitTransaction(trn, true).getBody();
			txnId = null;
			String deregReloadId = (deregCommitted != null) ? deregCommitted.getReloadId() : null;
			log.info("[Deregister] Transaction {} committed with force_reload=true. Reload ID: {}", trn, deregReloadId);

			// 8. Wait for the specific reload to confirm
			boolean reloaded = waitForReload(DEREGISTER_PREFIX, deregReloadId);
			if (!reloaded) {
				log.warn("[Deregister] Reload status unconfirmed for '{}' — route removal active on next reload.",
						proxyIn.getCompName());
			}
			return true;

		} catch (Exception ex) {
			discardTransaction(txnId);
			log.error("[Deregister] Flow failed ({}), transaction discarded — nothing was removed.", ex.getMessage());
			return false;
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Private: helpers
	// ─────────────────────────────────────────────────────────────

	private static final String REGISTER_PREFIX = "Register";
	private static final String DEREGISTER_PREFIX = "Deregister";

	/**
	 * Forces the DataPlane API to re-read the actual haproxy.cfg from disk into
	 * its managed model. {@code GET /configuration/raw} always returns the latest
	 * on-disk content (including manual nano edits); posting it back makes the
	 * model match the file. Without this, a transaction commit regenerates the
	 * file from the stale model and silently drops manually added records.
	 * <p>
	 * Best effort: a failure here only means the model may be stale; the
	 * reconcile step migrates whatever records the parse still finds.
	 */
	private boolean syncConfigFromFile(String prefix) {
		try {
			int version = call(prefix, "get configuration version",
					() -> proxyClient.getVersion().getBody());
			String rawConfig = call(prefix, "read raw configuration from file",
					() -> proxyClient.getRawConfig().getBody());
			call(prefix, "re-import configuration into DataPlane model",
					() -> proxyClient.postRawConfig(version, true, rawConfig));
			log.info("[{}] Re-imported haproxy.cfg (version {}) into the DataPlane model — existing records preserved.",
					prefix, version);
			return true;
		} catch (Exception e) {
			log.warn("[{}] Raw config re-import failed ({}), continuing with reconcile against on-disk content.",
					prefix, e.getMessage());
			return false;
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Public: manual sync (POST /sync)
	// ─────────────────────────────────────────────────────────────

	/**
	 * Reconciles the DataPlane model with the on-disk haproxy.cfg in a single
	 * transaction: syncs the raw file, parses it, and migrates every record that
	 * exists on disk but is missing from the model. Useful for an operator-driven
	 * catch-up after manual edits.
	 *
	 * @return a {@link ReconcileReport} describing what was migrated/skipped
	 */
	public ConfigInventory getInventory() {
		try {
			String rawConfig = proxyClient.getRawConfig().getBody();
			if (rawConfig == null || rawConfig.isBlank()) {
				return new ConfigInventory();
			}
			return HAProxyConfigParser.parse(rawConfig);
		} catch (Exception e) {
			log.error("Failed to fetch/parse HAProxy inventory: {}", e.getMessage());
			return new ConfigInventory();
		}
	}

	public ReconcileReport syncConfigWithModel() {
		configLock.lock();
		try {
			boolean synced = syncConfigFromFile("Sync");
			if (synced) {
				log.info("[Sync] DataPlane model re-imported successfully from haproxy.cfg.");
				return new ReconcileReport();
			}

			int version = call("Sync", "get configuration version",
					() -> proxyClient.getVersion().getBody());
			Transaction txn = call("Sync", "create transaction",
					() -> proxyClient.createTransaction(version).getBody());
			String txnId = txn.getId();
			try {
				ReconcileReport report = reconcileConfigWithModel(txnId, "Sync");
				if (!report.isClean()) {
					proxyClient.commitTransaction(txnId, true);
					log.info("[Sync] Transaction {} committed with force_reload=true — model reconciled with file.", txnId);
				} else {
					discardTransaction(txnId);
					log.info("[Sync] Model already in sync — transaction {} discarded.", txnId);
				}
				return report;
			} catch (Exception ex) {
				discardTransaction(txnId);
				throw ex;
			}
		} finally {
			configLock.unlock();
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Reconcile
	// ─────────────────────────────────────────────────────────────

	/**
	 * Parses the current on-disk haproxy.cfg and migrates every record that
	 * exists in the file but is missing from the DataPlane model into the model,
	 * using the given (already open) transaction. Runs inside the register /
	 * deregister / sync transaction so the commit regenerates the file WITHOUT
	 * dropping manual records.
	 *
	 * @param trn    the open transaction id
	 * @param prefix logging prefix ("Register" / "Deregister" / "Sync")
	 * @return report of what was migrated, skipped and left unsupported
	 */
	private ReconcileReport reconcileConfigWithModel(String trn, String prefix) {
		ReconcileReport report = new ReconcileReport();

		String rawConfig;
		try {
			rawConfig = proxyClient.getRawConfig().getBody();
		} catch (Exception e) {
			log.warn("[{}] Reconcile: could not read raw config — skipping migration.", prefix);
			return report;
		}
		if (rawConfig == null || rawConfig.isBlank()) {
			log.info("[{}] Reconcile: raw config is empty — nothing to migrate.", prefix);
			return report;
		}

		ConfigInventory inventory = HAProxyConfigParser.parse(rawConfig);
		List<String> unsupported = new ArrayList<>();
		for (var u : inventory.getUnsupported()) {
			unsupported.add(u.getType() + " " + u.getName());
		}
		report.setUnsupported(unsupported);

		// Backends + servers
		Map<String, Backend> modelBackends = new HashMap<>();
		try {
			List<Backend> backends = proxyClient.listBackends().getBody();
			if (backends != null) {
				for (Backend b : backends) {
					if (b.getName() != null) {
						modelBackends.put(b.getName().toLowerCase(Locale.ROOT), b);
					}
				}
			}
		} catch (Exception e) {
			log.warn("[{}] Reconcile: could not list backends — skipping backend migration.", prefix);
		}

		for (BackendRecord fileBe : inventory.getBackends()) {
			Backend existing = modelBackends.get(fileBe.getName().toLowerCase(Locale.ROOT));
			if (existing == null) {
				Backend payload = new Backend();
				payload.setName(fileBe.getName());
				payload.setMode(fileBe.getMode() != null ? fileBe.getMode() : "http");
				if (fileBe.getTimeoutServer() != null) payload.setTimeoutServer(fileBe.getTimeoutServer());
				if (fileBe.getTimeoutTunnel() != null) payload.setTimeoutTunnel(fileBe.getTimeoutTunnel());
				if (fileBe.getTimeoutConnect() != null) payload.setTimeoutConnect(fileBe.getTimeoutConnect());
				try {
					proxyClient.addBE(trn, payload);
					report.getMigratedBackends().add(fileBe.getName());
					log.info("[{}] Reconcile: migrated backend '{}' (mode {}) into the model.",
							prefix, fileBe.getName(), payload.getMode());
				} catch (FeignException e) {
					if (e.status() == 409) {
						report.getSkipped().add("backend " + fileBe.getName());
					} else {
						throw e;
					}
				}
			}

			// Path-rewrite rules for this backend (if any)
			if (!fileBe.getReplacePathRules().isEmpty()) {
				int ruleIndex = 0;
				for (HAProxyConfigParser.ReplacePathRecord rpr : fileBe.getReplacePathRules()) {
					HTTPRequestRule rulePayload = new HTTPRequestRule();
					rulePayload.setType("replace-path");
					rulePayload.setReplaceMatch(rpr.getMatch());
					rulePayload.setReplaceValue(rpr.getReplacement());
					try {
						proxyClient.createBackendHTTPRequestRule(trn, fileBe.getName(), ruleIndex++, rulePayload);
						log.info("[{}] Reconcile: migrated replace-path rule '{} -> {}' on backend '{}'.",
								prefix, rpr.getMatch(), rpr.getReplacement(), fileBe.getName());
					} catch (Exception e) {
						log.warn("[{}] Reconcile: failed to migrate replace-path rule on backend '{}': {}",
								prefix, fileBe.getName(), e.getMessage());
					}
				}
			}

			// Servers of this backend (also runs when the backend already exists)
			Set<String> modelServers = new HashSet<>();
			try {
				List<BackendServer> servers = proxyClient.listBEServers(fileBe.getName()).getBody();
				if (servers != null) {
					for (BackendServer s : servers) {
						if (s.getName() != null) {
							modelServers.add(s.getName().toLowerCase(Locale.ROOT));
						}
					}
				}
			} catch (Exception e) {
				log.warn("[{}] Reconcile: could not list servers of backend '{}'.", prefix, fileBe.getName());
			}

			for (ServerRecord fileSrv : fileBe.getServers()) {
				if (modelServers.contains(fileSrv.getName().toLowerCase(Locale.ROOT))) {
					continue;
				}
				BackendServer payload = new BackendServer();
				payload.setName(fileSrv.getName());
				payload.setAddress(fileSrv.getAddress());
				if (fileSrv.getPort() > 0) {
					payload.setPort(fileSrv.getPort());
				}
				if (fileSrv.isCheck()) {
					payload.setCheck("enabled");
				}
				if (fileSrv.isSsl()) {
					payload.setSsl("enabled");
				}
				if (fileSrv.getVerify() != null) {
					payload.setVerify(fileSrv.getVerify());
				}
				if (fileSrv.getResolvers() != null) {
					payload.setResolvers(fileSrv.getResolvers());
				}
				try {
					proxyClient.addBEServer(trn, fileBe.getName(), payload);
					report.getMigratedServers().add(fileBe.getName() + "/" + fileSrv.getName());
					log.info("[{}] Reconcile: migrated server '{}' into backend '{}'.",
							prefix, fileSrv.getName(), fileBe.getName());
				} catch (FeignException e) {
					if (e.status() == 409) {
						report.getSkipped().add("server " + fileBe.getName() + "/" + fileSrv.getName());
					} else {
						throw e;
					}
				}
			}
		}

		// Frontends: ACLs + switching rules, preserving on-disk order
		Map<String, Frontend> modelFrontends = new HashMap<>();
		try {
			List<Frontend> frontends = proxyClient.listFrontends().getBody();
			if (frontends != null) {
				for (Frontend f : frontends) {
					if (f.getName() != null) {
						modelFrontends.put(f.getName().toLowerCase(Locale.ROOT), f);
					}
				}
			}
		} catch (Exception e) {
			log.warn("[{}] Reconcile: could not list frontends — skipping ACL/rule migration.", prefix);
		}

		// Group the flat parser output per frontend, preserving line order
		Map<String, List<AclRecord>> fileAcls = new LinkedHashMap<>();
		for (AclRecord a : inventory.getAcls()) {
			fileAcls.computeIfAbsent(a.getFrontend(), k -> new ArrayList<>()).add(a);
		}
		Map<String, List<SwitchingRuleRecord>> fileRules = new LinkedHashMap<>();
		for (SwitchingRuleRecord r : inventory.getSwitchingRules()) {
			fileRules.computeIfAbsent(r.getFrontend(), k -> new ArrayList<>()).add(r);
		}

		Set<String> allFrontends = new LinkedHashSet<>();
		allFrontends.addAll(fileAcls.keySet());
		allFrontends.addAll(fileRules.keySet());

		for (String feName : allFrontends) {
			if (!modelFrontends.containsKey(feName.toLowerCase(Locale.ROOT))) {
				report.getUnsupported().add("frontend " + feName + " (missing from model)");
				continue;
			}

			Set<String> modelAclNames = new HashSet<>();
			Set<String> modelRuleNames = new HashSet<>();
			try {
				List<ACL> acls = proxyClient.listACLs(feName).getBody();
				if (acls != null) {
					for (ACL a : acls) {
						if (a.getAcl_name() != null) {
							modelAclNames.add(a.getAcl_name().toLowerCase(Locale.ROOT));
						}
					}
				}
			} catch (Exception e) {
				log.warn("[{}] Reconcile: could not list ACLs of frontend '{}'.", prefix, feName);
			}
			try {
				List<BESwitchRule> rules = proxyClient.listBESwitchingRules(feName).getBody();
				if (rules != null) {
					for (BESwitchRule r : rules) {
						if (r.getName() != null) {
							modelRuleNames.add(r.getName().toLowerCase(Locale.ROOT));
						}
					}
				}
			} catch (Exception e) {
				log.warn("[{}] Reconcile: could not list switching rules of frontend '{}'.", prefix, feName);
			}

			int aclPosition = 0;
			List<AclRecord> fileAclList = fileAcls.getOrDefault(feName, List.of());
			for (AclRecord fileAcl : fileAclList) {
				if (!modelAclNames.contains(fileAcl.getAclName().toLowerCase(Locale.ROOT))) {
					ACL payload = new ACL();
					payload.setAcl_name(fileAcl.getAclName());
					payload.setCriterion(fileAcl.getCriterion());
					payload.setValue(fileAcl.getValue());
					try {
						proxyClient.createACL(trn, feName, aclPosition, payload);
						modelAclNames.add(fileAcl.getAclName().toLowerCase(Locale.ROOT));
						report.getMigratedAcls().add(feName + "/" + fileAcl.getAclName());
						log.info("[{}] Reconcile: migrated ACL '{}' at index {} on frontend '{}'.",
								prefix, fileAcl.getAclName(), aclPosition, feName);
					} catch (FeignException e) {
						if (e.status() == 409) {
							report.getSkipped().add("acl " + feName + "/" + fileAcl.getAclName());
						} else {
							throw e;
						}
					}
				}
				aclPosition++;
			}

			int rulePosition = 0;
			List<SwitchingRuleRecord> fileRuleList = fileRules.getOrDefault(feName, List.of());
			for (SwitchingRuleRecord fileRule : fileRuleList) {
				if (!modelRuleNames.contains(fileRule.getBackend().toLowerCase(Locale.ROOT))) {
					BESwitchRule payload = new BESwitchRule();
					payload.setName(fileRule.getBackend());
					payload.setCond(fileRule.getCond());
					payload.setCond_test(fileRule.getCondTest());
					try {
						proxyClient.createBESwitchingRule(trn, feName, rulePosition, payload);
						modelRuleNames.add(fileRule.getBackend().toLowerCase(Locale.ROOT));
						report.getMigratedRules().add(feName + "/" + fileRule.getBackend());
						log.info("[{}] Reconcile: migrated switching rule '{}' at index {} on frontend '{}'.",
								prefix, fileRule.getBackend(), rulePosition, feName);
					} catch (FeignException e) {
						if (e.status() == 409) {
							report.getSkipped().add("rule " + feName + "/" + fileRule.getBackend());
						} else {
							throw e;
						}
					}
				}
				rulePosition++;
			}
		}

		if (report.isClean()) {
			log.info("[{}] Reconcile: model already matches the file — nothing to migrate.", prefix);
		} else {
			log.info(
					"[{}] Reconcile done: {} backends, {} servers, {} ACLs, {} rules migrated, {} skipped, {} unsupported.",
					prefix,
					report.getMigratedBackends().size(), report.getMigratedServers().size(),
					report.getMigratedAcls().size(), report.getMigratedRules().size(),
					report.getSkipped().size(), report.getUnsupported().size());
		}
		return report;
	}

	private static String aclKey(AclRecord acl) {
		return (acl.getAclName() == null ? "" : acl.getAclName()) + "|"
				+ (acl.getCriterion() == null ? "" : acl.getCriterion()) + "|"
				+ (acl.getValue() == null ? "" : acl.getValue());
	}

	private static String aclKey(ACL acl) {
		return (acl.getAcl_name() == null ? "" : acl.getAcl_name()) + "|"
				+ (acl.getCriterion() == null ? "" : acl.getCriterion()) + "|"
				+ (acl.getValue() == null ? "" : acl.getValue());
	}

	private static String ruleKey(SwitchingRuleRecord rule) {
		return (rule.getBackend() == null ? "" : rule.getBackend()) + "|"
				+ (rule.getCond() == null ? "" : rule.getCond()) + "|"
				+ (rule.getCondTest() == null ? "" : rule.getCondTest());
	}

	private static String ruleKey(BESwitchRule rule) {
		return (rule.getName() == null ? "" : rule.getName()) + "|"
				+ (rule.getCond() == null ? "" : rule.getCond()) + "|"
				+ (rule.getCond_test() == null ? "" : rule.getCond_test());
	}

	/**
	 * Result of a reconcile: what was migrated into the DataPlane model,
	 * what was skipped (409 = already exists) and which file sections the
	 * DataPlane API cannot manage ({@link #isClean()} == true means the model
	 * already matched the file).
	 */
	@Getter
	@Setter
	@Schema(description = "Report detailing the outcome of reconciling on-disk haproxy.cfg with the DataPlane API model")
	public static class ReconcileReport {

		@Schema(description = "Names of backends migrated from file into DataPlane model")
		private List<String> migratedBackends = new ArrayList<>();

		@Schema(description = "Names of servers migrated from file into DataPlane model")
		private List<String> migratedServers = new ArrayList<>();

		@Schema(description = "Names of frontend ACLs migrated from file into DataPlane model")
		private List<String> migratedAcls = new ArrayList<>();

		@Schema(description = "Names of frontend switching rules migrated from file into DataPlane model")
		private List<String> migratedRules = new ArrayList<>();

		@Schema(description = "Records skipped because they already existed in the DataPlane model")
		private List<String> skipped = new ArrayList<>();

		@Schema(description = "Sections that could not be migrated due to lack of DataPlane API support")
		private List<String> unsupported = new ArrayList<>();

		@Schema(description = "True if model was already clean and fully synchronized with file")
		public boolean isClean() {
			return migratedBackends.isEmpty() && migratedServers.isEmpty()
					&& migratedAcls.isEmpty() && migratedRules.isEmpty()
					&& skipped.isEmpty();
		}
	}

	/**
	 * Executes a single API step, logging the HTTP status and response body
	 * if it fails with a FeignException, then rethrows.
	 */
	private <T> T call(String prefix, String stepName, Supplier<T> step) {
		try {
			return step.get();
		} catch (FeignException e) {
			String body = "";
			if (e.responseBody().isPresent()) {
				try {
					body = new String(e.responseBody().get().array(), StandardCharsets.UTF_8);
				} catch (Exception ignored) {
					// keep empty body
				}
			}
			log.error("[{}] Step '{}' failed: HTTP {} — {}",
					prefix, stepName, e.status(), body.isBlank() ? e.getMessage() : body);
			throw e;
		}
	}

	/**
	 * Resolves the position at which the ACL + switching rule should be inserted.
	 * New routes must never land after an existing {@code http-request deny}
	 * rule, otherwise the deny is evaluated first and the route stays dead.
	 * Returns {@code min(requestedIndex, firstDenyIndex)}.
	 */
	private int resolveInsertIndex(String parentFrontEnd, int requestedIndex) {
		List<HTTPRequestRule> httpRules = proxyClient.listHTTPRequestRules(parentFrontEnd).getBody();
		if (httpRules == null || httpRules.isEmpty()) {
			return requestedIndex;
		}
		int firstDenyIndex = httpRules.stream()
				.filter(r -> "deny".equalsIgnoreCase(r.getType()))
				.map(HTTPRequestRule::getIndex)
				.filter(Objects::nonNull)
				.mapToInt(Integer::intValue)
				.min()
				.orElse(Integer.MAX_VALUE);
		return Math.min(requestedIndex, firstDenyIndex);
	}

	/**
	 * Reads the active config back after a commit and checks that the ACL,
	 * the switching rule and the backend are really present.
	 */
	private boolean verifyRegistration(HAProxyIn proxyIn, String aclName) {
		String compName = proxyIn.getCompName();

		List<ACL> acls = proxyClient.listACLs(proxyIn.getParentFE()).getBody();
		boolean aclFound = acls != null && acls.stream()
				.anyMatch(a -> aclName.equalsIgnoreCase(a.getAcl_name()));

		List<BESwitchRule> rules = proxyClient.listBESwitchingRules(proxyIn.getParentFE()).getBody();
		boolean ruleFound = rules != null && rules.stream()
				.anyMatch(r -> compName.equalsIgnoreCase(r.getName()));

		boolean backendFound = false;
		try {
			Backend be = proxyClient.getBackend(compName).getBody();
			backendFound = be != null && compName.equalsIgnoreCase(be.getName());
		} catch (FeignException e) {
			if (e.status() != 404) {
				throw e;
			}
		}

		log.debug("[Register] Verification for '{}': ACL={} BESwitchingRule={} Backend={}",
				compName, aclFound, ruleFound, backendFound);
		return aclFound && ruleFound && backendFound;
	}

	private String getLatestReloadId() {
		try {
			List<Reload> reloads = proxyClient.getReloads().getBody();
			if (reloads != null && !reloads.isEmpty()) {
				return reloads.get(reloads.size() - 1).getId();
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	/**
	 * Polls {@code GET /reloads} after a transaction commit until the latest
	 * reload entry reaches a terminal status ({@code "succeeded"} or
	 * {@code "failure"}) or {@link #RELOAD_WAIT_MS} elapses.
	 *
	 * <p>
	 * The DataPlane API triggers a graceful reload automatically on every
	 * successful {@code commitTransaction(trn, true)} call. This method waits
	 * for that specific reload to complete.
	 *
	 * <p>When {@code reloadId} is non-null the poll only matches the entry with
	 * that exact ID, preventing false positives from old stale reload entries
	 * sitting at the tail of the reload list. When {@code reloadId} is null
	 * (DataPlane did not return one) the method falls back to checking the last
	 * entry, preserving backward-compatible behaviour.
	 *
	 * @param prefix   logging prefix (e.g. "Register" / "Deregister")
	 * @param reloadId the reload ID returned by the commit response, or {@code null}
	 * @return {@code true} if reload succeeded or timed out; {@code false} only on explicit failure
	 */
	public boolean waitForReload(String prefix, String reloadId) {
		if (reloadId != null) {
			log.debug("[{}] Waiting for reload id={} (up to {}ms)", prefix, reloadId, RELOAD_WAIT_MS);
		} else {
			log.debug("[{}] No reload_id from commit — falling back to last-entry poll (up to {}ms)", prefix, RELOAD_WAIT_MS);
		}
		long deadline = System.currentTimeMillis() + RELOAD_WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(RELOAD_POLL_MS);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				log.warn("[{}] waitForReload interrupted — assuming reload in progress.", prefix);
				return true;
			}
			try {
				List<Reload> reloads = proxyClient.getReloads().getBody();
				if (reloads == null || reloads.isEmpty()) {
					continue;
				}
				// Locate the specific reload entry when we have its ID;
				// fall back to the last entry for backward compatibility.
				Reload target;
				if (reloadId != null) {
					Optional<Reload> match = reloads.stream()
							.filter(r -> reloadId.equals(r.getId()))
							.findFirst();
					if (match.isEmpty()) {
						log.debug("[{}] Reload id={} not yet in list — still waiting...", prefix, reloadId);
						continue;
					}
					target = match.get();
				} else {
					target = reloads.get(reloads.size() - 1);
				}
				String status = target.getStatus();
				log.debug("[{}] Reload poll: id={} status={}", prefix, target.getId(), status);
				if ("succeeded".equalsIgnoreCase(status)) {
					log.info("[{}] HAProxy reload succeeded (id={}).", prefix, target.getId());
					return true;
				}
				if ("failure".equalsIgnoreCase(status)) {
					log.error("[{}] HAProxy reload FAILED (id={}): {}", prefix, target.getId(), target.getError());
					return false;
				}
				// status = "in_progress" or unknown — keep polling
			} catch (Exception e) {
				log.warn("[{}] Could not read reload status: {}", prefix, e.getMessage());
			}
		}
		log.warn(
				"[{}] Timed out after {}ms waiting for HAProxy reload (id={}). Config IS committed; route active after next reload.",
				prefix, RELOAD_WAIT_MS, reloadId);
		return true; // soft: config was committed, reload just slow
	}

	/**
	 * Explicitly triggers a reload via DataPlane API and waits for completion.
	 * The reload ID is taken from the trigger response so polling is precise.
	 */
	public boolean triggerAndSyncReload(String prefix) {
		String triggeredReloadId = null;
		try {
			Reload triggered = proxyClient.triggerReload().getBody();
			if (triggered != null) {
				triggeredReloadId = triggered.getId();
			}
			log.info("[{}] Triggered explicit HAProxy reload via DataPlane API (id={}).", prefix, triggeredReloadId);
		} catch (Exception ex) {
			log.warn("[{}] Could not trigger explicit reload via DataPlane API: {}", prefix, ex.getMessage());
		}
		return waitForReload(prefix, triggeredReloadId);
	}

	/**
	 * Discards an open (uncommitted) transaction so it does not accumulate
	 * on the DataPlane API. No-op when {@code txnId} is null (already committed).
	 */
	private void discardTransaction(String txnId) {
		if (txnId == null) {
			return;
		}
		try {
			proxyClient.deleteTransaction(txnId);
			log.info("Discarded uncommitted transaction {}", txnId);
		} catch (Exception e) {
			log.warn("Failed to discard transaction {}: {}", txnId, e.getMessage());
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Backend builder helpers (multi-type support)
	// ─────────────────────────────────────────────────────────────

	/**
	 * Resolves the effective {@link BackendType} from the request.
	 * Falls back to {@link BackendType#K8S_DNS} when {@code backendType} is null
	 * (backward compatibility with existing callers).
	 */
	private BackendType effectiveType(HAProxyIn proxyIn) {
		if (proxyIn.getBackendType() != null) {
			return proxyIn.getBackendType();
		}
		// Auto-detect from field presence for backward compat
		if (proxyIn.isSsl())
			return BackendType.SSL;
		if (proxyIn.getServerAddress() != null && !proxyIn.getServerAddress().isBlank())
			return BackendType.EXTERNAL_IP;
		if (proxyIn.getPathPrefix() != null && !proxyIn.getPathPrefix().isBlank())
			return BackendType.PATH_REWRITE;
		return BackendType.K8S_DNS;
	}

	/**
	 * Builds the {@link Backend} payload according to the resolved
	 * {@link BackendType}.
	 * Sets mode (http vs tcp) and per-backend timeout overrides for AI_ML and
	 * WEBSOCKET.
	 */
	private Backend buildBackend(HAProxyIn proxyIn, BackendType type) {
		Backend b = new Backend();
		b.setName(proxyIn.getCompName());
		b.setMode(type == BackendType.TCP ? "tcp" : "http");

		switch (type) {
			case TIME_CONFIGURABLE -> {
				b.setTimeoutServer(proxyIn.getTimeoutServer() != null
						? proxyIn.getTimeoutServer()
						: AI_ML_DEFAULT_TIMEOUT_MS);
				b.setTimeoutTunnel(proxyIn.getTimeoutTunnel() != null
						? proxyIn.getTimeoutTunnel()
						: AI_ML_DEFAULT_TIMEOUT_MS);
			}
			case WEBSOCKET -> {
				if (proxyIn.getTimeoutTunnel() != null) {
					b.setTimeoutTunnel(proxyIn.getTimeoutTunnel());
				}
				if (proxyIn.getTimeoutServer() != null) {
					b.setTimeoutServer(proxyIn.getTimeoutServer());
				}
			}
			default -> {
				// K8S_DNS, EXTERNAL_IP, SSL, PATH_REWRITE, TCP:
				// apply explicit overrides if provided
				if (proxyIn.getTimeoutServer() != null)
					b.setTimeoutServer(proxyIn.getTimeoutServer());
				if (proxyIn.getTimeoutTunnel() != null)
					b.setTimeoutTunnel(proxyIn.getTimeoutTunnel());
			}
		}
		return b;
	}

	/**
	 * Builds the {@link BackendServer} payload according to the resolved
	 * {@link BackendType}.
	 * <ul>
	 * <li>K8S_DNS / PATH_REWRITE / WEBSOCKET / AI_ML — FQDN via namespace DNS +
	 * check</li>
	 * <li>EXTERNAL_IP — raw serverAddress:serverPort + check</li>
	 * <li>SSL — FQDN via namespace DNS + {@code ssl verify none} (no check, TLS
	 * upstream)</li>
	 * <li>TCP — FQDN via namespace DNS (no check directive needed)</li>
	 * </ul>
	 */
	private BackendServer buildBackendServer(HAProxyIn proxyIn, BackendType type) {
		BackendServer s = new BackendServer();
		s.setName(proxyIn.getCompName());

		if (type == BackendType.EXTERNAL_IP) {
			s.setAddress(proxyIn.getServerAddress());
			s.setPort(proxyIn.getServerPort());
			s.setCheck("enabled");
		} else {
			// All K8s-based types resolve via namespace DNS
			s.setAddress(proxyIn.getCompName() + "." + proxyIn.getNamespace() + ".svc.cluster.local");
			s.setPort(proxyIn.getInternalPort());
			if (type == BackendType.SSL || proxyIn.isSsl()) {
				// TLS to upstream — ssl verify none (self-signed certs)
				s.setSsl("enabled");
				s.setVerify("none");
			} else {
				s.setCheck("enabled");
			}
		}

		// Optional: CoreDNS resolver for dynamic resolution
		if (proxyIn.getResolvers() != null && !proxyIn.getResolvers().isBlank()) {
			s.setResolvers(proxyIn.getResolvers());
		}
		return s;
	}

	/**
	 * Builds the {@code http-request replace-path} rule for a
	 * {@link BackendType#PATH_REWRITE} backend.
	 *
	 * <p>
	 * Given {@code pathPrefix = "/mgw"} and {@code pathReplacement = null},
	 * the generated rule is equivalent to:
	 * 
	 * <pre>
	 * http-request replace-path /mgw(/)?(.*) /\2
	 * </pre>
	 *
	 * <p>
	 * If {@code pathReplacement} is set (e.g. {@code /dms/\2}) it is used verbatim,
	 * allowing advanced rewrites like the eOffice S3 wrapper.
	 */
	private HTTPRequestRule buildPathRewriteRule(HAProxyIn proxyIn) {
		HTTPRequestRule rule = new HTTPRequestRule();
		rule.setType("replace-path");
		String prefix = proxyIn.getPathPrefix().replaceAll("/+$", ""); // strip trailing slash
		rule.setReplaceMatch(prefix + "(/)?(.*)$");
		rule.setReplaceValue(proxyIn.getPathReplacement() != null
				&& !proxyIn.getPathReplacement().isBlank()
						? proxyIn.getPathReplacement()
						: "/\\2");
		return rule;
	}
}
