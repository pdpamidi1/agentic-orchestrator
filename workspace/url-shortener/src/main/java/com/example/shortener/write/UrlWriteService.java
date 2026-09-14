/*
 * UrlWriteService.java — creates a short link: validate, allocate or accept the code, persist
 *
 * Layer: write. The application service behind POST /api/v1/urls. Re-checks the request
 * semantically (absolute http/https URL with a host, length, alias charset, expiry in the future)
 * as defence in depth over the DTO's Bean Validation, takes the short code either verbatim from
 * custom_alias or from ShortCodeAllocator (batched Redis counter with DB-sequence fallback,
 * AC-10/AC-11), inserts the UrlMapping row in one transaction and maps a duplicate key to
 * AliasAlreadyExistsException (409, AC-3). Identical long URLs are never deduplicated (AC-4).
 * Framework-web-free; only UrlWriteController carries web annotations.
 */
package com.example.shortener.write;

import com.example.shortener.api.dto.CreateUrlRequest;
import com.example.shortener.api.dto.CreateUrlResponse;
import com.example.shortener.api.error.AliasAlreadyExistsException;
import com.example.shortener.api.error.InvalidUrlException;
import com.example.shortener.codegen.AllocatedCode;
import com.example.shortener.codegen.ShortCodeAllocator;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.domain.UrlMappingRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write path of the shortener: turns a validated {@link CreateUrlRequest} into a persisted {@code
 * urls} row and the {@link CreateUrlResponse} describing it (AC-1, AC-3, AC-4, AC-10, AC-11).
 *
 * <ul>
 *   <li>When {@code custom_alias} is supplied it becomes the {@code short_code}; otherwise a base62
 *       code is drawn from {@link ShortCodeAllocator}, which decides between the Redis counter and
 *       the database sequence and reports the {@link CodeSource} that is persisted.
 *   <li>A taken alias / short code is rejected twice: by a pre-check on the repository and, should
 *       a concurrent writer win the race, by the primary-key violation raised while flushing the
 *       insert. Both surface as {@link AliasAlreadyExistsException} (409) and the existing mapping
 *       is left untouched.
 *   <li>Identical {@code long_url}s are never deduplicated: every request without an alias
 *       allocates a fresh, distinct code (AC-4).
 *   <li>{@code created_by} is always left {@code null} (non-goal: no user attribution).
 *   <li>{@code short_url} is the configured {@code shortener.base-url} plus {@code /} plus the
 *       code.
 * </ul>
 *
 * <p>A mapping created from a custom alias consumed no counter value. Because {@code
 * urls.code_source} is constrained to the two counter names, such rows record {@link
 * CodeSource#REDIS}, the primary source, as their nominal origin.
 *
 * <p>This class carries no web annotations; every non-2xx outcome is expressed as an exception that
 * {@code GlobalExceptionHandler} renders as {@code application/problem+json}.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>Why validate again: the DTO's constraints are regular expressions and cannot tell {@code
 *       https:///no-host} from a real URL, and {@code @Future} uses the wall clock. The checks here
 *       are the authoritative ones, use the injected {@link Clock} and produce stable {@code
 *       detail} strings; they also protect callers that bypass the controller.
 *   <li>Why {@code saveAndFlush} and not {@code save}: the insert must hit Postgres inside this
 *       method so a duplicate key surfaces as {@link DataIntegrityViolationException} here, where
 *       it can be translated into a 409. With a deferred flush the failure would happen at commit,
 *       outside the method, and become a 500.
 *   <li>Why uniqueness is decided by the primary key, not the cache: the read cache is best effort
 *       and may be empty; {@code urls.short_code} is the only authority, which also makes alias
 *       creation independent of a Redis outage.
 *   <li>Why the code is not written to the read cache: the cache is populated lazily on the first
 *       redirect (cache-aside), so the write surface needs no Redis access beyond the counter.
 * </ul>
 *
 * <p>Profile gating: not gated itself; it is only pulled into a context by {@code
 * UrlWriteController} ({@code @Profile("!read")}) and is a plain component on every surface.
 *
 * <p>Invariants: {@code baseUrl} is non-blank and has no trailing slash. Thread-safety: immutable
 * after construction; the repository, allocator and clock are thread-safe, so the singleton is
 * shared by all request threads.
 */
@Service
public class UrlWriteService {

  /**
   * {@code code_source} recorded for rows whose short code is a caller-supplied alias. The column
   * is constrained to the two counter names by {@code urls_code_source_chk}, so an alias, which
   * consumed no counter value, is attributed to the primary counter as its nominal origin.
   */
  static final CodeSource CUSTOM_ALIAS_CODE_SOURCE = CodeSource.REDIS;

  /**
   * Problem {@code detail} for a {@code long_url} that is blank, unparseable, not absolute, not
   * http/https or has no host. Client-facing; names the rule, never internal state.
   */
  static final String DETAIL_INVALID_LONG_URL =
      "long_url must be an absolute http or https URL with a host";

  /**
   * Problem {@code detail} for a {@code long_url} longer than {@link
   * CreateUrlRequest#MAX_LONG_URL_LENGTH} characters.
   */
  static final String DETAIL_LONG_URL_TOO_LONG =
      "long_url must be at most " + CreateUrlRequest.MAX_LONG_URL_LENGTH + " characters";

  /** Problem {@code detail} for a {@code custom_alias} outside {@code [0-9a-zA-Z]{3,32}}. */
  static final String DETAIL_INVALID_ALIAS =
      "custom_alias must be 3 to 32 characters from [0-9a-zA-Z]";

  /** Problem {@code detail} for an {@code expiration_date} at or before the service clock's now. */
  static final String DETAIL_EXPIRY_NOT_IN_FUTURE = "expiration_date must be in the future";

  /**
   * Alias charset and length, compiled once from the same regex the DTO declares so that the two
   * validation layers can never disagree.
   */
  private static final Pattern ALIAS = Pattern.compile(CreateUrlRequest.CUSTOM_ALIAS_PATTERN);

  /** INFO for lost conflict races, DEBUG for successful creations; no request bodies are logged. */
  private static final Logger log = LoggerFactory.getLogger(UrlWriteService.class);

  /** System of record; used for the alias pre-check and the flushing insert. */
  private final UrlMappingRepository repository;

  /** Produces generated codes and reports which counter (Redis or DB sequence) served them. */
  private final ShortCodeAllocator allocator;

  /** Public base URL without trailing slash; {@code short_url = baseUrl + "/" + code}. */
  private final String baseUrl;

  /** Source of {@code created_at} and of the "now" the expiry is compared against. */
  private final Clock clock;

  /**
   * Creates the service from the bound {@code shortener.*} properties and the system UTC clock.
   *
   * <p>This is the constructor Spring uses ({@link Autowired}); it delegates to the explicit one.
   *
   * @param repository the {@code urls} repository
   * @param allocator the short-code allocator (Redis counter with DB-sequence fallback)
   * @param properties bound {@code shortener.*} settings; only {@code baseUrl} is used
   * @throws NullPointerException when any argument is {@code null}
   * @throws IllegalArgumentException when the configured base URL is blank
   */
  @Autowired
  public UrlWriteService(
      UrlMappingRepository repository,
      ShortCodeAllocator allocator,
      ShortenerProperties properties) {
    this(
        repository,
        allocator,
        Objects.requireNonNull(properties, "properties").baseUrl(),
        Clock.systemUTC());
  }

  /**
   * Creates the service with an explicit base URL and clock.
   *
   * @param repository the {@code urls} repository
   * @param allocator the short-code allocator (Redis counter with DB-sequence fallback)
   * @param baseUrl public base URL used to render {@code short_url}; trailing slashes are removed
   * @param clock source of {@code created_at} and of the "now" used to check expiry
   * @throws NullPointerException when any argument is {@code null}
   * @throws IllegalArgumentException when {@code baseUrl} is blank after stripping whitespace and
   *     trailing slashes (for example {@code " / "})
   */
  public UrlWriteService(
      UrlMappingRepository repository, ShortCodeAllocator allocator, String baseUrl, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.allocator = Objects.requireNonNull(allocator, "allocator");
    this.baseUrl = stripTrailingSlashes(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.clock = Objects.requireNonNull(clock, "clock");
    if (this.baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
  }

  /**
   * The base URL (without trailing slash) that short links are built from.
   *
   * @return the normalised base URL, for example {@code https://sho.rt}
   */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Creates a new short link.
   *
   * <p>Steps, in order: semantic URL check; expiry check against {@code clock.instant()}; short
   * code from the alias (pattern + availability pre-check) or from the allocator; {@code
   * saveAndFlush} of the new {@link UrlMapping}; response rendering. All checks run before any
   * counter value is drawn, so a rejected request costs no code (a lost flush race still costs one,
   * an accepted gap, see {@code docs/operations.md}).
   *
   * <p>Transaction: the whole method is one transaction. Any exception rolls it back, so a 400 or
   * 409 never leaves a row behind. A failure of both counters propagates unchanged and becomes a
   * 500 in the exception handler with nothing persisted.
   *
   * @param request the validated request body
   * @return the persisted mapping rendered as the 201 response body
   * @throws InvalidUrlException when a semantic rule not covered by Bean Validation fails (400)
   * @throws AliasAlreadyExistsException when the alias / short code is already taken (409)
   * @throws NullPointerException when {@code request} is {@code null}
   */
  @Transactional
  public CreateUrlResponse create(CreateUrlRequest request) {
    Objects.requireNonNull(request, "request");
    String longUrl = requireValidLongUrl(request.longUrl());
    Instant now = clock.instant();
    Instant expiresAt = requireFutureExpiry(request.expirationDate(), now);

    String shortCode;
    CodeSource codeSource;
    if (request.customAlias() != null) {
      // Aliases draw from no counter, so they keep working during a Redis outage; uniqueness is
      // enforced by the primary key below, not by the cache.
      shortCode = requireAvailableAlias(request.customAlias());
      codeSource = CUSTOM_ALIAS_CODE_SOURCE;
    } else {
      // The allocator hides the Redis-vs-sequence decision; codeSource records which one won.
      AllocatedCode allocated = allocator.allocate();
      shortCode = allocated.code();
      codeSource = allocated.codeSource();
    }

    UrlMapping mapping = new UrlMapping(shortCode, longUrl, now, expiresAt, codeSource);
    try {
      repository.saveAndFlush(mapping);
    } catch (DataIntegrityViolationException raceLost) {
      // A concurrent writer inserted the same short code between the pre-check and the flush; the
      // transaction rolls back and the existing row is left untouched (AC-3).
      // The driver message (constraint name etc.) is deliberately not forwarded to the client.
      log.info("Short code '{}' was taken concurrently; rejecting as conflict", shortCode);
      throw new AliasAlreadyExistsException(shortCode);
    }
    log.debug("Created short code '{}' from {}", shortCode, codeSource.wireValue());
    return new CreateUrlResponse(
        shortUrl(shortCode), shortCode, longUrl, expiresAt, codeSource.wireValue());
  }

  /**
   * Renders the public short link for a code: {@code <base-url>/<code>}.
   *
   * @param shortCode the generated code or custom alias
   * @return the absolute short URL, for example {@code http://localhost:8080/promo2024}
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  public String shortUrl(String shortCode) {
    return baseUrl + "/" + Objects.requireNonNull(shortCode, "shortCode");
  }

  // ---------------------------------------------------------------------------------------------
  // Semantic checks (defence in depth on top of the DTO's Bean Validation constraints)
  // ---------------------------------------------------------------------------------------------

  /**
   * Accepts a {@code long_url} only when it is non-blank, at most {@link
   * CreateUrlRequest#MAX_LONG_URL_LENGTH} characters, parseable as a {@link URI}, absolute, has an
   * {@code http} or {@code https} scheme (case-insensitive) and a non-blank host.
   *
   * <p>The value is returned unchanged: no normalisation of scheme or host case, no re-encoding, so
   * the stored and later redirected {@code long_url} is exactly what the client sent.
   *
   * @param longUrl the raw {@code long_url} from the request, possibly {@code null}
   * @return {@code longUrl} unchanged
   * @throws InvalidUrlException with {@link #DETAIL_LONG_URL_TOO_LONG} when too long, otherwise
   *     with {@link #DETAIL_INVALID_LONG_URL} (the parse failure, if any, is attached as cause but
   *     never exposed)
   */
  private static String requireValidLongUrl(String longUrl) {
    if (longUrl == null || longUrl.isBlank()) {
      throw new InvalidUrlException(DETAIL_INVALID_LONG_URL);
    }
    if (longUrl.length() > CreateUrlRequest.MAX_LONG_URL_LENGTH) {
      throw new InvalidUrlException(DETAIL_LONG_URL_TOO_LONG);
    }
    URI uri;
    try {
      uri = new URI(longUrl);
    } catch (URISyntaxException e) {
      throw new InvalidUrlException(DETAIL_INVALID_LONG_URL, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean httpScheme = scheme.equals("http") || scheme.equals("https");
    // getHost() is null for "https:///path" and for authorities java.net.URI cannot parse as a
    // host; that is what catches host-less URLs the DTO's ^https?://.+ regex lets through.
    if (!httpScheme || !uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
      throw new InvalidUrlException(DETAIL_INVALID_LONG_URL);
    }
    return longUrl;
  }

  /**
   * Converts an optional {@code expiration_date} to the {@link Instant} stored in {@code
   * expires_at}, requiring it to lie strictly after {@code now}.
   *
   * <p>"Exactly now" is rejected, mirroring the read side where a mapping expiring exactly now is
   * already expired: a link that would answer 410 on its first redirect is never created.
   *
   * @param expirationDate the offset date-time from the request, or {@code null} for a link that
   *     never expires
   * @param now the service clock's current instant
   * @return the expiry as an instant (offset normalised to UTC), or {@code null} when absent
   * @throws InvalidUrlException with {@link #DETAIL_EXPIRY_NOT_IN_FUTURE} when the expiry is at or
   *     before {@code now}
   */
  private static Instant requireFutureExpiry(OffsetDateTime expirationDate, Instant now) {
    if (expirationDate == null) {
      return null;
    }
    Instant expiresAt = expirationDate.toInstant();
    if (!expiresAt.isAfter(now)) {
      throw new InvalidUrlException(DETAIL_EXPIRY_NOT_IN_FUTURE);
    }
    return expiresAt;
  }

  /**
   * Accepts a {@code custom_alias} only when it matches {@link #ALIAS} and no row with that short
   * code exists yet.
   *
   * <p>The existence check is a fast pre-check that turns the common case into a clean 409 without
   * attempting an insert; it is not the guarantee. Two concurrent requests can both pass it, and
   * the primary key on {@code urls.short_code} then rejects the loser at flush time in {@link
   * #create}. The pattern check comes first so an invalid alias never costs a database round trip.
   *
   * @param alias the caller-supplied alias, never {@code null} here
   * @return {@code alias} unchanged; it is used verbatim as the short code
   * @throws InvalidUrlException with {@link #DETAIL_INVALID_ALIAS} when the alias is malformed
   * @throws AliasAlreadyExistsException when a mapping already occupies the alias (409)
   */
  private String requireAvailableAlias(String alias) {
    if (!ALIAS.matcher(alias).matches()) {
      throw new InvalidUrlException(DETAIL_INVALID_ALIAS);
    }
    if (repository.existsByShortCode(alias)) {
      throw new AliasAlreadyExistsException(alias);
    }
    return alias;
  }

  /**
   * Normalises a configured base URL: strips surrounding whitespace, then every trailing {@code /}
   * so that {@code shortUrl} can always join with exactly one slash ({@code "http://x//"} becomes
   * {@code "http://x"}).
   *
   * @param value the raw base URL, never {@code null}
   * @return the trimmed value without trailing slashes; may be empty (the constructor rejects that)
   */
  private static String stripTrailingSlashes(String value) {
    String result = value.strip();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
