# Document file validation

## Private-beta allow-list

The Document Store accepts only the paths below. A filename or browser MIME
type is a claim, not proof of content.

| Source | DOCX | PDF |
| --- | --- | --- |
| Approved generated-file producer | Allowed after validation | Allowed after validation |
| Legacy user replacement upload | Allowed after validation | Rejected |
| Application-scoped secure upload | Allowed after validation and scan | Allowed after validation and scan |
| Environment-data fixture | Same policy as its declared source | Same policy as its declared source |

All other types are rejected. User replacement PDF is deliberately excluded:
Document Export owns conversion of a validated replacement DOCX into a
generated PDF. Renderer time/memory budgets, font licensing and rendering
quality remain [EXPORT-02](https://github.com/jobseekercopilot/document-export-service/issues/4)
scope.

## Validation and state changes

Validation completes before object storage, metadata creation, version
deactivation or activation. Rejected content therefore creates no file record
and cannot replace the current active file.

The service:

1. checks the declared type, original extension, browser/producer MIME claim
   and configured byte limit;
2. decodes generated Base64 only when its encoded length can fit the decoded
   limit;
3. inspects the complete file in memory using the format rules below;
4. generates `document-<file UUID>.docx` or `document-<file UUID>.pdf` for
   storage, metadata, responses and downloads;
5. writes the validated object before committing metadata, with compensation
   if metadata persistence fails.

Downloads repeat checksum and content validation. A stored object that no
longer passes either check is marked `UNAVAILABLE` and returns a stable `503`;
it is not served.

## Default limits

| Limit | Default | Configuration |
| --- | ---: | --- |
| Multipart file | 10 MiB | `DOCUMENT_STORE_MAXIMUM_FILE_BYTES` |
| Multipart request | 11 MiB | `DOCUMENT_STORE_MAXIMUM_REQUEST_BYTES` |
| Decoded generated file | 10 MiB | `DOCUMENT_STORE_MAXIMUM_FILE_BYTES` |
| DOCX ZIP entries | 256 | `DOCUMENT_STORE_MAXIMUM_DOCX_ENTRIES` |
| One expanded DOCX entry | 10 MiB | `DOCUMENT_STORE_MAXIMUM_DOCX_ENTRY_BYTES` |
| All expanded DOCX entries | 25 MiB | `DOCUMENT_STORE_MAXIMUM_DOCX_EXPANDED_BYTES` |
| DOCX expansion ratio | 100:1 | `DOCUMENT_STORE_MAXIMUM_DOCX_EXPANSION_RATIO` |

All limits must be positive. Infrastructure must review any increase against
application memory, servlet buffering and downstream rendering limits.

## Format rules

DOCX must be a readable ZIP package with unique, traversal-safe entry names,
`[Content_Types].xml`, `word/document.xml` and the standard non-macro main
document content type. Inspection rejects excessive entry counts or expansion,
DTD/entity input, macros, ActiveX, embedded/OLE content, encrypted packages,
external relationships and imported `altChunk`/object/control content.

PDF must have a supported `%PDF-1.0` through `%PDF-1.7` or `%PDF-2.0` header
and a terminal `%%EOF`. The private-beta generated-file path rejects obvious
encryption, JavaScript, launch actions, embedded files and automatic actions.
PDF is accepted from the generated producer path or the producer-only secure
application-upload path. The legacy browser replacement route remains DOCX
only.

Filenames reject blank or overlong values, Unicode normalization changes,
control/format characters, path separators, drive/alternate-stream separators
and extension/type mismatch. Caller filenames are never returned as download
names.

The canonical exact-artifact route binds both the document-version UUID and
artifact UUID. The pair, authenticated owner, parent retention state and
artifact availability must all match. `AVAILABLE` artifacts on `AVAILABLE` or
`ARCHIVED` parents are eligible whether or not the artifact is active. Deleted
parents and every non-`AVAILABLE` artifact status are non-enumerating denials.
The legacy artifact-ID route applies the same retained-artifact rules for
backward compatibility.

A successful download is a read: it does not activate an artifact, move a
family current pointer, create a version or emit lifecycle state. Size,
SHA-256 and structural/type checks still run before bytes are returned. A
failed integrity or safety check follows the established quarantine policy by
marking the artifact inactive and `UNAVAILABLE` before returning `503`.

## Application upload quarantine and malware scanning

The application-scoped upload route stores the exact original under a private
`quarantine/` key before scanning. It uses a scanner interface whose
private-beta implementation speaks the bounded ClamAV `INSTREAM` protocol.
The scanner must return a supported clean verdict and a parseable signature
timestamp no older than the configured maximum. Infected content is rejected;
unavailable, stale, malformed or timed-out scanner responses fail closed and
never create an approved version.

After a clean verdict, PDFBox or the bounded DOCX XML reader extracts text with
a timeout, page/entry/expanded-byte and character limit. Image-only PDFs are
recorded truthfully as `NO_TEXT`. The service creates a draft, stores the exact
original artifact, then atomically records the separate hashes and approves
the document with the upload operation as `READY`. Cleanup removes quarantine
objects immediately where possible and a bounded reconciler retries retained
quarantine references or fails stale processing operations closed.

The private-beta defaults are 100 retained families, 20 versions per family,
500 MiB of retained uploaded originals per owner and 20 upload attempts per
60-second owner window.

## Scanner decision and residual risk

No paid malware-scanning service is authorised for the private beta. The
approved implementation is pinned containerised ClamAV behind the engine
interface plus the narrow type/source allow-list and bounded structural
inspection. This is not a claim of complete malware detection or safe rendering
of arbitrary documents.

Production access must remain private and owner scoped. Operators should mark
a suspect record unavailable, preserve only approved synthetic incident
evidence, rotate affected credentials when exposure is possible, and follow
the storage incident procedure in
[`STORAGE_OPERATIONS.md`](STORAGE_OPERATIONS.md). Selecting a hosted scanner
later requires an explicit privacy, retention, regional-processing,
availability and cost decision; the engine interface is the extension point
and no hosted provider is selected here.

## User-visible outcomes

- `400`: unsupported type, unsafe filename, mismatched MIME/extension,
  malformed/corrupt content, active/encrypted content, or invalid archive.
- `413`: configured file, request, entry or expansion limit exceeded.
- `503`: a previously stored object fails checksum or safety revalidation.

Messages intentionally describe the failed rule without returning file bytes,
archive entries, object keys or internal parser details.
