# Document Store authorization boundary

Status: producer boundary and DOC-09 retention-administrator isolation
implemented; consumer, policy and deployment rollout remain incomplete.

## Identity sources

Document and file identifiers are locators, never authority.

- A human request uses a platform access token. The service accepts only RS256
  tokens from the configured JWKS with the configured issuer and audience, a
  valid expiry, a nonblank subject and `token_type=access`.
- The authenticated token subject is the document owner. `userId`,
  `X-User-Id`, `X-Document-Owner`, application IDs, document IDs and file IDs
  cannot override it.
- An approved backend uses exactly one `X-Service-Token` and must bind the user
  it is acting for in exactly one `X-Document-Owner` header. The owner header
  has meaning only after the producer, reader or retention-administrator
  credential authenticates.
- Environment-data operations use a separate
  `X-Environment-Data-Token`. They remain disabled outside explicitly allowed
  non-production profiles and are forbidden in production.
- If an `Authorization` header is present, a service token cannot act as a
  fallback for an invalid bearer token.

All three service credentials plus the environment-data credential are runtime
injected, contain at least 32 bytes and must be mutually distinct. Blank, short
or shared values prevent startup.

## Authorization matrix

| Operation | User access token | Producer service | Reader service | Retention admin | Environment data |
| --- | --- | --- | --- | --- | --- |
| Create document text/metadata | Own subject only | Bound owner | Denied | Denied | Denied |
| Read/list document text/metadata | Own subject only | Bound owner | Bound owner | Denied | Denied |
| Archive, restore or soft delete | Own subject only | Bound owner | Denied | Denied | Denied |
| Atomic generated-withdrawal cleanup | Denied | Bound owner | Denied | Denied | Denied |
| Apply/release legal hold | Denied | Denied | Denied | Bound owner | Denied |
| Irreversible guarded purge | Denied | Denied | Denied | Bound owner | Denied |
| Store generated DOCX/PDF bytes | Denied | Bound owner | Denied | Denied | Denied |
| Upload replacement DOCX | Own subject only | Bound owner | Denied | Denied | Denied |
| Read/list/download file metadata or bytes | Own subject only | Bound owner | Bound owner | Denied | Denied |
| Seed, reset or verify synthetic environment data | Denied | Denied | Denied | Denied | Allowed profile only |
| Health | Public | Public | Public | Public | Public |
| API documentation | Authenticated | Authenticated | Authenticated | Authenticated | Denied |

Every repository query used by a public document or file operation includes the
resolved owner. File queries join the file to its owning document. Foreign and
missing document/file UUIDs return the same stable `404` message.

## Logging and denial rules

- Authentication failures return
  `{"code":"AUTHENTICATION_REQUIRED","message":"Valid authentication is required."}`.
- Authenticated callers without the required role receive the stable
  `ACCESS_DENIED` response.
- Foreign and absent records are non-enumerating.
- Application logs use route families such as `/api/v1/documents/**`; they do
  not emit raw owner, application, document or file IDs.
- Document contents, filenames, bearer tokens, service credentials and owner
  headers must never be logged.

## Runtime configuration

| Variable | Consumer |
| --- | --- |
| `AUTH_JWKS_URI` | Document Store access-token verification |
| `DOCUMENT_STORE_JWT_ISSUER` | Document Store access-token verification |
| `DOCUMENT_STORE_JWT_AUDIENCE` | Document Store access-token verification |
| `DOCUMENT_STORE_PRODUCER_TOKEN` | CV/Cover Letter, Document Export and approved orchestration consumers |
| `DOCUMENT_STORE_READER_TOKEN` | Approved read-only backend consumers |
| `DOCUMENT_STORE_RETENTION_ADMIN_TOKEN` | Privacy/legal support automation for hold and guarded purge only |
| `ENVIRONMENT_DATA_TOKEN` | Isolated non-production fixture controller |

Infrastructure owns injection and rotation. Values must not appear in Git,
Compose defaults, image layers, workflow logs or shell history.

## Remaining rollout dependencies

- Document Generation Gateway must send a validated bearer or its approved
  service identity plus owner context on every Store call.
- CV and Cover Letter Service and Document Export must receive only their
  approved Store role and propagate trusted owner context.
- Infrastructure must inject and rotate the credentials and JWT verification
  settings.
- Product/legal/privacy must approve the retention policy before Infrastructure
  can enable maintenance or irreversible purge.
- Integrated tests must prove generation, list, download, upload, replacement,
  archive, restore and deletion for an owner, deny the same operations for a
  second user, and prove retention-admin least privilege.

Until those dependencies and the separate persistence, lifecycle, upload and
retention blockers are complete, the document capability remains disabled for
beta.
