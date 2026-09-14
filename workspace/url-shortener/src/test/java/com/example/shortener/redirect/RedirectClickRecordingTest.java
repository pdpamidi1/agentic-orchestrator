/*
 * RedirectClickRecordingTest.java — click recording on the redirect path, adapter level
 *
 * Layer: test (unit). Standalone MockMvcTester over RedirectController with a mocked
 * UrlReadService and a mocked ClickEventRecorder plus the real GlobalExceptionHandler. Pins: a
 * successful redirect asks the recorder exactly once with the short code, the peer address and the
 * Referer header (AC-1); 404 and 410 never reach the recorder (AC-2); a throwing recorder leaves
 * status, Location and Cache-Control untouched and is logged at WARN without the raw address
 * (AC-3, AC-11); a missing Referer is passed as null (AC-14); the Spring constructor tolerates an
 * absent recorder bean and picks it up when present. No Spring Boot context, no database.
 */
package com.example.shortener.redirect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.analytics.recording.ClickEventRecorder;
import com.example.shortener.api.error.GlobalExceptionHandler;
import com.example.shortener.api.error.ShortCodeExpiredException;
import com.example.shortener.api.error.ShortCodeNotFoundException;
import com.example.shortener.read.RedirectController;
import com.example.shortener.read.UrlReadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@link RedirectController} + {@link ClickEventRecorder} interaction behind standalone MockMvc.
 */
class RedirectClickRecordingTest {

  private static final String CODE = "promo2024";
  private static final String LONG_URL = "https://example.com/some/path?x=1&y=2";
  private static final String CLIENT_IP = "203.0.113.7";
  private static final String REFERER = "https://news.example.org/story?id=42";

  private final UrlReadService service = mock(UrlReadService.class);
  private final ClickEventRecorder recorder = mock(ClickEventRecorder.class);

  private final MockMvcTester mvc =
      MockMvcTester.of(
          List.of(new RedirectController(service, recorder)),
          builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final Logger controllerLog = (Logger) LoggerFactory.getLogger(RedirectController.class);

  @BeforeEach
  void captureLog() {
    logEvents.start();
    controllerLog.addAppender(logEvents);
  }

  @AfterEach
  void releaseLog() {
    controllerLog.detachAppender(logEvents);
    logEvents.stop();
  }

  // --- AC-1: one record per successful redirect ------------------------------------------------

  /** A resolved code records exactly one click with code, peer address and Referer. */
  @Test
  void successfulRedirectRecordsExactlyOneClick() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);

    MvcTestResult result = get(CODE, CLIENT_IP, REFERER);

