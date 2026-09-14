/*
 * MigrationScriptTest.java — static checks on the V1 Flyway migration of the urls table.
 *
 * Layer: test (unit). Pins the shape of src/main/resources/db/migration/V1__create_urls_table.sql
 * without a database (AC-15): Flyway naming, the six required columns with their types and
 * nullability, short_code as primary key, the code_source check constraint, the expires_at index,
 * the url_code_seq fallback sequence seeded at or above 62^3 with NO CYCLE, absence of destructive
 * statements, and a one-to-one match with the @Column names of the UrlMapping entity. Technique:
 * plain JUnit 5 reading the SQL file from the source tree and matching regular expressions, plus
 * reflection over the JPA annotations. Run with ./mvnw test (from the module root: the path is
 * relative to the working directory). The migration is actually executed against Postgres by
 * every *IT under ./mvnw -Pit verify.
 */
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
 *
 * <p>Fixture strategy: the migration is read once per class in {@link #readMigration()} into the
 * static {@link #sql} string; every test matches case-insensitive, mostly line-anchored regular
 * expressions against it. Column patterns are built by {@link #column(String, String, boolean)} so
 * nullability is checked on the column definition line only. No Spring, no JPA runtime: the entity
 * is inspected through reflection on its annotations.
 *
 * <p>Removing this class would allow the migration to be renamed, a column dropped or made NOT
 * NULL, the check constraint loosened or the sequence re-seeded below the AMB-11 bound without any
 * Docker-free test noticing; the schema would only be validated by Hibernate at IT start-up, which
 * does not cover constraints, indexes or the sequence.
 */
class MigrationScriptTest {

  /** Flyway location configured in application.yml, relative to the module root. */
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

  /** The one and only migration of this release. */
  private static final Path MIGRATION = MIGRATION_DIR.resolve("V1__create_urls_table.sql");

  /** Minimum sequence start honouring AMB-11 / ADR-004: 62^3 so raw values encode to >= 3 chars. */
  private static final long MIN_SEQUENCE_START = 238_328L;

  /** Full text of the migration, loaded once in {@link #readMigration()}. */
  private static String sql;

  /** Asserts the migration file exists and reads it once for all tests of the class. */
  @BeforeAll
  static void readMigration() throws IOException {
    assertThat(MIGRATION).as("migration file").isRegularFile();
    sql = Files.readString(MIGRATION);
  }

  /**
   * Given the migration file name, when matched against Flyway's versioned pattern, then it is
   * {@code V1__<description>.sql} (two underscores, alphanumeric description).
   */
  @Test
  void migrationFollowsFlywayVersionedNaming() {
    String name = MIGRATION.getFileName().toString();
    assertThat(name).matches("^V1__[A-Za-z0-9_]+\\.sql$");
  }

  /**
   * Given the migration directory listing, when inspected, then every file is a versioned Flyway
   * script (no repeatable or stray files) and exactly one carries version 1.
   */
  @Test
  void migrationIsTheOnlyV1AndDirectoryHoldsOnlyVersionedSql() throws IOException {
    try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
      List<String> names = files.map(p -> p.getFileName().toString()).toList();
      assertThat(names).allMatch(n -> n.matches("^V\\d+(?:[._]\\d+)*__[A-Za-z0-9_]+\\.sql$"));
      assertThat(names.stream().filter(n -> n.startsWith("V1__")).count()).isEqualTo(1);
    }
  }

  /**
   * Given the SQL, when scanned, then it creates {@code urls} with short_code varchar(32) NOT NULL,
   * long_url varchar(2048) NOT NULL, created_at timestamptz NOT NULL, expires_at timestamptz
   * nullable, created_by varchar nullable and code_source varchar NOT NULL (AC-15 column list).
   */
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

  /** Given the SQL, when scanned, then {@code short_code} is declared as the primary key. */
  @Test
  void shortCodeIsThePrimaryKey() {
    assertThat(sql)
        .containsPattern(Pattern.compile("(?i)PRIMARY\\s+KEY\\s*\\(\\s*short_code\\s*\\)"));
  }

  /**
   * Given the SQL, when the {@code expires_at} and {@code created_by} definition lines are
   * inspected, then neither is NOT NULL (the partial index predicate {@code WHERE expires_at IS NOT
   * NULL} on another line must not be mistaken for a constraint).
   */
  @Test
  void nullableColumnsAreNotForcedNotNull() {
    // Anchored to the column definition line so the partial index predicate
    // (WHERE expires_at IS NOT NULL) is not mistaken for a NOT NULL constraint.
    assertThat(sql)
        .doesNotContainPattern(Pattern.compile("(?im)^\\s*expires_at\\s+\\S+\\s+NOT\\s+NULL"));
    assertThat(sql)
        .doesNotContainPattern(Pattern.compile("(?im)^\\s*created_by\\s+\\S+\\s+NOT\\s+NULL"));
  }

  /**
   * Given the SQL, when scanned, then a CHECK restricts {@code code_source} to exactly the two wire
   * values {@code 'redis'} and {@code 'db_sequence'} of {@link CodeSource}.
   */
  @Test
  void codeSourceIsRestrictedToTheKnownWireValues() {
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CHECK\\s*\\(\\s*code_source\\s+IN\\s*\\(\\s*'redis'\\s*,\\s*'db_sequence'\\s*\\)\\s*\\)"));
  }

  /** Given the SQL, when scanned, then an index on {@code urls(expires_at)} exists. */
  @Test
  void createsAnIndexSupportingExpiryLookups() {
    assertThat(sql)
        .containsPattern(
            Pattern.compile(
                "(?i)CREATE\\s+INDEX\\s+\\w+\\s+ON\\s+urls\\s*\\(\\s*expires_at\\s*\\)"));
  }

  /**
   * Given the SQL, when the {@code CREATE SEQUENCE url_code_seq} statement is parsed, then its
   * START WITH value is at least 62^3 = 238,328 and the sequence is NO CYCLE, so fallback values
   * are never reused and encode to three or more characters even before the seed offset.
   */
  @Test
  void createsFallbackSequenceSeededPastTheAmb11Offset() {
    var matcher =
        Pattern.compile("(?i)CREATE\\s+SEQUENCE\\s+url_code_seq\\b[^;]*?START\\s+WITH\\s+(\\d+)")
            .matcher(sql);
    assertThat(matcher.find()).as("CREATE SEQUENCE url_code_seq ... START WITH n").isTrue();
    assertThat(Long.parseLong(matcher.group(1))).isGreaterThanOrEqualTo(MIN_SEQUENCE_START);
    assertThat(sql).containsPattern(Pattern.compile("(?i)url_code_seq\\b[^;]*NO\\s+CYCLE"));
  }

  /**
   * Given the SQL, when scanned, then it contains no DROP TABLE/SEQUENCE/INDEX and no TRUNCATE: a
   * forward-only first migration must never destroy data.
   */
  @Test
  void migrationContainsNoDestructiveStatements() {
    assertThat(sql)
        .doesNotContainPattern(Pattern.compile("(?i)\\bDROP\\s+(TABLE|SEQUENCE|INDEX)\\b"));
    assertThat(sql).doesNotContainPattern(Pattern.compile("(?i)\\bTRUNCATE\\b"));
  }

  /**
   * Given the {@link UrlMapping} annotations read by reflection, when compared with the SQL, then
   * the entity maps table {@code urls}, its {@code @Column} names are exactly the six migration
   * columns, each appears as a definition line in the SQL, and {@code shortCode} is the only
   * {@code @Id} (so Hibernate's {@code ddl-auto: validate} will pass at start-up).
   */
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

  /**
   * Pattern for a column definition line: the name at the start of a line followed by a type.
   *
   * @param name the SQL column name
   * @return a multiline, case-insensitive pattern
   */
  private static Pattern column(String name) {
    return Pattern.compile("(?im)^\\s*" + name + "\\s+\\S+");
  }

  /**
   * Pattern for a column definition line with a given type and nullability: {@code notNull} demands
   * a following {@code NOT NULL}; otherwise a negative lookahead forbids it right after the type.
   *
   * @param name the SQL column name
   * @param type regular expression for the column type, e.g. {@code varchar\\(32\\)}
   * @param notNull whether the column must be declared NOT NULL
   * @return a multiline, case-insensitive pattern
   */
  private static Pattern column(String name, String type, boolean notNull) {
    String tail = notNull ? "\\s+NOT\\s+NULL" : "(?!\\s+NOT\\s+NULL)";
    return Pattern.compile("(?im)^\\s*" + name + "\\s+" + type + tail);
  }
}
