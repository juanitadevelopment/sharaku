package net.teppan.shazo.shell;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Producer;
import net.teppan.shazo.ShazoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ShellRepository} and {@link LineParser}.
 *
 * <p>Tests use {@code sh -c "..."} to stay portable across Unix-like systems
 * without relying on the PATH containing any specific binary beyond {@code sh}.
 */
class ShellRepositoryTest {

    // ── Domain type under test ───────────────────────────────────────────────

    record Line(String text) {}

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private ShellRepository<Line> repo;

    @BeforeEach
    void setUp() {
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of(ShellCommand.of("sh", "-c",
                "echo " + l.text() + " | wc -l")))
            .store  (l -> List.of())
            .delete (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of("sh", "-c",
                "printf '%s' '" + l.text() + "'")))
            .catalog (l -> List.of(ShellCommand.of("sh", "-c",
                "printf '%s\\n' " + l.text())))
            // infuser: first non-blank stdout line → Line
            .infuser(result -> result.primary().firstValue("line", Producer.asString())
                .map(Line::new).orElseThrow())
            // gatherer: each line → Line
            .build();

        repo = new ShellRepository<>(describer);
    }

    // ── retrieve ─────────────────────────────────────────────────────────────

    @Test
    void retrieveReturnsStdoutAsLine() throws ShazoException {
        var result = repo.retrieve(new Line("hello"));
        assertThat(result).contains(new Line("hello"));
    }

    @Test
    void retrieveEmptyOutputReturnsEmpty() throws ShazoException {
        // "true" exits 0 with no output; result has no rows → verifier returns false → Optional.empty()
        Describer<Line, ShellCommand> noOutputDescriber = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of("sh", "-c", "true")))
            .catalog (l -> List.of(ShellCommand.of("sh", "-c", "true")))
            .infuser(result -> new Line(""))
            .build();

        var emptyRepo = new ShellRepository<>(noOutputDescriber);
        assertThat(emptyRepo.retrieve(new Line("anything"))).isEmpty();
    }

    // ── catalog ───────────────────────────────────────────────────────────────

    @Test
    void catalogReturnsMultipleLines() throws ShazoException {
        // sh -c "printf '%s\n' a b c" prints a, b, c on separate lines
        Describer<Line, ShellCommand> multiDescriber = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of())
            .catalog (l -> List.of(ShellCommand.of("sh", "-c", "printf '%s\\n' a b c")))
            .infuser (result -> new Line(""))
            .build();

        // Lines are not keyed entities, so this is a tabular read: catalog the
        // rows and map them (gather = catalog-keys + retrieve-each does not apply).
        var lines = new ShellRepository<>(multiDescriber).catalog(new Line("ignored"))
            .rows().stream().map(row -> new Line((String) row.get("line"))).toList();
        assertThat(lines).containsExactly(new Line("a"), new Line("b"), new Line("c"));
    }

    // ── LineParser.tabDelimited ───────────────────────────────────────────────

    @Test
    void tabDelimitedParserSplitsColumns() throws ShazoException {
        record Row(String id, String name, String score) {}

        Describer<Row, ShellCommand> describer = Describer.<Row, ShellCommand>builder()
            .contains(r -> List.of())
            .store   (r -> List.of())
            .delete  (r -> List.of())
            .retrieve(r -> List.of(ShellCommand.of("sh", "-c",
                "printf '42\\tAlice\\t99.5'")))
            .catalog (r -> List.of())
            .infuser(result -> result.primary().first().map(row -> new Row(
                (String) row.get("id"),
                (String) row.get("name"),
                (String) row.get("score"))).orElseThrow())
            .build();

        var tabRepo = new ShellRepository<>(describer,
            LineParser.tabDelimited("id", "name", "score"));

        assertThat(tabRepo.retrieve(new Row("", "", "")))
            .contains(new Row("42", "Alice", "99.5"));
    }

    @Test
    void tabDelimitedFillsMissingColumnsWithEmptyString() throws ShazoException {
        record Pair(String a, String b, String c) {}

        Describer<Pair, ShellCommand> describer = Describer.<Pair, ShellCommand>builder()
            .contains(p -> List.of())
            .store   (p -> List.of())
            .delete  (p -> List.of())
            .retrieve(p -> List.of(ShellCommand.of("sh", "-c",
                "printf 'x\\ty'")))    // only 2 fields, 3 columns
            .catalog (p -> List.of())
            .infuser(result -> result.primary().first().map(row -> new Pair(
                (String) row.get("a"),
                (String) row.get("b"),
                (String) row.get("c"))).orElseThrow())
            .build();

        var tabRepo = new ShellRepository<>(describer,
            LineParser.tabDelimited("a", "b", "c"));

        assertThat(tabRepo.retrieve(new Pair("", "", "")))
            .contains(new Pair("x", "y", ""));
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void nonZeroExitCodeThrowsShazoException() {
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of("sh", "-c", "exit 42")))
            .catalog (l -> List.of())
            .infuser (result -> new Line(""))
            .build();

        var failingRepo = new ShellRepository<>(describer);
        assertThatThrownBy(() -> failingRepo.retrieve(new Line("x")))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("42");
    }

    @Test
    void slowProcessTimesOut() {
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of("sh", "-c", "sleep 10")))
            .catalog (l -> List.of())
            .infuser (result -> new Line(""))
            .build();

        var repo = new ShellRepository<>(describer,
            LineParser.byLine(), null, Duration.ofMillis(200));

        assertThatThrownBy(() -> repo.retrieve(new Line("x")))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("timed out");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of()).store(l -> List.of()).delete(l -> List.of())
            .retrieve(l -> List.of()).catalog(l -> List.of())
            .infuser(result -> new Line(""))
            .build();

        assertThatThrownBy(() -> new ShellRepository<>(describer,
                LineParser.byLine(), null, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonExistentCommandThrowsShazoException() {
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of(
                "__no_such_command_shazo_test__")))
            .catalog (l -> List.of())
            .infuser (result -> new Line(""))
            .build();

        var badRepo = new ShellRepository<>(describer);
        assertThatThrownBy(() -> badRepo.retrieve(new Line("x")))
            .isInstanceOf(ShazoException.class);
    }

    // ── Argument redaction ──────────────────────────────────────────────────────

    @Test
    void safeDescriptionNamesExecutableButNotArgumentValues() {
        var cmd = ShellCommand.of("grep", "--password=hunter2", "/etc/secret");
        assertThat(cmd.safeDescription())
            .contains("grep")
            .contains("2 arg(s)")
            .doesNotContain("hunter2")
            .doesNotContain("/etc/secret");
    }

    @Test
    void nonZeroExitMessageDoesNotLeakArgumentValues() {
        // A sensitive value is passed as an argument ($0 to the script) but never
        // echoed, so it must not surface in the exception message the repository
        // throws (which the HTTP transport can relay to a remote caller).
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of("sh", "-c", "exit 7", "SUPERSECRET_TOKEN")))
            .catalog (l -> List.of())
            .infuser (result -> new Line(""))
            .build();

        assertThatThrownBy(() -> new ShellRepository<>(describer).retrieve(new Line("x")))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("code 7")
            .hasMessageContaining("sh [3 arg(s)]")
            .hasMessageNotContaining("SUPERSECRET_TOKEN");
    }

    @Test
    void failedStartMessageDoesNotLeakArgumentValues() {
        Describer<Line, ShellCommand> describer = Describer.<Line, ShellCommand>builder()
            .contains(l -> List.of())
            .store   (l -> List.of())
            .delete  (l -> List.of())
            .retrieve(l -> List.of(ShellCommand.of(
                "__no_such_command_shazo_test__", "SUPERSECRET_TOKEN")))
            .catalog (l -> List.of())
            .infuser (result -> new Line(""))
            .build();

        assertThatThrownBy(() -> new ShellRepository<>(describer).retrieve(new Line("x")))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("__no_such_command_shazo_test__ [1 arg(s)]")
            .hasMessageNotContaining("SUPERSECRET_TOKEN");
    }

    // ── LineParser factory ────────────────────────────────────────────────────

    @Test
    void byLineParserMapsLineColumn() {
        var parser = LineParser.byLine();
        var row    = parser.parse("hello world");
        assertThat(row).containsEntry("line", "hello world");
    }

    @Test
    void tabDelimitedThrowsOnEmptyColumns() {
        assertThatThrownBy(() -> LineParser.tabDelimited())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delimitedThrowsOnEmptyColumns() {
        assertThatThrownBy(() -> LineParser.delimited(","))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delimitedParserSplitsOnCustomDelimiter() {
        var parser = LineParser.delimited(",", "x", "y");
        var row    = parser.parse("foo,bar");
        assertThat(row)
            .containsEntry("x", "foo")
            .containsEntry("y", "bar");
    }
}
