/*
 * GlobalExceptionHandler.java — Single producer of every non-2xx response (problem+json)
 *
 * Layer: api. A @RestControllerAdvice that extends Spring's ResponseEntityExceptionHandler so
 * that framework exceptions (validation, unreadable body, unknown route, unsupported media type)
 * and the four application exceptions of this package, as well as any unexpected Throwable, all
 * leave the service as RFC 9457 application/problem+json. Depended on by nothing directly; it is
 * picked up by Spring MVC on both deployment surfaces. Enforces the architecture rule "no
 * controller builds an error body" and serves AC-14 / AC-16 and every 4xx/5xx row in openapi.yaml.
 */
package com.example.shortener.api.error;

import java.net.URI;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single producer of every non-2xx response of the API (AC-16).
 *
 * <p>Every error, whether raised by Spring MVC (validation, unreadable body, unsupported media
 * type...), by the application ({@link InvalidUrlException}, {@link ShortCodeNotFoundException},
 * {@link AliasAlreadyExistsException}, {@link ShortCodeExpiredException}) or unexpectedly (any
 * other {@link Throwable}), is rendered as an RFC 9457 {@link ProblemDetail} with {@code
 * Content-Type: application/problem+json} and the fields {@code type}, {@code title}, {@code
 * status}, {@code detail} and {@code instance}. Stack traces, exception class names and other
 * internal identifiers never reach the client; unexpected errors are logged server-side instead.
 *
 * <p><b>How the pieces fit.</b> Three kinds of entry point converge on {@link
 * #createResponseEntity}:
 *
 * <ul>
 *   <li>Overrides of {@link ResponseEntityExceptionHandler} hooks ({@link
 *       #handleMethodArgumentNotValid}, {@link #handleHttpMessageNotReadable}) replace the
 *       superclass body with a problem whose {@code detail} is safe to expose, then hand off to
 *       {@code handleExceptionInternal}, which in turn calls {@code createResponseEntity}.
 *   <li>Superclass handlers that are <em>not</em> overridden (for example {@code
 *       NoResourceFoundException} for a route that the active profile does not register, or {@code
 *       HttpMediaTypeNotSupportedException}) build Spring's default {@code ProblemDetail} with type
 *       {@code about:blank}; {@code createResponseEntity} completes it.
 *   <li>The {@code @ExceptionHandler} methods for the four application exceptions and the catch-all
 *       {@link #handleUnexpected} build a problem directly and call {@link #respond}.
 * </ul>
 *
 * Spring selects the handler whose exception type is closest to the thrown one, so the {@code
 * Throwable} catch-all never shadows a more specific mapping.
 *
 * <p><b>Invariants.</b> Every response carries {@code Content-Type: application/problem+json}, a
 * non-null {@code type} under {@link #PROBLEM_TYPE_BASE}, a non-null {@code title}, and {@code
 * instance} equal to the request URI when the request is servlet-based. Problem {@code detail} only
 * ever contains: a Bean Validation message with the JSON field name, a domain exception message, or
 * one of the two fixed strings {@link #DETAIL_MALFORMED_REQUEST} / {@link #DETAIL_INTERNAL_ERROR}.
 *
 * <p><b>Thread-safety and lifecycle.</b> Stateless singleton Spring bean (the only state is the
 * static logger and constants); safe for concurrent requests. Registered on every profile because
 * it carries no {@code @Profile}.
 *
 * <p><b>Design choice.</b> Centralising rendering here (rather than per controller or via {@code
 * server.error.*} whitelabel pages) is what allows {@code OpenApiContractIT} to assert one error
 * schema for every operation and status, and what keeps {@code application.yml}'s {@code
 * include-message: never} redundant rather than load-bearing.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /**
   * Base URI of the problem {@code type} identifiers. Every {@code type} is this prefix plus a
   * kebab-case slug; the URIs are stable identifiers for clients to switch on and are not
   * dereferenced by the service.
   */
  static final String PROBLEM_TYPE_BASE = "https://example.com/problems/";

  /** {@code type} for 400 caused by Bean Validation failures on the request body. */
  static final URI TYPE_VALIDATION_ERROR = problemType("validation-error");

  /** {@code type} for 400 caused by a missing, unparsable or wrongly typed JSON body. */
  static final URI TYPE_MALFORMED_REQUEST = problemType("malformed-request");

  /** {@code type} for 400 raised by the service layer as {@link InvalidUrlException}. */
  static final URI TYPE_INVALID_URL = problemType("invalid-url");

  /** {@code type} for 404 raised as {@link ShortCodeNotFoundException}. */
  static final URI TYPE_SHORT_CODE_NOT_FOUND = problemType("short-code-not-found");

  /** {@code type} for 409 raised as {@link AliasAlreadyExistsException}. */
  static final URI TYPE_ALIAS_ALREADY_EXISTS = problemType("alias-already-exists");

  /** {@code type} for 410 raised as {@link ShortCodeExpiredException}. */
  static final URI TYPE_SHORT_CODE_EXPIRED = problemType("short-code-expired");

  /** {@code type} for 500: any {@link Throwable} no other handler claimed. */
  static final URI TYPE_INTERNAL_ERROR = problemType("internal-error");

  /**
   * Fixed {@code detail} of every 500. Constant on purpose: the real exception is logged, never
   * described to the client.
   */
  static final String DETAIL_INTERNAL_ERROR =
      "An unexpected error occurred while processing the request";

  /**
   * Fixed {@code detail} of a 400 for an unreadable body. Constant on purpose: Jackson's messages
   * name internal classes and byte offsets.
   */
  static final String DETAIL_MALFORMED_REQUEST =
      "Request body is missing, malformed or contains a value of the wrong type";

  /** Logger for unexpected errors; the only place in this class that logs a stack trace. */
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // ---------------------------------------------------------------------------------------------
  // 400: Bean Validation failures (@Valid on the request body) and unreadable bodies
  // ---------------------------------------------------------------------------------------------

  /**
   * Renders a {@code @Valid} failure on a {@code @RequestBody} as a 400 {@code validation-error}
   * problem whose {@code detail} lists every violated field as {@code <json_name>: <message>},
   * joined by {@code "; "} and sorted by field name so the output is deterministic regardless of
   * validator iteration order (AC-2, AC-5, AC-6).
   *
   * <p>Field names are converted from Java property names to their snake_case JSON names with
   * {@link #jsonName(String)} so clients see the names they sent. Should the binding result carry
   * no field errors (for example only a class-level constraint), the detail falls back to a fixed
   * sentence rather than an empty string.
   *
   * @param ex the validation failure raised by Spring MVC argument resolution
   * @param headers headers proposed by the superclass; passed through unchanged
   * @param status the status proposed by the superclass (400); the method always answers 400
   * @param request the current request, used for {@code instance}
   * @return a 400 response entity carrying a {@link ProblemDetail}
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .sorted(Comparator.comparing(FieldError::getField))
            .map(error -> jsonName(error.getField()) + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));
    if (detail.isEmpty()) {
      detail = "Request validation failed";
    }
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION_ERROR, "Validation failed", detail);
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  /**
   * Renders an unreadable request body (absent body, invalid JSON, a field of the wrong type such
   * as a non-ISO-8601 {@code expiration_date}) as a 400 {@code malformed-request} problem with the
   * fixed {@link #DETAIL_MALFORMED_REQUEST} detail.
   *
   * @param ex the conversion failure; its message is deliberately discarded
   * @param headers headers proposed by the superclass; passed through unchanged
   * @param status the status proposed by the superclass; the method always answers 400
   * @param request the current request, used for {@code instance}
   * @return a 400 response entity carrying a {@link ProblemDetail}
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // The exception message names Jackson classes and JSON locations; never forward it.
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            TYPE_MALFORMED_REQUEST,
            "Malformed request",
            DETAIL_MALFORMED_REQUEST);
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  // ---------------------------------------------------------------------------------------------
  // Application exceptions
  // ---------------------------------------------------------------------------------------------

  /**
   * Maps a semantic input rejection from the service layer to 400 {@code invalid-url}. The
   * exception message is client-facing by contract and becomes the {@code detail}.
   *
   * @param ex the rejection raised by {@code UrlWriteService}
   * @param request the current request, used for {@code instance}
   * @return a 400 problem response
   */
  @ExceptionHandler(InvalidUrlException.class)
  public ResponseEntity<Object> handleInvalidUrl(InvalidUrlException ex, WebRequest request) {
    return respond(
        problem(HttpStatus.BAD_REQUEST, TYPE_INVALID_URL, "Invalid URL", ex.getMessage()), request);
  }

  /**
   * Maps an unknown short code to 404 {@code short-code-not-found} (AC-8).
   *
   * @param ex the lookup miss raised by {@code UrlReadService}
   * @param request the current request, used for {@code instance}
   * @return a 404 problem response whose detail names the unknown code
   */
  @ExceptionHandler(ShortCodeNotFoundException.class)
  public ResponseEntity<Object> handleShortCodeNotFound(
      ShortCodeNotFoundException ex, WebRequest request) {
    return respond(
        problem(
            HttpStatus.NOT_FOUND,
            TYPE_SHORT_CODE_NOT_FOUND,
            "Short code not found",
            ex.getMessage()),
        request);
  }

  /**
   * Maps a taken alias / short code to 409 {@code alias-already-exists} (AC-3). The existing
   * mapping has already been left untouched by the time this runs; the handler only reports.
   *
   * @param ex the conflict raised by {@code UrlWriteService}
   * @param request the current request, used for {@code instance}
   * @return a 409 problem response whose detail names the taken code
   */
  @ExceptionHandler(AliasAlreadyExistsException.class)
  public ResponseEntity<Object> handleAliasAlreadyExists(
      AliasAlreadyExistsException ex, WebRequest request) {
    return respond(
        problem(
            HttpStatus.CONFLICT,
            TYPE_ALIAS_ALREADY_EXISTS,
            "Alias already exists",
            ex.getMessage()),
        request);
  }

  /**
   * Maps an expired mapping to 410 {@code short-code-expired} (AC-9).
   *
   * @param ex the expiry raised by {@code UrlReadService}
   * @param request the current request, used for {@code instance}
   * @return a 410 problem response whose detail names the code and, when known, the expiry
   */
  @ExceptionHandler(ShortCodeExpiredException.class)
  public ResponseEntity<Object> handleShortCodeExpired(
      ShortCodeExpiredException ex, WebRequest request) {
    return respond(
        problem(HttpStatus.GONE, TYPE_SHORT_CODE_EXPIRED, "Short code expired", ex.getMessage()),
        request);
  }

  // ---------------------------------------------------------------------------------------------
  // 500: anything else
  // ---------------------------------------------------------------------------------------------

  /**
   * Last-resort handler: any {@link Throwable} that no other handler (here or in the superclass)
   * claims becomes a 500 {@code internal-error} problem with the fixed {@link
   * #DETAIL_INTERNAL_ERROR} detail. This includes PostgreSQL outages, which are not degradable (see
   * {@code docs/operations.md} 1.2).
   *
   * <p>Side effect: the full exception, including its stack trace, is logged at ERROR together with
   * the request description (URI, no client info) so operators can diagnose what the client cannot
   * see.
   *
   * @param ex the unhandled error
   * @param request the current request, used for the log line and for {@code instance}
   * @return a 500 problem response that reveals nothing about {@code ex}
   */
  @ExceptionHandler(Throwable.class)
  public ResponseEntity<Object> handleUnexpected(Throwable ex, WebRequest request) {
    log.error("Unhandled error while processing {}", request.getDescription(false), ex);
    return respond(
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            TYPE_INTERNAL_ERROR,
            "Internal server error",
            DETAIL_INTERNAL_ERROR),
        request);
  }

  // ---------------------------------------------------------------------------------------------
  // Common rendering: every ResponseEntity produced by this class goes through here
  // ---------------------------------------------------------------------------------------------

  /**
   * Final step for every response, including the ones produced by the superclass for Spring MVC
   * exceptions: forces {@code application/problem+json}, fills {@code instance} with the request
   * path and replaces the default {@code about:blank} type with a stable identifier.
   *
   * <p>Only {@link ProblemDetail} bodies are touched; any other body is passed through with the
   * problem media type. For a superclass-built problem the {@code type} becomes {@link
   * #PROBLEM_TYPE_BASE} plus the kebab-cased reason phrase (for example {@code .../not-found} for
   * an unregistered route) and the {@code title} becomes the reason phrase. Values already set by
   * the handlers above are never overwritten. The {@link ProblemDetail} is mutated in place, which
   * is safe because each instance is created for one response.
   *
   * @param body the response body, normally a {@link ProblemDetail}
   * @param headers headers to send; may be empty
   * @param statusCode the HTTP status to send
   * @param request the current request; its URI becomes {@code instance} when servlet-based
   * @return the fully rendered response entity
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      if (problem.getInstance() == null) {
        problem.setInstance(instanceOf(request));
      }
      // Spring's own ProblemDetail.forStatus(...) leaves type as about:blank; give it a stable id.
      if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
        problem.setType(problemType(slugOf(statusCode)));
      }
      if (problem.getTitle() == null) {
        problem.setTitle(titleOf(statusCode));
      }
    }
    return ResponseEntity.status(statusCode)
        .headers(headers)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  /**
   * Shortcut used by the {@code @ExceptionHandler} methods: renders an already complete problem
   * with empty headers and the status recorded inside the problem.
   *
   * @param problem the problem to send; its {@code status} decides the HTTP status
   * @param request the current request, used for {@code instance}
   * @return the rendered response entity
   */
  private ResponseEntity<Object> respond(ProblemDetail problem, WebRequest request) {
    return createResponseEntity(
        problem, new HttpHeaders(), HttpStatusCode.valueOf(problem.getStatus()), request);
  }

  /**
   * Builds a {@link ProblemDetail} with explicit status, type, title and detail. {@code instance}
   * is left unset and filled in later by {@link #createResponseEntity}.
   *
   * @param status the HTTP status
   * @param type the stable problem type URI
   * @param title short human-readable summary, constant per type
   * @param detail client-safe explanation for this occurrence
   * @return the populated problem
   */
  private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type);
    problem.setTitle(title);
    return problem;
  }

  /**
   * Builds a problem type URI from a slug: {@link #PROBLEM_TYPE_BASE} plus {@code slug}.
   *
   * @param slug kebab-case identifier such as {@code "invalid-url"}
   * @return the absolute type URI
   */
  private static URI problemType(String slug) {
    return URI.create(PROBLEM_TYPE_BASE + slug);
  }

  /**
   * Derives the problem {@code instance} from the request: the servlet request URI (path only, no
   * query string, no host), for example {@code /api/v1/urls} or {@code /nope}.
   *
   * @param request the current request
   * @return the request URI, or {@code null} when the request is not servlet-based
   */
  private static URI instanceOf(WebRequest request) {
    if (request instanceof ServletWebRequest servletRequest) {
      return URI.create(servletRequest.getRequest().getRequestURI());
    }
    return null;
  }

  /**
   * Standard reason phrase for a status, used as the default {@code title} of superclass-built
   * problems.
   *
   * @param statusCode the HTTP status
   * @return the reason phrase (for example {@code "Not Found"}), or {@code "HTTP <code>"} for a
   *     status that {@link HttpStatus} does not know
   */
  private static String titleOf(HttpStatusCode statusCode) {
    HttpStatus status = HttpStatus.resolve(statusCode.value());
    return status != null ? status.getReasonPhrase() : "HTTP " + statusCode.value();
  }

  /**
   * Kebab-cases {@link #titleOf} for use as a problem type slug: lower-cased, every run of
   * non-alphanumerics replaced by one hyphen (for example {@code "Not Found"} to {@code
   * "not-found"}).
   *
   * @param statusCode the HTTP status
   * @return the slug
   */
  private static String slugOf(HttpStatusCode statusCode) {
    return titleOf(statusCode).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  /**
   * Renders a Java property name such as {@code longUrl} as its JSON name {@code long_url}.
   *
   * <p>Simple camel-to-snake conversion: every upper-case character becomes an underscore followed
   * by its lower-case form; everything else is copied. Nested paths or digits are not treated
   * specially, which is sufficient for the flat {@code CreateUrlRequest} record. Package-private
   * for unit testing.
   *
   * @param property the Java property name reported by the {@link FieldError}
   * @return the snake_case JSON name
   */
  static String jsonName(String property) {
    StringBuilder out = new StringBuilder(property.length() + 4);
    for (char c : property.toCharArray()) {
      if (Character.isUpperCase(c)) {
        out.append('_').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
