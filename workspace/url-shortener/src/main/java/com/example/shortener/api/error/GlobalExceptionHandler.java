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
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** Base URI of the problem {@code type} identifiers. */
  static final String PROBLEM_TYPE_BASE = "https://example.com/problems/";

  static final URI TYPE_VALIDATION_ERROR = problemType("validation-error");
  static final URI TYPE_MALFORMED_REQUEST = problemType("malformed-request");
  static final URI TYPE_INVALID_URL = problemType("invalid-url");
  static final URI TYPE_SHORT_CODE_NOT_FOUND = problemType("short-code-not-found");
  static final URI TYPE_ALIAS_ALREADY_EXISTS = problemType("alias-already-exists");
  static final URI TYPE_SHORT_CODE_EXPIRED = problemType("short-code-expired");
  static final URI TYPE_INTERNAL_ERROR = problemType("internal-error");

  static final String DETAIL_INTERNAL_ERROR =
      "An unexpected error occurred while processing the request";
  static final String DETAIL_MALFORMED_REQUEST =
      "Request body is missing, malformed or contains a value of the wrong type";

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // ---------------------------------------------------------------------------------------------
  // 400: Bean Validation failures (@Valid on the request body) and unreadable bodies
  // ---------------------------------------------------------------------------------------------

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

  @ExceptionHandler(InvalidUrlException.class)
  public ResponseEntity<Object> handleInvalidUrl(InvalidUrlException ex, WebRequest request) {
    return respond(
        problem(HttpStatus.BAD_REQUEST, TYPE_INVALID_URL, "Invalid URL", ex.getMessage()), request);
  }

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
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      if (problem.getInstance() == null) {
        problem.setInstance(instanceOf(request));
      }
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

  private ResponseEntity<Object> respond(ProblemDetail problem, WebRequest request) {
    return createResponseEntity(
        problem, new HttpHeaders(), HttpStatusCode.valueOf(problem.getStatus()), request);
  }

  private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type);
    problem.setTitle(title);
    return problem;
  }

  private static URI problemType(String slug) {
    return URI.create(PROBLEM_TYPE_BASE + slug);
  }

  private static URI instanceOf(WebRequest request) {
    if (request instanceof ServletWebRequest servletRequest) {
      return URI.create(servletRequest.getRequest().getRequestURI());
    }
    return null;
  }

  private static String titleOf(HttpStatusCode statusCode) {
    HttpStatus status = HttpStatus.resolve(statusCode.value());
    return status != null ? status.getReasonPhrase() : "HTTP " + statusCode.value();
  }

  private static String slugOf(HttpStatusCode statusCode) {
    return titleOf(statusCode).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  /** Renders a Java property name such as {@code longUrl} as its JSON name {@code long_url}. */
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
