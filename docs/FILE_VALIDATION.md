# Document file validation

## Private-beta allow-list

The Document Store accepts only the paths below. A filename or browser MIME
type is a claim, not proof of content.

| Source | DOCX | PDF |
| --- | --- | --- |
| Approved generated-file producer | Allowed after validation | Allowed after validation |
| User replacement upload | Allowed after validation | Rejected |
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
PDF is accepted only from an approved producer identity; browser replacement
PDF is not accepted.

Filenames reject blank or overlong values, Unicode normalization changes,
control/format characters, path separators, drive/alternate-stream separators
and extension/type mismatch. Caller filenames are never returned as download
names.

## Malware-scanning decision and residual risk

No paid malware-scanning service is authorised for the private beta. The beta
control is a narrow type/source allow-list plus bounded static inspection
before persistence and again before download. This is not a claim of complete
malware detection, a full PDF parser or safe rendering of arbitrary documents.

Production access must remain private and owner scoped. Operators should mark
a suspect record unavailable, preserve only approved synthetic incident
evidence, rotate affected credentials when exposure is possible, and follow
the storage incident procedure in
[`STORAGE_OPERATIONS.md`](STORAGE_OPERATIONS.md). Adding a scanner later
requires an explicit privacy, retention, regional-processing, availability and
cost decision.

## User-visible outcomes

- `400`: unsupported type, unsafe filename, mismatched MIME/extension,
  malformed/corrupt content, active/encrypted content, or invalid archive.
- `413`: configured file, request, entry or expansion limit exceeded.
- `503`: a previously stored object fails checksum or safety revalidation.

Messages intentionally describe the failed rule without returning file bytes,
archive entries, object keys or internal parser details.