    assertThat(result).hasStatus(HttpStatus.FOUND);
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, LONG_URL);
    assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "private");
    verify(recorder, times(1)).record(anyString(), any(), any());
    verify(recorder).record(CODE, CLIENT_IP, REFERER);
    verifyNoMoreInteractions(recorder);
  }

  // --- AC-14: missing Referer ----------------------------------------------------------------

  /** Without a Referer header the recorder receives null for it and the redirect is unchanged. */
  @Test
  void missingRefererIsPassedAsNull() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);

    MvcTestResult result = get(CODE, CLIENT_IP, null);

    assertThat(result).hasStatus(HttpStatus.FOUND);
    verify(recorder).record(CODE, CLIENT_IP, null);
  }

  /** A garbage Referer is forwarded verbatim; reducing it to a host is the recorder's job. */
  @Test
  void garbageRefererIsForwardedToTheRecorder() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);

    MvcTestResult result = get(CODE, CLIENT_IP, "not a url");

    assertThat(result).hasStatus(HttpStatus.FOUND);
    verify(recorder).record(CODE, CLIENT_IP, "not a url");
  }

  // --- AC-2: no row for unknown or expired codes -----------------------------------------------

  /** 404: the recorder is never consulted. */
  @Test
  void unknownCodeRecordsNothing() {
    when(service.resolve("nope123")).thenThrow(new ShortCodeNotFoundException("nope123"));

    MvcTestResult result = get("nope123", CLIENT_IP, REFERER);

    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    verifyNoInteractions(recorder);
  }

  /** 410: the recorder is never consulted. */
  @Test
  void expiredCodeRecordsNothing() {
    when(service.resolve("old456"))
        .thenThrow(new ShortCodeExpiredException("old456", Instant.parse("2020-01-01T00:00:00Z")));

    MvcTestResult result = get("old456", CLIENT_IP, REFERER);

    assertThat(result).hasStatus(HttpStatus.GONE);
    verifyNoInteractions(recorder);
  }

  /** An unexpected lookup failure (500) also records nothing: only successes are clicks. */
  @Test
  void lookupFailureRecordsNothing() {
    when(service.resolve(CODE)).thenThrow(new IllegalStateException("pool exhausted"));

    MvcTestResult result = get(CODE, CLIENT_IP, REFERER);

    assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(recorder);
  }

  // --- AC-3 / AC-11: recorder failures are contained and logged without the address -----------

  /** A throwing recorder changes neither status nor headers, and the warning has no raw IP. */
  @Test
  void recorderFailureDoesNotChangeTheRedirectAndIsLoggedWithoutRawIp() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);
    when(recorder.record(anyString(), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    MvcTestResult result = get(CODE, CLIENT_IP, REFERER);

    assertThat(result).hasStatus(HttpStatus.FOUND);
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, LONG_URL);
    assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "private");
    assertThat(result).body().isEmpty();
    verify(recorder).record(CODE, CLIENT_IP, REFERER);

    List<ILoggingEvent> warnings =
        logEvents.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    assertThat(warnings).hasSize(1);
    ILoggingEvent warning = warnings.get(0);
    assertThat(warning.getFormattedMessage())
        .contains(CODE)
        .contains("DataAccessResourceFailureException")
        .doesNotContain(CLIENT_IP)
        .doesNotContain(REFERER);
    assertThat(warning.getThrowableProxy()).isNotNull();
    assertThat(warning.getThrowableProxy().getMessage()).doesNotContain(CLIENT_IP);
  }

  /** Even a RuntimeException that is not a Spring data exception is contained. */
  @Test
  void anyRuntimeExceptionFromTheRecorderIsContained() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);
    when(recorder.record(anyString(), any(), isNull()))
        .thenThrow(new IllegalArgumentException("hashedIp must be a 64-character digest"));

    MvcTestResult result = get(CODE, CLIENT_IP, null);

    assertThat(result).hasStatus(HttpStatus.FOUND);
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, LONG_URL);
  }

  // --- wiring ----------------------------------------------------------------------------------

  /** The single-argument constructor disables recording; redirects still work. */
  @Test
  void controllerWithoutRecorderStillRedirects() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);
    MockMvcTester bare =
        MockMvcTester.of(
            List.of(new RedirectController(service)),
            builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

    MvcTestResult result = bare.get().uri("/" + CODE).exchange();

    assertThat(result).hasStatus(HttpStatus.FOUND);
    verifyNoInteractions(recorder);
  }

  /** Spring wires the recorder when its bean exists and tolerates a context without one. */
  @Test
  void springConstructorPicksUpTheRecorderBeanWhenPresent() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(UrlReadService.class, () -> service)
            .withUserConfiguration(RedirectController.class);

    runner.run(context -> assertThat(context).hasSingleBean(RedirectController.class));
    runner
        .withBean(ClickEventRecorder.class, () -> recorder)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RedirectController.class);
              when(service.resolve(CODE)).thenReturn(LONG_URL);
              MockMvcTester wired =
                  MockMvcTester.of(
                      List.of(context.getBean(RedirectController.class)),
                      builder -> builder.build());
              wired
                  .get()
                  .uri("/" + CODE)
                  .with(
                      request -> {
                        request.setRemoteAddr(CLIENT_IP);
                        return request;
                      })
                  .exchange();
              verify(recorder).record(CODE, CLIENT_IP, null);
            });
  }

  /** The service is mandatory whichever constructor is used. */
  @Test
  void nullServiceIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RedirectController(null, recorder))
        .isInstanceOf(NullPointerException.class);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private MvcTestResult get(String shortCode, String remoteAddr, String referer) {
    var request =
        mvc.get()
            .uri("/" + shortCode)
            .with(
                req -> {
                  req.setRemoteAddr(remoteAddr);
                  return req;
                });
    if (referer != null) {
      request = request.header(HttpHeaders.REFERER, referer);
    }
    return request.exchange();
  }
}
