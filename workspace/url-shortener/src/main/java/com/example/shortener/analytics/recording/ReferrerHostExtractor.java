/*
 * ReferrerHostExtractor.java — reduces a Referer header to its host component
 *
 * Layer: analytics.recording. Pure function over the raw header value: only the host is kept
 * (no scheme, port, path, query, fragment or userinfo), so the stored referrer_host carries no
 * per-visit detail. Absent, blank or unparsable headers yield null (AC-1, AC-14).
 */
package com.example.shortener.analytics.recording;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Extracts the host of a {@code Referer} header for {@code click_outbox.referrer_host}.
 *
 * <p><b>Responsibility.</b> Map the header value to a lower-cased host name, or {@code null} when
 * there is nothing trustworthy to store: header absent or blank, not a syntactically valid URI, URI
 * without an authority (relative references, {@code about:blank}, {@code mailto:}), an empty host,
 * or a host longer than the {@value #MAX_HOST_LENGTH}-character column.
 *
 * <p><b>Invariants.</b> Never throws for any input; never returns an empty string; the result
 * contains no {@code /}, {@code ?}, {@code #}, {@code @} or {@code :} except inside a bracketed
 * IPv6 literal, which is returned as {@code java.net.URI} renders it (e.g. {@code [::1]}).
 *
 * <p><b>Thread-safety and lifecycle.</b> Stateless singleton bean; also usable without Spring.
 */
@Component
public class ReferrerHostExtractor {

  /** Width of {@code click_outbox.referrer_host}; longer hosts are not valid DNS names anyway. */
  static final int MAX_HOST_LENGTH = 255;

  /**
   * Returns the host of a referrer URL.
   *
   * @param refererHeader raw {@code Referer} header value, or {@code null} when absent
   * @return the lower-cased host, or {@code null} when absent, blank, unparsable or host-less
   */
  public String extract(String refererHeader) {
    if (refererHeader == null) {
      return null;
    }
    String value = refererHeader.strip();
    if (value.isEmpty()) {
      return null;
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException | IllegalArgumentException unparsable) {
      return null;
    }
    String host = uri.getHost();
    if (host == null || host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
      return null;
    }
    return host.toLowerCase(Locale.ROOT);
  }
}
