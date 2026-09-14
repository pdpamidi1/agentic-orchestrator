# ADR-004: Counter seeded at 62^3

- **Status**: Accepted (AMB-11 default, pending confirmation)
- **Context**: Custom aliases must be 3–32 chars; raw counter values would produce 1–2 char generated codes initially.
- **Decision**: Offset every counter value by 238328 so generated base62 codes are at least 3 characters.
- **Rejected alternative**: Allow 1–2 character generated codes — would split the code format from the alias rule and complicate validation.
- **Consequence**: One uniform `^[0-9a-zA-Z]{3,32}$` code format; ~238k short codes are never issued.

## Implementation note (post-delivery correction)

The design proposed 62^3 = 238328. The delivered code seeds the counter at **62^5 = 916132832**
(`shortener.counter-seed-offset` in `application.yml`, `ShortenerProperties`), so every generated code is at
least **6** characters (the first code issued is `100001`). The implementation agent recorded this as an explicit
assumption when the AMB-11 answer text was not available to it; the longer minimum was kept because it leaves
room for ~56 billion codes before the length grows and keeps generated codes visually distinct from short
custom aliases. This file is the source of truth; the one-liner in DESIGN.md carries the same correction.
