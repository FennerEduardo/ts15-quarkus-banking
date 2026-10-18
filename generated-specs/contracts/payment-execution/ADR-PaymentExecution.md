# ADR 001: Architecture Decisions for Ejecución de pagos entre cuentas (BIAN Payment Execution)

## Status
Accepted

## Context
Project requiring structured implementation matching Gherkin specification.

## Decisions
- **Architecture Style**: Hexagonal Architecture (Ports & Adapters) (hexagonal)
- **Primary Backend Language**: java
- **Backend Framework**: quarkus (Java 21 (Quarkus 3.40.1 LTS))
- **ORM / Persistence**: jdbc (JDBC (runtime kernel) on io.quarkus:quarkus-agroal)
- **Validation**: jakarta-validation (jakarta.validation:jakarta.validation-api:3.0.2)
- **Authentication**: jwt-bcrypt (bcrypt cost factor 12, JWT TTL 3600s)
- **Backend Testing Framework**: junit (org.junit.jupiter:junit-jupiter:5.10.2)

## Prohibited Layer Dependencies
Domain core must NOT import:
- `express`
- `@nestjs/common`
- `prisma`
- `typeorm`
- `axios`
- `org.springframework.*`
- `java.sql.Statement without PreparedStatement`

## Industry Standards (Banking & Financial Services)
- **Standards**: BIAN Service Landscape <https://bian.org/servicelandscape/>; ISO 20022 Financial Services Messaging <https://www.iso20022.org/>
- **Canonical model**: BIAN Service Domains (control records, service operations) + ISO 20022 Business Model
- **Classification**: @bian:PaymentExecution; @iso20022:pacs.008; @capability:L3
- MUST: Map each feature to exactly one BIAN Service Domain (@bian:<ServiceDomain>); an internal API exposes operations of that single domain only.
- MUST: Use BIAN service operations for commands (Initiate, Update, Execute, Request, Retrieve, Control, Exchange, Notify) and the domain control record as the aggregate.
- MUST: Model payment data with ISO 20022 terms and types: Debtor/Creditor, amounts as decimal + ISO 4217 currency, never floating point.
- MUST: Monetary commands require an Idempotency-Key and must emit their events through the transactional outbox.
- MUST: Separate Inner APIs (single service domain, canonical model) from Outer APIs (channel orchestration).
- MUST: Never persist or log full card numbers (PAN), CVV or credentials; mask account identifiers in logs.

Compliance items are engineering guidance, not certification.
