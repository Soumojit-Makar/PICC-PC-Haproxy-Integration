# Development Guidelines and Contribution Standards: `PICC-PC-Haproxy-Integration`

This document defines the architectural standards, development workflows, coding conventions, and security requirements for contributors to **`PICC-PC-Haproxy-Integration`**.

---

## Table of Contents

1. [Architecture & Design Principles](#1-architecture--design-principles)
2. [Development Environment Setup](#2-development-environment-setup)
3. [Package Structure & Code Navigation](#3-package-structure--code-navigation)
4. [Coding Standards & Best Practices](#4-coding-standards--best-practices)
   - [Spring Cloud OpenFeign Conventions](#spring-cloud-openfeign-conventions)
   - [HAProxy DataPlane Transaction Safety](#haproxy-dataplane-transaction-safety)
   - [Two-Way Configuration Reconciliation](#two-way-configuration-reconciliation)
   - [Asynchronous Operations & Status Tracking](#asynchronous-operations--status-tracking)
   - [Exception Handling & Validation](#exception-handling--validation)
   - [Logging & Sensitive Data Masking](#logging--sensitive-data-masking)
5. [Security, Code Quality & Compliance Tooling](#5-security-code-quality--compliance-tooling)
   - [SAST: SpotBugs & FindSecBugs](#sast-spotbugs--findsecbugs)
   - [SCA: OWASP Dependency-Check](#sca-owasp-dependency-check)
   - [SBOM: CycloneDX Aggregate Generation](#sbom-cyclonedx-aggregate-generation)
   - [Checkstyle: Google Java Style](#checkstyle-google-java-style)
6. [Git Workflow & Branching Strategy](#6-git-workflow--branching-strategy)
   - [Branch Naming Conventions](#branch-naming-conventions)
   - [Conventional Commits](#conventional-commits)
7. [Pull Request (PR) Checklist](#7-pull-request-pr-checklist)
8. [Release Lifecycle & Versioning](#8-release-lifecycle--versioning)

---

## 1. Architecture & Design Principles

`PICC-PC-Haproxy-Integration` acts as the orchestration gateway between platform microservices and the **HAProxy DataPlane API v3**. It adheres to core architectural principles:

1. **Declarative RPC Integration**: All outbound REST calls to HAProxy DataPlane API v3 are encapsulated within **Spring Cloud OpenFeign** clients (`HAProxyFeignClient`). Manual HTTP client plumbing is strictly forbidden.
2. **Transactional Atomicity**: All mutative configuration operations (backend creation, server binding, ACL registration, HTTP switching rules) are staged inside an explicit **DataPlane API transaction**. Transactions are committed atomically with `force_reload=true` or aborted cleanly upon failure.
3. **On-Disk File Preservation & Synchronization**: The service prevents accidental loss of manual edits made directly to `/etc/haproxy/haproxy.cfg`. Before initiating transactions, it re-imports the live on-disk file into the DataPlane API model or reconciles missing entities.
4. **Non-Blocking Asynchronous Lifecycle**: Heavy registration and deregistration processes execute asynchronously in background thread pools (`CompletableFuture`), returning `202 ACCEPTED` immediately to callers with pollable tracking tokens.
5. **Zero-Trust Credential Security**: DataPlane API credentials, Kubernetes cluster tokens, and sensitive headers must never be logged in plain text or exposed via `/actuator/env` or error payloads.

---

## 2. Development Environment Setup

### Required Tools
- **JDK 21** (Eclipse Temurin 21 or OpenJDK 21 LTS).
- **Maven 3.9+** (or use `./mvnw`).
- **HAProxy 2.8+** with **DataPlane API v3** running locally or in Docker.
- **IDE**: IntelliJ IDEA, Eclipse, or VS Code with:
  - **Lombok Plugin** installed and *Annotation Processing* enabled.
  - **Google Java Format** plugin recommended.

### IDE Annotation Processing Setup
In IntelliJ IDEA:
- Navigate to **Settings > Build, Execution, Deployment > Compiler > Annotation Processors**.
- Check **Enable annotation processing**.

In Eclipse:
- Install m2e-apt and enable project annotation processing.

---

## 3. Package Structure & Code Navigation

```
src/main/java/com/nnp/haproxy/
├── config/
│   ├── HaproxyFeignConfig.java         # BasicAuth RequestInterceptor for DataPlane API
│   ├── OpenApiConfig.java              # Swagger 3.0 / OpenAPI documentation bean
│   └── WebClientConfig.java            # Reactive WebClient for Kubernetes API
├── controller/
│   └── HAProxyController.java          # REST API endpoints (/register, /deregister, /sync, /reload)
├── exception/
│   └── GlobalExceptionHandler.java     # Centralized @RestControllerAdvice error handling
├── feign/
│   ├── HAProxyFeignClient.java         # Declarative OpenFeign client for DataPlane API v3
│   └── model/                          # DataPlane API v3 DTO models
│       ├── ACL.java
│       ├── ACLList.java
│       ├── Backend.java
│       ├── BackendServer.java
│       ├── BESwitchRule.java
│       ├── BESwitchRuleList.java
│       ├── Frontend.java
│       ├── HTTPRequestRule.java
│       ├── Reload.java
│       └── Transaction.java
├── model/
│   ├── ApiResponse.java                # Standard API response wrapper
│   ├── BackendType.java                # Enum for routing patterns (K8S_DNS, EXTERNAL_IP, etc.)
│   └── HAProxyIn.java                  # Registration request DTO
├── service/
│   ├── HAProxyConfigParser.java        # haproxy.cfg file parser & inventory analyzer
│   ├── HAProxyService.java             # Core business logic & transaction orchestration
│   └── K8SService.java                 # Kubernetes pod running status pre-validation
└── HaproxyIntegrationApplication.java # Spring Boot entry point
```

---

## 4. Coding Standards & Best Practices

### Spring Cloud OpenFeign Conventions
- Outbound endpoints must be declared in `HAProxyFeignClient`.
- Always supply fallback defaults in `@FeignClient` annotations:
  ```java
  @FeignClient(
          name = "${haproxy.feign.name:haproxy-data-plane}",
          url  = "${haproxy.feign.url:http://localhost:5555}${feign.url.api:/v3/services/haproxy}",
          configuration = HaproxyFeignConfig.class
  )
  ```
- All query parameters in Feign methods must be explicitly named (e.g., `@RequestParam("transaction_id") String trnId`).

### HAProxy DataPlane Transaction Safety
- Always wrap mutations in `try / finally` transaction blocks:
  ```java
  Transaction txn = proxyClient.createTransaction(version).getBody();
  String trnId = txn.getId();
  try {
      // 1. Stage changes in transaction
      proxyClient.addBackend(trnId, backend);
      proxyClient.addBEServer(trnId, backendName, server);
      proxyClient.addFrontendACL(trnId, frontendName, acl);
      proxyClient.addFrontendRule(trnId, frontendName, rule);

      // 2. Commit transaction
      proxyClient.commitTransaction(trnId, true);
  } catch (Exception e) {
      // 3. Cleanly abort dangling transaction on failure
      proxyClient.deleteTransaction(trnId);
      throw e;
  }
  ```

### Two-Way Configuration Reconciliation
- The DataPlane API model can drift if administrators edit `/etc/haproxy/haproxy.cfg` directly.
- The service performs a re-import (`postRawConfig`) or manual parsing and reconciliation before transactions to safeguard on-disk configurations.
- Any new backend patterns added to `HAProxyService` must be reflected in `HAProxyConfigParser`.

### Asynchronous Operations & Status Tracking
- Long-running operations such as registration and reload polling run via `CompletableFuture.runAsync()`.
- Results are recorded in an in-memory concurrent map and exposed via `/register-status/{compName}`.
- Keep in-memory status clean and provide informative failure reasons for debugging.

### Exception Handling & Validation
- Validation logic is implemented in `HAProxyController.validateRegister()`:
  - `compName`, `parentFE`, and `backendType` are mandatory.
  - `EXTERNAL_IP` backends require `serverAddress` and `serverPort`.
  - `PATH_REWRITE` requires `pathPrefix`.
- Handled errors must map to standard HTTP status codes (`400 Bad Request`, `404 Not Found`, `409 Conflict`, `500 Internal Server Error`) wrapped in `ApiResponse<T>`.

### Logging & Sensitive Data Masking
- Never log passwords, auth headers, or raw Kubernetes bearer tokens.
- Use parameterized SLF4J logging:
  ```java
  log.info("[Register] Successfully registered component '{}' in HAProxy.", compName);
  ```

---

## 5. Security, Code Quality & Compliance Tooling

This project integrates automated security and compliance plugins in Maven:

### SAST: SpotBugs & FindSecBugs
Static Analysis Security Testing scans byte-code for security defects:
```bash
./mvnw spotbugs:check
```
Exclusions for known benign patterns are configured in `spotbugs-exclude.xml`.

### SCA: OWASP Dependency-Check
Software Composition Analysis scans third-party dependencies for known CVEs:
```bash
./mvnw dependency-check:check
```
Builds fail if any dependency exceeds the CVSS threshold of 7.0.

### SBOM: CycloneDX Aggregate Generation
Generates a complete Software Bill of Materials in CycloneDX JSON format:
```bash
./mvnw cyclonedx:makeAggregateBom
```
Outputs `target/bom.json` documenting all transitive dependencies and licenses.

### Checkstyle: Google Java Style
Enforces standardized code formatting:
```bash
./mvnw checkstyle:check
```

---

## 6. Git Workflow & Branching Strategy

We follow the standard GitHub Flow branching strategy:
- `main` / `master`: Production-ready, deployable codebase.
- `develop`: Integration branch for upcoming releases.
- Topic branches: `feature/*`, `fix/*`, `chore/*`, `docs/*`.

### Branch Naming Conventions
- `feature/k8s-dns-resolvers`
- `fix/transaction-leak-on-timeout`
- `docs/add-deployment-manual`

### Conventional Commits
Commit messages must follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:
- `feat(service): add dynamic DNS resolvers configuration`
- `fix(feign): handle null reload ID on transaction commit`
- `docs(readme): update quickstart curl examples`
- `refactor(config): externalize all hardcoded properties to env vars`

---

## 7. Pull Request (PR) Checklist

Before submitting a Pull Request, verify the following:
- [ ] Code compiles without errors: `./mvnw clean test-compile`
- [ ] All unit and mock tests pass: `./mvnw clean test`
- [ ] SpotBugs check passes: `./mvnw spotbugs:check`
- [ ] Zero hardcoded credentials, IPs, or proprietary internal tokens.
- [ ] `.env.example` updated if any new configuration properties were introduced.
- [ ] Documentation updated (`README.md`, `DEVELOPMENT_GUIDELINES.md`, `USER_MANUAL_AND_DEPLOYMENT_GUIDE.md`).

---

## 8. Release Lifecycle & Versioning

This project follows **Semantic Versioning 2.0.0** (`MAJOR.MINOR.PATCH`):
- **MAJOR**: Breaking changes to the REST API or HAProxy DataPlane contract.
- **MINOR**: Backward-compatible new features (e.g., new `BackendType`).
- **PATCH**: Backward-compatible bug fixes and security updates.

Releases are published to GitHub Packages automatically via GitHub Actions upon tagging:
```bash
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```
