# ADR-013: Blocking Spring MVC, not WebFlux

- **Status**: Accepted
- **Context**: The redirect path is latency-sensitive; persistence uses JPA and Flyway.
- **Decision**: Use the servlet stack (Spring MVC) with blocking JPA/Redis clients.
- **Rejected alternative**: WebFlux — JPA and Flyway are blocking, and redirect latency is dominated by one cache lookup.
- **Consequence**: Simpler code and testing; horizontal scaling of read instances is the throughput lever.
