<!--
SYNC IMPACT REPORT
==================
Initial constitution template. Project governance principles live here.
Replace the placeholder sections below with your project's actual constitution.
-->

# Project Constitution

This document establishes the binding governance principles for this project.
All planning, specification, and implementation work MUST be checked against
these principles before completion.

## Core Principles

### I. Code Quality

- **Readability**: Code MUST be self-documenting with clear naming conventions.
- **Maintainability**: Functions MUST have a single responsibility. Files MUST
  NOT exceed 300 lines without justification.
- **Consistency**: All code MUST follow the project's style guide. Linting
  MUST pass with zero warnings before merge.
- **No Dead Code**: Unused imports, variables, and functions MUST be removed.

### II. Testing Standards

- **Coverage Threshold**: New code MUST have minimum 80% line coverage.
  Critical paths MUST have 100% coverage.
- **Test Types Required**: Unit tests, integration tests, contract tests.
- **CI Gate**: All tests MUST pass before merge.

### III. User Experience Consistency

- **Error Handling**: All errors MUST provide clear, actionable messages.
- **Internationalization**: User-facing strings MUST be externalized.
- **Accessibility**: UI MUST meet WCAG 2.1 AA minimum.

### IV. Performance & Reliability

- **Latency**: p95 latency MUST stay below 200ms for interactive operations.
- **Availability**: Production services MUST target 99.9% uptime.
- **Observability**: All services MUST emit structured logs and metrics.

### V. Security

- **Input Validation**: All user input MUST be validated at the trust boundary.
- **Secrets Management**: No secrets in source. Use the project's secret store.
- **Dependency Audit**: Dependencies MUST pass `npm audit` / `cargo audit` /
  equivalent with zero high-severity findings.

## Governance

This constitution supersedes ad-hoc convention where they conflict.

- **Authority**: The principles above are binding gates. The Constitution
  Check section of `design.md` MUST be evaluated against these principles.
- **Amendments**: Changes to this document require a PR with rationale and
  maintainer approval.
- **Versioning**: This document follows SemVer for governance:
  MAJOR = backward-incompatible governance change;
  MINOR = new principle or section;
  PATCH = clarification or refinement.

**Version**: 1.0.0 | **Ratified**: [DATE] | **Last Amended**: [DATE]