package com.nnp.haproxy.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * Parses the raw HAProxy configuration text into a logical record inventory
 * (backends, servers, ACLs, use_backend rules, timeouts, rewrite rules) so the
 * service can compare the on-disk file against the DataPlane API model and
 * migrate records that exist only in the file into the model.
 *
 * <p>This is a read-only parser  -  it never modifies the configuration. It is
 * tolerant of any indentation and of inline comments, matching the style of
 * manually edited config files.
 */
public final class HAProxyConfigParser {

	private static final Pattern SECTION_HEADER = Pattern.compile(
			"^([a-z][a-z0-9-]*)(?:\\s+(\\S+))?(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE);

	private static final Pattern SERVER_LINE = Pattern.compile(
			"^\\s*server\\s+(\\S+)\\s+(\\S+)(.*)$", Pattern.CASE_INSENSITIVE);

	private static final Pattern ACL_LINE = Pattern.compile(
			"^\\s*acl\\s+(\\S+)\\s+(\\S+)\\s+(.*)$", Pattern.CASE_INSENSITIVE);

	private static final Pattern USE_BACKEND_LINE = Pattern.compile(
			"^\\s*use_backend\\s+(\\S+)\\s*(.*)$", Pattern.CASE_INSENSITIVE);

	private static final Pattern MODE_LINE = Pattern.compile(
			"^\\s*mode\\s+(\\S+)\\s*(?:#.*)?$", Pattern.CASE_INSENSITIVE);

	private static final Pattern TIMEOUT_LINE = Pattern.compile(
			"^\\s*timeout\\s+(server|tunnel|connect)\\s+(\\S+)\\s*(?:#.*)?$", Pattern.CASE_INSENSITIVE);

	private static final Pattern REPLACE_PATH_LINE = Pattern.compile(
			"^\\s*http-request\\s+replace-path\\s+(\\S+)\\s+(\\S+)\\s*(?:#.*)?$", Pattern.CASE_INSENSITIVE);

	private static final Pattern COND_LINE = Pattern.compile(
			"^(if|unless)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

	private static final Pattern SSL_PATTERN = Pattern.compile(
			"\\bssl\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern VERIFY_PATTERN = Pattern.compile(
			"\\bverify\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

	private static final Pattern RESOLVERS_PATTERN = Pattern.compile(
			"\\bresolvers\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

	/** Section keywords the parser recognises. Anything else at column 0 is ignored. */
	private static final Set<String> SECTION_KEYWORDS = Set.of(
			"global", "defaults", "frontend", "backend", "listen", "resolvers", "peers",
			"mailers", "program", "userlist", "cache", "stick-table", "http-errors",
			"spoe", "ring", "log-forward", "fcgi-app", "event_hdl");

	/** Sections that have no (or no migrated) equivalent in the DataPlane API model. */
	private static final Set<String> UNSUPPORTED_SECTIONS = Set.of(
			"listen", "resolvers", "peers", "mailers", "program", "userlist", "cache",
			"stick-table", "http-errors", "spoe", "ring", "log-forward", "fcgi-app", "event_hdl");

	private HAProxyConfigParser() {
	}

	/**
	 * Parses the given raw configuration text into an inventory of records.
	 *
	 * @param rawConfig the full haproxy.cfg content
	 * @return the parsed inventory
	 */
	public static ConfigInventory parse(String rawConfig) {
		ConfigInventory inventory = new ConfigInventory();
		if (rawConfig == null) {
			return inventory;
		}

		String sectionType = null;
		String sectionName = null;
		Set<String> reportedUnsupported = new LinkedHashSet<>();

		for (String rawLine : rawConfig.split("\n", -1)) {
			String line = rawLine.trim();

			Optional<String[]> headerOpt = sectionHeader(line);
			if (headerOpt.isPresent()) {
				String[] header = headerOpt.get();
				sectionType = header[0];
				sectionName = header[1];
				if (UNSUPPORTED_SECTIONS.contains(sectionType)
						&& reportedUnsupported.add(sectionType + " " + sectionName)) {
					inventory.getUnsupported().add(new UnsupportedRecord(sectionType, sectionName,
							"section type has no DataPlane API equivalent  -  cannot be migrated"));
				}
				if ("backend".equals(sectionType) && sectionName != null) {
					// register the backend even if it has no child lines yet
					backend(inventory, sectionName);
				}
				continue;
			}

			if (sectionType == null) {
				continue;
			}

			switch (sectionType) {
				case "backend" -> handleBackendLine(inventory, sectionName, line);
				case "frontend" -> handleFrontendLine(inventory, sectionName, line);
				default -> {
					// other sections are reported (or ignored) and not parsed further
				}
			}
		}

		return inventory;
	}

	// -------------------------------------------------------------
	// Parsing helpers
	// -------------------------------------------------------------

	/**
	 * Returns [keyword, name] when the line is a section header, or {@code null}.
	 * Only recognised section keywords at the start of a line qualify.
	 */
    private static Optional<String[]> sectionHeader(String line) {
        if (line.startsWith("#") || line.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = SECTION_HEADER.matcher(line);
        if (!m.matches() || !SECTION_KEYWORDS.contains(m.group(1).toLowerCase())) {
            return Optional.empty();
        }
        return Optional.of(new String[] { m.group(1).toLowerCase(), m.group(2) });
    }

	private static void handleBackendLine(ConfigInventory inventory, String backendName, String line) {
		BackendRecord be = backend(inventory, backendName);

		Matcher mode = MODE_LINE.matcher(line);
		if (mode.matches()) {
			be.setMode(mode.group(1).toLowerCase());
			return;
		}

		Matcher timeout = TIMEOUT_LINE.matcher(line);
		if (timeout.matches()) {
			String type = timeout.group(1).toLowerCase();
			Long ms = parseTimeoutToMs(timeout.group(2));
			switch (type) {
				case "server" -> be.setTimeoutServer(ms);
				case "tunnel" -> be.setTimeoutTunnel(ms);
				case "connect" -> be.setTimeoutConnect(ms);
                default -> throw new IllegalStateException("Unhandled timeout type: " + type);
			}
			return;
		}

		Matcher replacePath = REPLACE_PATH_LINE.matcher(line);
		if (replacePath.matches()) {
			be.getReplacePathRules().add(new ReplacePathRecord(
					replacePath.group(1), replacePath.group(2)));
			return;
		}

		Matcher server = SERVER_LINE.matcher(line);
		if (server.matches()) {
			ServerRecord srv = parseServerLine(server);
			be.getServers().add(srv);
		}
	}

	private static void handleFrontendLine(ConfigInventory inventory, String frontendName, String line) {
		Matcher acl = ACL_LINE.matcher(line);
		if (acl.matches()) {
			inventory.getAcls().add(new AclRecord(frontendName,
					acl.group(1), acl.group(2), acl.group(3).trim()));
			return;
		}

		Matcher rule = USE_BACKEND_LINE.matcher(line);
		if (rule.matches()) {
			String cond = null;
			String condTest = null;
			Matcher condMatcher = COND_LINE.matcher(rule.group(2).trim());
			if (condMatcher.matches()) {
				cond = condMatcher.group(1).toLowerCase();
				condTest = condMatcher.group(2).trim();
			}
			inventory.getSwitchingRules().add(new SwitchingRuleRecord(frontendName,
					rule.group(1), cond, condTest));
		}
	}

	/** Parses {@code server <name> <address[:port]> [options...]}  -  options like {@code check}, {@code ssl}, {@code verify}, {@code resolvers} are kept. */
	private static ServerRecord parseServerLine(Matcher server) {
		String name = stripQuotes(server.group(1));
		String addrPort = server.group(2);
		String address = addrPort;
		int port = 0;

		if (addrPort.startsWith("[")) {
			int close = addrPort.indexOf(']');
			if (close > 0) {
				address = addrPort.substring(1, close);
				if (close + 1 < addrPort.length() && addrPort.charAt(close + 1) == ':') {
					port = parseIntOrZero(addrPort.substring(close + 2));
				}
			}
		} else {
			int firstColon = addrPort.indexOf(':');
			int lastColon = addrPort.lastIndexOf(':');
			if (firstColon > 0 && firstColon == lastColon) {
				address = addrPort.substring(0, firstColon);
				port = parseIntOrZero(addrPort.substring(firstColon + 1));
			}
		}

		String options = server.group(3);
		boolean check = options != null && Pattern.compile("\\bcheck\\b", Pattern.CASE_INSENSITIVE).matcher(options).find();
		boolean ssl = options != null && SSL_PATTERN.matcher(options).find();

		String verify = null;
		if (options != null) {
			Matcher vm = VERIFY_PATTERN.matcher(options);
			if (vm.find()) {
				verify = vm.group(1);
			}
		}

		String resolvers = null;
		if (options != null) {
			Matcher rm = RESOLVERS_PATTERN.matcher(options);
			if (rm.find()) {
				resolvers = rm.group(1);
			}
		}

		ServerRecord rec = new ServerRecord(name, address, port, check);
		rec.setSsl(ssl);
		rec.setVerify(verify);
		rec.setResolvers(resolvers);
		return rec;
	}

	private static String stripQuotes(String token) {
		if (token != null && token.length() >= 2
				&& ((token.startsWith("\"") && token.endsWith("\""))
				|| (token.startsWith("'") && token.endsWith("'")))) {
			return token.substring(1, token.length() - 1);
		}
		return token;
	}

	private static int parseIntOrZero(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Parses timeout strings like "600s", "10m", "1h", "600000ms", "600000" into milliseconds.
	 */
	public static Long parseTimeoutToMs(String val) {
		if (val == null || val.isBlank()) {
			return null;
		}
		val = val.trim().toLowerCase();
		try {
			if (val.endsWith("ms")) {
				return Long.parseLong(val.substring(0, val.length() - 2));
			} else if (val.endsWith("s")) {
				return Long.parseLong(val.substring(0, val.length() - 1)) * 1000L;
			} else if (val.endsWith("m")) {
				return Long.parseLong(val.substring(0, val.length() - 1)) * 60_000L;
			} else if (val.endsWith("h")) {
				return Long.parseLong(val.substring(0, val.length() - 1)) * 3_600_000L;
			} else if (val.endsWith("d")) {
				return Long.parseLong(val.substring(0, val.length() - 1)) * 86_400_000L;
			} else {
				return Long.parseLong(val);
			}
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static BackendRecord backend(ConfigInventory inventory, String name) {
		for (BackendRecord existing : inventory.getBackends()) {
			if (existing.getName().equalsIgnoreCase(name)) {
				return existing;
			}
		}
		BackendRecord created = new BackendRecord(name);
		inventory.getBackends().add(created);
		return created;
	}

	// -------------------------------------------------------------
	// Inventory model
	// -------------------------------------------------------------

	@Getter
	@Schema(description = "Complete parsed inventory of HAProxy configuration")
	public static final class ConfigInventory {

		@Schema(description = "List of configured backend sections and their servers/rules")
		private final List<BackendRecord> backends = new ArrayList<>();

		@Schema(description = "List of frontend ACL rules")
		private final List<AclRecord> acls = new ArrayList<>();

		@Schema(description = "List of frontend use_backend switching rules")
		private final List<SwitchingRuleRecord> switchingRules = new ArrayList<>();

		@Schema(description = "List of unsupported sections found in haproxy.cfg (e.g. resolvers, peers)")
		private final List<UnsupportedRecord> unsupported = new ArrayList<>();
	}

	@Getter
	@Setter
	@Schema(description = "HAProxy backend definition")
	public static final class BackendRecord {

		@Schema(description = "Backend name", example = "order-service")
		private final String name;

		@Schema(description = "Proxy mode", example = "http")
		private String mode;

		@Schema(description = "Server response timeout in ms", example = "600000")
		private Long timeoutServer;

		@Schema(description = "Tunnel timeout in ms", example = "3600000")
		private Long timeoutTunnel;

		@Schema(description = "Connect timeout in ms", example = "5000")
		private Long timeoutConnect;

		@Schema(description = "HTTP replace-path rewrite rules")
		private final List<ReplacePathRecord> replacePathRules = new ArrayList<>();

		@Schema(description = "Upstream servers belonging to this backend")
		private final List<ServerRecord> servers = new ArrayList<>();

		public BackendRecord(String name) {
			this.name = name;
		}
	}

	@Getter
	@Setter
	@Schema(description = "Path rewrite rule configured on a backend")
	public static final class ReplacePathRecord {

		@Schema(description = "Regex pattern to match against request path", example = "/mgw(/)?(.*)")
		private final String match;

		@Schema(description = "Replacement expression", example = "/\\2")
		private final String replacement;

		public ReplacePathRecord(String match, String replacement) {
			this.match = match;
			this.replacement = replacement;
		}
	}

	@Getter
	@Setter
	@Schema(description = "Upstream server line inside a backend")
	public static final class ServerRecord {

		@Schema(description = "Server identifier name", example = "order-service")
		private final String name;

		@Schema(description = "IP address or FQDN hostname", example = "order-service.nnp.svc.cluster.local")
		private final String address;

		@Schema(description = "Port number", example = "8080")
		private final int port;

		@Schema(description = "Whether health checking is enabled", example = "true")
		private final boolean check;

		@Schema(description = "Whether TLS/SSL is enabled to upstream", example = "false")
		private boolean ssl;

		@Schema(description = "SSL verification setting", example = "none")
		private String verify;

		@Schema(description = "Resolver section name used for dynamic DNS", example = "k8s_dns")
		private String resolvers;

		public ServerRecord(String name, String address, int port, boolean check) {
			this.name = name;
			this.address = address;
			this.port = port;
			this.check = check;
		}
	}

	@Getter
	@Schema(description = "Frontend ACL rule")
	public static final class AclRecord {

		@Schema(description = "Frontend name", example = "http_front")
		private final String frontend;

		@Schema(description = "ACL rule name", example = "is_order-service")
		private final String aclName;

		@Schema(description = "Criterion used for matching", example = "hdr(host)")
		private final String criterion;

		@Schema(description = "Matching value/domain", example = "-i order-service.example.com")
		private final String value;

		public AclRecord(String frontend, String aclName, String criterion, String value) {
			this.frontend = frontend;
			this.aclName = aclName;
			this.criterion = criterion;
			this.value = value;
		}
	}

	@Getter
	@Schema(description = "Frontend switching rule (use_backend)")
	public static final class SwitchingRuleRecord {

		@Schema(description = "Frontend name", example = "http_front")
		private final String frontend;

		@Schema(description = "Target backend name to route traffic to", example = "order-service")
		private final String backend;

		@Schema(description = "Condition type", example = "if")
		private final String cond;

		@Schema(description = "Condition expression or ACL name", example = "is_order-service")
		private final String condTest;

		public SwitchingRuleRecord(String frontend, String backend, String cond, String condTest) {
			this.frontend = frontend;
			this.backend = backend;
			this.cond = cond;
			this.condTest = condTest;
		}
	}

	@Getter
	@Schema(description = "Section in haproxy.cfg that cannot be migrated to DataPlane API model")
	public static final class UnsupportedRecord {

		@Schema(description = "Section keyword", example = "resolvers")
		private final String type;

		@Schema(description = "Section name", example = "k8s_dns")
		private final String name;

		@Schema(description = "Reason why it is unsupported", example = "section type has no DataPlane API equivalent  -  cannot be migrated")
		private final String reason;

		public UnsupportedRecord(String type, String name, String reason) {
			this.type = type;
			this.name = name;
			this.reason = reason;
		}
	}
}
