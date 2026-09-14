/*
 * ClickAnalyticsMigrationTest.java — static checks on the V2 click-analytics Flyway migration and
 * on the repositories' native SQL, without a database.
 *
 * Layer: test (unit). Pins src/main/resources/db/migration/V2__click_analytics.sql (task T2; AC-1,
 * AC-6, AC-7, AC-10, AC-12): additive and forward-only, the four tables with timestamptz columns,
 * the UNIQUE idempotency_key, the (status, next_attempt_at) and (occurred_at) indexes, the absence
 * of any raw-address or other personal-data column, and a one-to-one match between every
 * analytics @Entity's @Column names and the SQL. Also pins that ClickOutboxRepository.claimBatch is
 * a native SELECT ... LIMIT ... FOR UPDATE SKIP LOCKED. Technique: plain JUnit 5 + regex +
 * reflection; run with ./mvnw test. The migration is executed for real against PostgreSQL by
 * ClickAnalyticsRepositoryIT under ./mvnw -Pit verify.
 */
package com.example.shortener.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import com.example.shortener.analytics.domain.ProcessedClickEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Static checks on {@code V2__click_analytics.sql} and the analytics repositories. No Docker.
 *
 * <p>Fixture strategy: the SQL is read once per class; every test matches case-insensitive regular
 * expressions against it, and the entities are inspected through reflection so that a column
 * renamed on one side only is caught before Hibernate's {@code ddl-auto: validate} would fail at IT
 * start-up.
 */
class ClickAnalyticsMigrationTest {

  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path MIGRATION = MIGRATION_DIR.resolve("V2__click_analytics.sql");

  /** Column names that would hold personal data and must never appear in any analytics table. */
  private static final List<String> FORBIDDEN_COLUMNS =
      List.of(
          "ip",
          "ip_address",
          "ipaddress",
          "client_ip",
          "remote_ip",
          "remote_addr",
          "remote_address",
          "raw_ip",
          "user_agent",
          "email",
          "user_id",
          "session_id",
          "cookie");

  private static String sql;

  @BeforeAll
  static void readMigration() throws IOException {
    assertThat(MIGRATION).as("migration file").isRegularFile();
    sql = Files.readString(MIGRATION);
  }

