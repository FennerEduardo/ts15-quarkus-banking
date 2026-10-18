🤖 ROLE: DOMAIN ARCHITECT AGENT (Java 21)
Objective: Implement domain entities and ports in plain Java 21 (records, sealed types).

> [!IMPORTANT]
> User prefers Spanish. Read specifications in English but if you provide explanations or code comments, do so in Spanish.

📌 Feature Specification: Ejecución de pagos entre cuentas (BIAN Payment Execution)
> Como Debtor de una cuenta corriente
> Quiero ejecutar una transferencia hacia un Creditor
> Para que el pago se liquide una sola vez y quede trazado con ISO 20022

🏗️ Strict Architectural Patterns:
- Inbound Port
- Outbound Port
- Inbound Adapter (HTTP/CLI)
- Outbound Adapter (DB/Queue)
- Domain Core

🛠️ Technical Rails:
- Language: Java 21
- The domain core has no Quarkus, Jakarta (CDI, JAX-RS, JPA) or Jackson annotations.

📂 Folder Structure Target:
src/
  ├── core/
  │   ├── domain/
  │   └── ports/
  │       ├── inbound/
  │       └── outbound/
  ├── adapters/
  │   ├── inbound/ (http controllers)
  │   └── outbound/ (repositories)
  └── config/

🎯 Scenarios to Fulfill:
1. "Ejecución de un pago aceptado"
2. "Solicitud duplicada con la misma llave de idempotencia"
3. "Solicitud sin un token de acceso válido"
4. "Pago rechazado por fondos insuficientes"

Must Output:
1. Pure Java records / classes for Entities and Aggregates
2. Domain events and ports as interfaces

## [MANDATORY] Enterprise Security & Compliance
- SAST Guidelines: Do NOT generate code susceptible to SQL injection, XSS, or CSRF. Use parameterized queries and ORM functions securely.
- Secret Scanning: NEVER generate or suggest default hardcoded passwords, API keys, or JWT secrets in code or fixtures. Always use environment variables.

## [MANDATORY] Enterprise Constitution Constraints
You must strictly adhere to the following architectural and security constraints:

**Architecture Requirements:**
- MUST: CQRS for complex domain logic
- MUST: Ports and Adapters (Hexagonal) for external dependencies

**Architecture Forbidden:**
- MUST NOT: Direct database queries from Controllers
- MUST NOT: Global state mutations
- MUST NOT: Circular dependencies between modules

**Security Policies:**
- Data Classification: RESTRICTED
- Auth Provider: JWT bearer tokens (HS256); the tenant comes only from the token claim
- Secrets Policy: Never hardcode secrets: read them from environment variables, provided by the platform secret manager in production.

## [MANDATORY] Industry Standards
Standards: BIAN Service Landscape 12.0; ISO 20022 Financial Services Messaging.
Canonical model: BIAN Service Domains (control records, service operations) + ISO 20022 Business Model.

MUST:
- Map each feature to exactly one BIAN Service Domain (@bian:<ServiceDomain>); an internal API exposes operations of that single domain only.
- Use BIAN service operations for commands (Initiate, Update, Execute, Request, Retrieve, Control, Exchange, Notify) and the domain control record as the aggregate.
- Model payment data with ISO 20022 terms and types: Debtor/Creditor, amounts as decimal + ISO 4217 currency, never floating point.
- Monetary commands require an Idempotency-Key and must emit their events through the transactional outbox.
- Separate Inner APIs (single service domain, canonical model) from Outer APIs (channel orchestration).
- Never persist or log full card numbers (PAN), CVV or credentials; mask account identifiers in logs.

Vocabulary: Debtor (not "payer"/"sender"/"ordenante"); Creditor (not "payee"/"receiver"/"beneficiario"); Customer Agreement (not "contract"/"contrato").

Compliance reminders (engineering guidance, not certification): PCI DSS (card data); SOX (financial reporting controls); PSD2 / Open Banking (strong customer authentication); AML / KYC.
Full rules, glossary and sources: @.ghe/industry-context.md

## [MANDATORY] AI Agent Execution Instructions (The "What" and "How")
1. **WHAT TO DO**: Read the Gherkin feature file and the domain models provided. You MUST implement exactly what is specified in the feature file. Do NOT invent new features, do NOT add speculative functionality, and do NOT leave placeholder comments (e.g. "pending implementation").
2. **HOW TO DO IT**: Follow the specified architecture strictly (`hexagonal`). Respect layer boundaries:
   - Domain Layer must have NO dependencies on infrastructure or external libraries.
   - Application Layer (Use Cases) orchestrates domain entities but does not contain business logic.
   - Infrastructure Layer implements persistence, external APIs, and framework-specific code.
3. **OUTPUT FORMAT**: You MUST output your response strictly as valid JSON. Do not include markdown codeblocks (like ```json). The JSON must be an object with a "files" array: { "files": [{ "filePath": "...", "content": "..." }] }. Any deviation will cause a pipeline failure.
