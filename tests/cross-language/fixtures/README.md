# Cross-Language Validation Fixtures

These JSON fixtures are the shared input contract for the Python, Node, and Java
validator adapter tests. Each adapter reads the same files and asserts only the
portable validation outcome: whether the event is valid and which canonical
schema rules fail.

## Fixture format

Each fixture is a JSON object with:

- `purpose`: A concise statement of the behavior the fixture covers.
- `event`: The flat canonical DT3 log event supplied to a language SDK validator.
- `expected`: The portable validation expectation:
  - `valid`: Expected validation result in LENIENT mode.
  - `errors`: Required `{ "field", "rule" }` diagnostic pairs for invalid events.

Adapters must not assert language-specific diagnostic text. Diagnostic message
wording is implementation-specific as long as it remains sanitized and the
field/rule contract is preserved.

## Scope

Fixtures intentionally cover validation inputs only. They do not prescribe
logger-generated timestamps, SDK identity values, transport behavior, masking,
or language-specific exception semantics.
