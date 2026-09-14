/*
 * IpHasher.java — one-way, salted SHA-256 digest of a client address
 *
 * Layer: analytics.recording. The only place in the application, besides the redirect controller
 * that reads the request, where a raw client address is referenced (ArchitectureTest rule). Turns
 * the address into the 64-character lowercase hex digest stored in click_outbox.hashed_ip so the
 * raw IP is never persisted, logged or forwarded (AC-10, AC-11). Depends on AnalyticsProperties
 * for the static salt only.
 */
package com.example.shortener.analytics.recording;

import com.example.shortener.analytics.config.AnalyticsProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Computes {@code hex(SHA-256(salt + clientIp))} for the click recorder (AC-10, AC-11).
 *
 * <p><b>Responsibility.</b> Pseudonymise the client address with a configured static salt so that
 * equal addresses map to equal digests (unique-visitor counting stays possible) while the digest
 * cannot be reversed without the salt. The concatenation order is fixed: salt first, then the
 * address, UTF-8 encoded, with no separator.
 *
 * <p><b>Invariants.</b> The result is always exactly 64 lowercase hexadecimal characters, which is
 * what {@code ClickOutboxEntry} and {@code click_outbox_hashed_ip_chk} require. A {@code null} or
 * blank address is hashed as the empty string so that a request without a resolvable peer address
 * still produces a valid, stable digest. The raw address never appears in a log line, an exception
 * message or {@link #toString()}.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; a fresh {@link MessageDigest} is
 * created per call because digests are not thread-safe.
 */
@Component
public class IpHasher {

  /** Digest algorithm; mandated by the data model (64 hex characters). */
  static final String ALGORITHM = "SHA-256";

  /** Salt bytes, captured once at construction so the properties object is not kept. */
  private final byte[] saltBytes;

  /**
   * Creates the hasher from the validated analytics configuration.
   *
   * @param properties bound {@code analytics.*} settings; only {@code salt} is used
   * @throws NullPointerException when {@code properties} or its salt is {@code null}
   */
  @Autowired
  public IpHasher(AnalyticsProperties properties) {
    this(Objects.requireNonNull(properties, "properties").salt());
  }

  /**
   * Creates the hasher from an explicit salt (tests, or callers without the properties bean).
   *
   * @param salt the static secret prepended to every address before hashing
   * @throws NullPointerException when {@code salt} is {@code null}
   * @throws IllegalArgumentException when {@code salt} is blank
   */
  public IpHasher(String salt) {
    Objects.requireNonNull(salt, "salt");
    if (salt.isBlank()) {
      throw new IllegalArgumentException("salt must not be blank");
    }
    this.saltBytes = salt.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Hashes a client address.
   *
   * @param clientIp the raw peer address as reported by the servlet container; {@code null} or
   *     blank is treated as the empty string
   * @return {@code hex(SHA-256(salt + clientIp))}, 64 lowercase hex characters
   */
  public String hash(String clientIp) {
    String address = clientIp == null ? "" : clientIp.strip();
    MessageDigest digest = newDigest();
    digest.update(saltBytes);
    digest.update(address.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Never renders the salt. */
  @Override
  public String toString() {
    return "IpHasher{algorithm=" + ALGORITHM + "}";
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(ALGORITHM);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every Java platform implementation; this cannot happen.
      throw new IllegalStateException(ALGORITHM + " is not available", e);
    }
  }
}
