# ADR-009: Versioned write path, unversioned redirect path

- **Status**: Accepted
- **Context**: The API needs a version prefix, but short links must stay short.
- **Decision**: Write endpoint is `/api/v1/urls`; redirects stay at the bare root `/{short_code}`.
- **Rejected alternative**: Version the redirect path too — every extra path segment lengthens every shared link.
- **Consequence**: Root path is reserved for short codes; future root-level routes must not collide with the code charset.