  /** Given the directory, when listed, then V2 is the single new version and V1 is untouched. */
  @Test
  void migrationIsVersionTwoAndDirectoryHoldsOnlyVersionedSql() throws IOException {
    assertThat(MIGRATION.getFileName().toString()).matches("^V2__[A-Za-z0-9_]+\\.sql$");
    try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
      List<String> names = files.map(p -> p.getFileName().toString()).toList();
      assertThat(names).allMatch(n -> n.matches("^V\\d+(?:[._]\\d+)*__[A-Za-z0-9_]+\\.sql$"));
      assertThat(names.stream().filter(n -> n.startsWith("V2__")).count()).isEqualTo(1);
      assertThat(names).contains("V1__create_urls_table.sql");
    }
  }

  /**
   * Given the SQL, when scanned, then it is purely additive: no DROP, TRUNCATE, ALTER or DELETE, so
   * no existing table (in particular {@code urls}) is altered destructively.
   */
  @Test
  void migrationIsAdditiveAndForwardOnly() {
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\bDROP\\b"));
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\bTRUNCATE\\b"));
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\bALTER\\s+TABLE\\b"));
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\bDELETE\\s+FROM\\b"));
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\burls\\b"));
  }

  /** Given the SQL, when scanned, then click_outbox has every contracted column and type. */
  @Test
  void createsClickOutboxWithContractedColumns() {
    assertThat(sql).containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+click_outbox\\s*\\("));
    assertThat(sql).containsPattern(column("id", "bigint", true));
    assertThat(sql).containsPattern(column("idempotency_key", "varchar\\(\\d+\\)", true));
    assertThat(sql).containsPattern(column("short_code", "varchar\\(32\\)", true));
    assertThat(sql).containsPattern(column("occurred_at", "timestamptz", true));
    assertThat(sql).containsPattern(column("hashed_ip", "varchar\\(64\\)", true));
    assertThat(sql).containsPattern(column("referrer_host", "varchar\\(\\d+\\)", false));
    assertThat(sql).containsPattern(column("status", "varchar\\(\\d+\\)", true));
    assertThat(sql).containsPattern(column("attempts", "integer", true));
    assertThat(sql).containsPattern(column("next_attempt_at", "timestamptz", true));
    assertThat(sql).containsPattern(column("published_at", "timestamptz", false));
    assertThat(sql).containsPattern(column("created_at", "timestamptz", true));
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CHECK\\s*\\(\\s*status\\s+IN\\s*\\(\\s*'PENDING'\\s*,\\s*'PUBLISHED'\\s*,\\s*'FAILED'\\s*\\)\\s*\\)"));
  }

  /** Given the SQL, when scanned, then idempotency_key carries a UNIQUE constraint. */
  @Test
  void idempotencyKeyIsUnique() {
    assertThat(sql).containsPattern(Pattern.compile("(?i)UNIQUE\\s*\\(\\s*idempotency_key\\s*\\)"));
  }

  /**
   * Given the SQL, when scanned, then the relay index on (status, next_attempt_at) and the
   * retention index on (occurred_at) exist on click_outbox.
   */
  @Test
  void createsRelayAndRetentionIndexes() {
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CREATE\\s+INDEX\\s+\\w+\\s+ON\\s+click_outbox\\s*\\(\\s*status\\s*,\\s*next_attempt_at\\s*\\)"));
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CREATE\\s+INDEX\\s+\\w+\\s+ON\\s+click_outbox\\s*\\(\\s*occurred_at\\s*\\)"));
  }

  /** Given the SQL, when scanned, then the three aggregate/dedupe tables have their keys. */
  @Test
  void createsAggregateAndDedupeTablesWithPrimaryKeys() {
    assertThat(sql).containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+click_stats\\s*\\("));
    assertThat(sql).containsPattern(column("total_clicks", "bigint", true));
    assertThat(sql).containsPattern(column("last_clicked_at", "timestamptz", false));
    assertThat(sql).containsPattern(column("updated_at", "timestamptz", true));
    assertThat(sql)
        .containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+click_stats_daily\\s*\\("));
    assertThat(sql).containsPattern(column("day", "date", true));
    assertThat(sql).containsPattern(column("count", "bigint", true));
    assertThat(sql)
        .containsPattern(
            Pattern.compile("(?i)PRIMARY\\s+KEY\\s*\\(\\s*short_code\\s*,\\s*day\\s*\\)"));
    assertThat(sql)
        .containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+processed_click_event\\s*\\("));
    assertThat(sql).containsPattern(column("processed_at", "timestamptz", true));
    assertThat(sql)
        .containsPattern(Pattern.compile("(?i)PRIMARY\\s+KEY\\s*\\(\\s*idempotency_key\\s*\\)"));
  }

  /**
   * Given the SQL, when every timestamp column definition is inspected, then each is timestamptz
   * (UTC-safe); a plain {@code timestamp} would silently depend on the session time zone.
   */
  @Test
  void everyTimestampColumnIsTimestamptz() {
    Matcher m = Pattern.compile("(?im)^\\s*(\\w+_at)\\s+(\\w+)").matcher(sql);
    int found = 0;
    while (m.find()) {
      found++;
      assertThat(m.group(2)).as("type of %s", m.group(1)).isEqualToIgnoringCase("timestamptz");
    }
    assertThat(found).isGreaterThanOrEqualTo(7);
  }

  /**
   * Given the SQL, when the column definition lines are collected, then none is a forbidden
   * personal-data column and the only address-derived column is {@code hashed_ip} with a SHA-256
   * hex check constraint.
   */
  @Test
  void noColumnStoresRawAddressOrOtherPersonalData() {
    List<String> columns = new ArrayList<>();
    Matcher m =
        Pattern.compile("(?im)^\\s*(\\w+)\\s+(?:bigint|varchar|integer|timestamptz|date)\\b")
            .matcher(sql);
    while (m.find()) {
      columns.add(m.group(1).toLowerCase());
    }
    assertThat(columns).isNotEmpty();
    assertThat(columns).doesNotContainAnyElementsOf(FORBIDDEN_COLUMNS);
    assertThat(columns.stream().filter(c -> c.contains("ip")).toList())
        .containsExactly("hashed_ip");
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CHECK\\s*\\(\\s*hashed_ip\\s*~\\s*'\\^\\[0-9a-f\\]\\{64\\}\\$'\\s*\\)"));
  }

  /** Given the entities, when inspected by reflection, then their columns match the SQL 1:1. */
  @Test
  void entityColumnsMatchTheMigrationOneToOne() {
    assertEntity(
        ClickOutboxEntry.class,
        "click_outbox",
        "id",
        "idempotency_key",
        "short_code",
        "occurred_at",
        "hashed_ip",
        "referrer_host",
        "status",
        "attempts",
        "next_attempt_at",
        "published_at",
        "created_at");
    assertEntity(
        ClickStats.class,
        "click_stats",
        "short_code",
        "total_clicks",
        "last_clicked_at",
        "updated_at");
    assertEntity(ClickStatsDaily.class, "click_stats_daily", "short_code", "day", "count");
    assertEntity(
        ProcessedClickEvent.class, "processed_click_event", "idempotency_key", "processed_at");
  }

  /**
   * Given {@link ClickOutboxRepository#claimBatch}, when its {@code @Query} is read, then it is a
   * native {@code SELECT ... FOR UPDATE SKIP LOCKED} bounded by a {@code LIMIT} parameter and
   * filtered on the indexed (status, next_attempt_at) pair.
   */
  @Test
  void claimBatchIsNativeSelectForUpdateSkipLockedWithLimit() throws NoSuchMethodException {
    Method claim =
        ClickOutboxRepository.class.getMethod("claimBatch", java.time.Instant.class, int.class);
    Query query = claim.getAnnotation(Query.class);
    assertThat(query).isNotNull();
    assertThat(query.nativeQuery()).isTrue();
    String q = query.value();
    assertThat(q).containsPattern(Pattern.compile("(?i)^\\s*SELECT\\s+.*FROM\\s+click_outbox\\b"));
    assertThat(q).containsPattern(Pattern.compile("(?i)status\\s*=\\s*'PENDING'"));
    assertThat(q).containsPattern(Pattern.compile("(?i)next_attempt_at\\s*<=\\s*:now"));
    assertThat(q).containsPattern(Pattern.compile("(?i)\\bLIMIT\\s+:limit\\b"));
    assertThat(q).containsPattern(Pattern.compile("(?i)\\bFOR\\s+UPDATE\\s+SKIP\\s+LOCKED\\s*$"));
  }

  private static void assertEntity(Class<?> entity, String table, String... expectedColumns) {
    assertThat(entity.getAnnotation(Entity.class)).as("%s is @Entity", entity).isNotNull();
    Table t = entity.getAnnotation(Table.class);
    assertThat(t).isNotNull();
    assertThat(t.name()).isEqualTo(table);
    List<String> columns = columnsOf(entity);
    assertThat(columns).containsExactlyInAnyOrder(expectedColumns);
    for (String column : columns) {
      assertThat(sql).as("column %s present in migration", column).containsPattern(column(column));
    }
    assertThat(sql).containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+" + table + "\\s*\\("));
  }

  /** {@code @Column} names of the entity, descending into an {@code @EmbeddedId} if present. */
  private static List<String> columnsOf(Class<?> type) {
    List<String> names = new ArrayList<>();
    for (Field f : type.getDeclaredFields()) {
      if (f.isAnnotationPresent(Column.class)) {
        names.add(f.getAnnotation(Column.class).name());
      } else if (f.isAnnotationPresent(EmbeddedId.class)) {
        assertThat(f.getType().getAnnotation(Embeddable.class)).isNotNull();
        names.addAll(columnsOf(f.getType()));
      }
    }
    return names;
  }

  private static Pattern column(String name) {
    return Pattern.compile("(?im)^\\s*" + name + "\\s+\\S+");
  }

  private static Pattern column(String name, String type, boolean notNull) {
    String tail =
        notNull ? "(?:\\s+GENERATED[^,]*)?\\s+NOT\\s+NULL|\\s+GENERATED" : "(?!\\s+NOT\\s+NULL)";
    return Pattern.compile("(?im)^\\s*" + name + "\\s+" + type + "(?:" + tail + ")");
  }
}
