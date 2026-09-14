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
 */
@Service
public class UrlWriteService {

  /** {@code code_source} recorded for rows whose short code is a caller-supplied alias. */
  static final CodeSource CUSTOM_ALIAS_CODE_SOURCE = CodeSource.REDIS;

  static final String DETAIL_INVALID_LONG_URL =
      "long_url must be an absolute http or https URL with a host";
  static final String DETAIL_LONG_URL_TOO_LONG =
      "long_url must be at most " + CreateUrlRequest.MAX_LONG_URL_LENGTH + " characters";
  static final String DETAIL_INVALID_ALIAS =
      "custom_alias must be 3 to 32 characters from [0-9a-zA-Z]";
  static final String DETAIL_EXPIRY_NOT_IN_FUTURE = "expiration_date must be in the future";

  private static final Pattern ALIAS = Pattern.compile(CreateUrlRequest.CUSTOM_ALIAS_PATTERN);

  private static final Logger log = LoggerFactory.getLogger(UrlWriteService.class);

  private final UrlMappingRepository repository;
  private final ShortCodeAllocator allocator;
  private final String baseUrl;
  private final Clock clock;

  /** Creates the service from the bound {@code shortener.*} properties and the system UTC clock. */
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

  /** The base URL (without trailing slash) that short links are built from. */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Creates a new short link.
   *
   * @param request the validated request body
   * @return the persisted mapping rendered as the 201 response body
   * @throws InvalidUrlException when a semantic rule not covered by Bean Validation fails (400)
   * @throws AliasAlreadyExistsException when the alias / short code is already taken (409)
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
      shortCode = requireAvailableAlias(request.customAlias());
      codeSource = CUSTOM_ALIAS_CODE_SOURCE;
    } else {
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
      log.info("Short code '{}' was taken concurrently; rejecting as conflict", shortCode);
      throw new AliasAlreadyExistsException(shortCode);
    }
    log.debug("Created short code '{}' from {}", shortCode, codeSource.wireValue());
    return new CreateUrlResponse(
        shortUrl(shortCode), shortCode, longUrl, expiresAt, codeSource.wireValue());
  }

  /** Renders the public short link for a code: {@code <base-url>/<code>}. */
  public String shortUrl(String shortCode) {
    return baseUrl + "/" + Objects.requireNonNull(shortCode, "shortCode");
  }

  // ---------------------------------------------------------------------------------------------
  // Semantic checks (defence in depth on top of the DTO's Bean Validation constraints)
  // ---------------------------------------------------------------------------------------------

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
    if (!httpScheme || !uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
      throw new InvalidUrlException(DETAIL_INVALID_LONG_URL);
    }
    return longUrl;
  }

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

  private String requireAvailableAlias(String alias) {
    if (!ALIAS.matcher(alias).matches()) {
      throw new InvalidUrlException(DETAIL_INVALID_ALIAS);
    }
    if (repository.existsByShortCode(alias)) {
      throw new AliasAlreadyExistsException(alias);
    }
    return alias;
  }

  private static String stripTrailingSlashes(String value) {
    String result = value.strip();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
