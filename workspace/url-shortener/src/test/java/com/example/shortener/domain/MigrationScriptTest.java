package com.example.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Static checks on the Flyway migration for the {@code urls} table (AC-15). Reads the SQL file from
 * the source tree; no database container is needed.
 */
class MigrationScriptTest {

  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path MIGRATION = MIGRATION_DIR.resolve("V1__create_urls_table.sql");

  /** Minimum sequence start honouring AMB-11 / ADR-004: 62^3 so raw values encode to >= 3 chars. */
  private static final long MIN_SEQUENCE_START = 238_328L;

  private static String sql;

  @BeforeAll
  static void readMigration() throws IOException {
    assertThat(MIGRATION).as("migration file").isRegularFile();
    sql = Files.readString(MIGRATION);
  }

  @Test
  void migrationFollowsFlywayVersionedNaming() {
    String name = MIGRATION.getFileName().toString();
    assertThat(name).matches("^V1__[A-Za-z0-9_]+\\.sql$");
  }

  @Test
  void migrationIsTheOnlyV1AndDirectoryHoldsOnlyVersionedSql() throws IOException {
    try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
      List<String> names = files.map(p -> p.getFileName().toString()).toList();
      assertThat(names).allMatch(n -> n.matches("^V\\d+(?:[._]\\d+)*__[A-Za-z0-9_]+\\.sql$"));
      assertThat(names.stream().filter(n -> n.startsWith("V1__")).count()).isEqualTo(1);
    }
  }

  @Test
  void createsUrlsTableWithEveryRequiredColumn() {
    assertThat(sql).containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+urls\\s*\\("));
    assertThat(sql).containsPattern(column("short_code", "varchar\\(32\\)", true));
    assertThat(sql).containsPattern(column("long_url", "varchar\\(2048\\)", true));
    assertThat(sql).containsPattern(column("created_at", "timestamptz", true));
    assertThat(sql).containsPattern(column("expires_at", "timestamptz", false));
    assertThat(sql).containsPattern(column("created_by", "varchar(?:\\(\\d+\\))?", false));
    assertThat(sql).containsPattern(column("code_source", "varchar(?:\\(\\d+\\))?", true));
  }

  @Test
  void shortCodeIsThePrimaryKey() {
    assertThat(sql)
        .containsPattern(Pattern.compile("(?i)PRIMARY\\s+KEY\\s*\\(\\s*short_code\\s*\\)"));
  }

  @Test
  void nullableColumnsAreNotForcedNotNull() {
    // Anchored to the column definition line so the partial index predicate
    // (WHERE expires_at IS NOT NULL) is not mistaken for a NOT NULL constraint.
    assertThat(sql)
        .doesNotContainPattern(Pattern.compile("(?im)^\\s*expires_at\\s+\\S+\\s+NOT\\s+NULL"));
    assertThat(sql)
        .doesNotContainPattern(Pattern.compile("(?im)^\\s*created_by\\s+\\S+\\s+NOT\\s+NULL"));
  }

  @Test
  void codeSourceIsRestrictedToTheKnownWireValues() {
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CHECK\\s*\\(\\s*code_source\\s+IN\\s*\\(\\s*'redis'\\s*,\\s*'db_sequence'\\s*\\)\\s*\\)"));
  }

  @Test
  void createsAnIndexSupportingExpiryLookups() {
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CREATE\\s+INDEX\\s+\\w+\\s+ON\\s+urls\\s*\\(\\s*expires_at\\s*\\)"));
  }

  @Test
  void createsFallbackSequenceSeededPastTheAmb11Offset() {
    var matcher =
        Pattern.compile("(?i)CREATE\\s+SEQUENCE\\s+url_code_seq\\b[^;]*?START\\s+WITH\\s+(\\d+)")
            .matcher(sql);
    assertThat(matcher.find()).as("CREATE SEQUENCE url_code_seq ... START WITH n").isTrue();
    assertThat(Long.parseLong(matcher.group(1))).isGreaterThanOrEqualTo(MIN_SEQUENCE_START);
    assertThat(sql).containsPattern(Pattern.compile("(?i)url_code_seq\\b[^;]*NO\\s+CYCLE"));
  }

  @Test
  void migrationContainsNoDestructiveStatements() {
    assertThat(sql)
        .doesNotContainPattern(Pattern.compile("(?i)\\bDROP\\s+(TABLE|SEQUENCE|INDEX)\\b"));
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\bTRUNCATE\\b"));
  }

  @Test
  void entityColumnsMatchTheMigrationOneToOne() {
    Table table = UrlMapping.class.getAnnotation(Table.class);
    assertThat(table).isNotNull();
    assertThat(table.name()).isEqualTo("urls");

    List<String> entityColumns =
        Stream.of(UrlMapping.class.getDeclaredFields())
            .filter(f -> f.isAnnotationPresent(Column.class))
            .map(f -> f.getAnnotation(Column.class).name())
            .toList();
    assertThat(entityColumns)
        .containsExactlyInAnyOrder(
            "short_code", "long_url", "created_at", "expires_at", "created_by", "code_source");
    for (String column : entityColumns) {
      assertThat(sql).as("column %s present in migration", column).containsPattern(column(column));
    }

    List<String> idColumns =
        Stream.of(UrlMapping.class.getDeclaredFields())
            .filter(f -> f.isAnnotationPresent(Id.class))
            .map(Field::getName)
            .toList();
    assertThat(idColumns).containsExactly("shortCode");
  }

  private static Pattern column(String name) {
    return Pattern.compile("(?im)^\\s*" + name + "\\s+\\S+");
  }

  private static Pattern column(String name, String type, boolean notNull) {
    String tail = notNull ? "\\s+NOT\\s+NULL" : "(?!\\s+NOT\\s+NULL)";
    return Pattern.compile("(?im)^\\s*" + name + "\\s+" + type + tail);
  }
}
