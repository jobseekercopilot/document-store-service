# Security

Do not disclose vulnerabilities in a public issue.

Use a private GitHub Security Advisory for this repository and include:

- affected commit and component;
- reproduction steps using synthetic data only;
- expected and observed behaviour;
- impact, including cross-user or personal-data exposure;
- suggested remediation if known.

Do not attach credentials, access tokens, real CVs, cover letters, prompts,
model responses, exported documents, or browser sessions. Revoke or rotate any
credential that may have been exposed before sharing redacted evidence.

This service is not approved for production or real-user data.

## Implemented request boundary

Every public document and file operation requires either a validated platform
access token or an approved least-privilege service identity. Human ownership
comes only from the access-token subject. Service calls require the matching
`X-Document-Owner` context and use owner predicates at the repository boundary.

Runtime credentials are required for producer, reader and environment-data
roles. They must be distinct, at least 32 bytes, injected and rotated outside
the repository. Never commit or paste their values into issues, pull requests,
Compose files, workflow logs, image layers or shell history.

The full authorization matrix, stable denial behaviour and remaining rollout
dependencies are documented in
[`docs/AUTHORIZATION_BOUNDARY.md`](docs/AUTHORIZATION_BOUNDARY.md).

The producer boundary does not make this service safe for real data. Durable
encrypted storage, migrations, backup/restore, lifecycle/retention controls,
content limits, hostile-file inspection, consumer identity rollout and
integrated cross-user evidence remain required.
