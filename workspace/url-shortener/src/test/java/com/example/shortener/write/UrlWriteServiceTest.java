package com.example.shortener.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UrlWriteService} with a mocked repository and allocator: success (AC-1), alias conflict
 * (AC-3), no deduplication (AC-4), recorded {@code code_source} for both counters (AC-10, AC-11).
 */
class UrlWriteServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-14T10:15:30Z");
  private static final String LONG_URL = "https://example.com/some/path";
  private static final String BASE_URL = "http://localhost:8080";

  private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
  private final ShortCodeAllocator allocator = mock(ShortCodeAllocator.class);
  private final UrlWriteService service =
      new UrlWriteService(repository, allocator, BASE_URL, Clock.fixed(NOW, ZoneOffset.UTC));

  // --- success (AC-1, AC-10) -------------------------------------------------------------------

  @Test
  void persistsAllocatedRedisCodeAndReturnsShortUrlFromBaseUrl() {
    when(allocator.allocate()).thenReturn(new AllocatedCode("100001", CodeSource.REDIS));

    CreateUrlResponse response = service.create(new CreateUrlRequest(LONG_URL, null, null));

    assertThat(response.shortCode()).isEqualTo("100001");
    assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/100001");
    assertThat(response.longUrl()).isEqualTo(LONG_URL);
    assertThat(response.expiresAt()).isNull();
    assertThat(response.codeSource()).isEqualTo("redis");

    UrlMapping saved = savedMapping();
    assertThat(saved.getShortCode()).isEqualTo("100001");
    assertThat(saved.getLongUrl()).isEqualTo(LONG_URL);
    assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    assertThat(saved.getExpiresAt()).isNull();
    assertThat(saved.getCreatedBy()).isNull();
    assertThat(saved.getCodeSource()).isEqualTo(CodeSource.REDIS);
    verify(repository, never()).existsByShortCode(any());
  }

  @Test
  void persistsExpiryAsInstantAndEchoesItInTheResponse() {
    OffsetDateTime expiry = OffsetDateTime.of(2030, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2));
    when(allocator.allocate()).thenReturn(new AllocatedCode("100002", CodeSource.REDIS));

    CreateUrlResponse response = service.create(new CreateUrlRequest(LONG_URL, null, expiry));

    Instant expected = Instant.parse("2030-01-01T10:00:00Z");
    assertThat(response.expiresAt()).isEqualTo(expected);
    assertThat(savedMapping().getExpiresAt()).isEqualTo(expected);
  }

  @Test
  void usesCustomAliasAsShortCodeWithoutAllocatingACode() {
    when(repository.existsByShortCode("promo2024")).thenReturn(false);

    CreateUrlResponse response = service.create(new CreateUrlRequest(LONG_URL, "promo2024", null));

    assertThat(response.shortCode()).isEqualTo("promo2024");
    assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/promo2024");
    assertThat(response.codeSource())
        .isEqualTo(UrlWriteService.CUSTOM_ALIAS_CODE_SOURCE.wireValue());
    UrlMapping saved = savedMapping();
    assertThat(saved.getShortCode()).isEqualTo("promo2024");
    assertThat(saved.getCreatedBy()).isNull();
    assertThat(saved.getCodeSource()).isEqualTo(UrlWriteService.CUSTOM_ALIAS_CODE_SOURCE);
    verifyNoInteractions(allocator);
  }

  @Test
  void buildsShortUrlFromConfiguredPropertiesWithoutTrailingSlash() {
    ShortenerProperties properties =
        new ShortenerProperties("https://sho.rt/", 1000, Duration.ofHours(24), 916_132_832L);
    UrlWriteService configured = new UrlWriteService(repository, allocator, properties);
    when(allocator.allocate()).thenReturn(new AllocatedCode("abc123", CodeSource.REDIS));

    assertThat(configured.baseUrl()).isEqualTo("https://sho.rt");
    assertThat(configured.create(new CreateUrlRequest(LONG_URL, null, null)).shortUrl())
        .isEqualTo("https://sho.rt/abc123");
    assertThat(
            new UrlWriteService(repository, allocator, "http://x//", Clock.systemUTC()).baseUrl())
        .isEqualTo("http://x");
  }

  // --- alias conflict (AC-3) -------------------------------------------------------------------

  @Test
  void takenAliasIsRejectedByPreCheckAndNothingIsPersisted() {
    when(repository.existsByShortCode("promo2024")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new CreateUrlRequest(LONG_URL, "promo2024", null)))
        .isInstanceOf(AliasAlreadyExistsException.class)
        .hasMessageContaining("promo2024");

    verify(repository, never()).saveAndFlush(any());
    verify(repository, never()).save(any());
    verifyNoInteractions(allocator);
  }

  @Test
  void primaryKeyViolationOnFlushIsRejectedAsConflict() {
    when(repository.existsByShortCode("promo2024")).thenReturn(false);
    when(repository.saveAndFlush(any(UrlMapping.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key value violates pk_urls"));

    assertThatThrownBy(() -> service.create(new CreateUrlRequest(LONG_URL, "promo2024", null)))
        .isInstanceOf(AliasAlreadyExistsException.class)
        .hasMessageContaining("promo2024")
        .hasMessageNotContaining("pk_urls");
  }

  @Test
  void generatedCodeCollisionOnFlushIsRejectedAsConflictToo() {
    when(allocator.allocate()).thenReturn(new AllocatedCode("100001", CodeSource.REDIS));
    when(repository.saveAndFlush(any(UrlMapping.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> service.create(new CreateUrlRequest(LONG_URL, null, null)))
        .isInstanceOf(AliasAlreadyExistsException.class)
        .hasMessageContaining("100001");
  }

  // --- no deduplication (AC-4) -----------------------------------------------------------------

  @Test
  void repostingTheSameLongUrlAllocatesANewDistinctCodeEveryTime() {
    when(allocator.allocate())
        .thenReturn(new AllocatedCode("100001", CodeSource.REDIS))
        .thenReturn(new AllocatedCode("100002", CodeSource.REDIS))
        .thenReturn(new AllocatedCode("100003", CodeSource.REDIS));
    CreateUrlRequest request = new CreateUrlRequest(LONG_URL, null, null);

    List<String> codes =
        List.of(
            service.create(request).shortCode(),
            service.create(request).shortCode(),
            service.create(request).shortCode());

    assertThat(codes).containsExactly("100001", "100002", "100003").doesNotHaveDuplicates();
    verify(allocator, times(3)).allocate();
    ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
    verify(repository, times(3)).saveAndFlush(captor.capture());
    assertThat(captor.getAllValues()).extracting(UrlMapping::getLongUrl).containsOnly(LONG_URL);
    assertThat(captor.getAllValues())
        .extracting(UrlMapping::getShortCode)
        .containsExactly("100001", "100002", "100003");
    verify(repository, never()).existsByShortCode(any());
  }

  // --- db_sequence fallback (AC-11) ------------------------------------------------------------

  @Test
  void persistsDbSequenceCodeSourceWhenTheAllocatorFellBack() {
    when(allocator.allocate()).thenReturn(new AllocatedCode("1Xy9zQ", CodeSource.DB_SEQUENCE));

    CreateUrlResponse response = service.create(new CreateUrlRequest(LONG_URL, null, null));

    assertThat(response.shortCode()).isEqualTo("1Xy9zQ");
    assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/1Xy9zQ");
    assertThat(response.codeSource()).isEqualTo("db_sequence");
    UrlMapping saved = savedMapping();
    assertThat(saved.getCodeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(saved.getShortCode()).isEqualTo("1Xy9zQ");
    assertThat(saved.getCreatedBy()).isNull();
  }

  @Test
  void allocatorFailurePropagatesAndNothingIsPersisted() {
    when(allocator.allocate()).thenThrow(new IllegalStateException("both counters down"));

    assertThatThrownBy(() -> service.create(new CreateUrlRequest(LONG_URL, null, null)))
        .isInstanceOf(IllegalStateException.class);

    verify(repository, never()).saveAndFlush(any());
  }

  // --- semantic checks beyond Bean Validation --------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.com/file",
        "https://",
        "https:///path-without-host",
        "http:// example.com",
        "example.com/no-scheme",
        "   ",
        "HTTPS://"
      })
  void semanticallyInvalidLongUrlIsRejectedBeforeAllocation(String longUrl) {
    assertThatThrownBy(() -> service.create(new CreateUrlRequest(longUrl, null, null)))
        .isInstanceOf(InvalidUrlException.class)
        .hasMessage(UrlWriteService.DETAIL_INVALID_LONG_URL);

    verifyNoInteractions(allocator, repository);
  }

  @Test
  void overlongLongUrlIsRejected() {
    String url = "https://example.com/" + "a".repeat(2048);

    assertThatThrownBy(() -> service.create(new CreateUrlRequest(url, null, null)))
        .isInstanceOf(InvalidUrlException.class)
        .hasMessage(UrlWriteService.DETAIL_LONG_URL_TOO_LONG);
    verifyNoInteractions(allocator, repository);
  }

  @Test
  void upperCaseSchemeWithHostIsAccepted() {
    when(allocator.allocate()).thenReturn(new AllocatedCode("100009", CodeSource.REDIS));

    assertThat(service.create(new CreateUrlRequest("HTTP://Example.com", null, null)).longUrl())
        .isEqualTo("HTTP://Example.com");
  }

  @Test
  void expiryAtOrBeforeNowIsRejected() {
    OffsetDateTime atNow = NOW.atOffset(ZoneOffset.UTC);

    assertThatThrownBy(() -> service.create(new CreateUrlRequest(LONG_URL, null, atNow)))
        .isInstanceOf(InvalidUrlException.class)
        .hasMessage(UrlWriteService.DETAIL_EXPIRY_NOT_IN_FUTURE);
    assertThatThrownBy(
            () -> service.create(new CreateUrlRequest(LONG_URL, null, atNow.minusSeconds(1))))
        .isInstanceOf(InvalidUrlException.class);
    verifyNoInteractions(allocator, repository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "bad-alias", "with space", "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q"})
  void malformedAliasIsRejectedWithoutTouchingTheRepository(String alias) {
    assertThatThrownBy(() -> service.create(new CreateUrlRequest(LONG_URL, alias, null)))
        .isInstanceOf(InvalidUrlException.class)
        .hasMessage(UrlWriteService.DETAIL_INVALID_ALIAS);
    verifyNoInteractions(allocator, repository);
  }

  // --- construction / wiring -------------------------------------------------------------------

  @Test
  void createIsTransactional() throws NoSuchMethodException {
    assertThat(
            UrlWriteService.class
                .getMethod("create", CreateUrlRequest.class)
                .isAnnotationPresent(Transactional.class))
        .isTrue();
  }

  @Test
  void rejectsMissingCollaborators() {
    Clock clock = Clock.systemUTC();
    assertThatNullPointerException()
        .isThrownBy(() -> new UrlWriteService(null, allocator, BASE_URL, clock));
    assertThatNullPointerException()
        .isThrownBy(() -> new UrlWriteService(repository, null, BASE_URL, clock));
    assertThatNullPointerException()
        .isThrownBy(() -> new UrlWriteService(repository, allocator, null, clock));
    assertThatNullPointerException()
        .isThrownBy(() -> new UrlWriteService(repository, allocator, BASE_URL, null));
    assertThatNullPointerException().isThrownBy(() -> service.create(null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new UrlWriteService(repository, allocator, " / ", clock));
  }

  private UrlMapping savedMapping() {
    ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
    verify(repository).saveAndFlush(captor.capture());
    return captor.getValue();
  }
}
