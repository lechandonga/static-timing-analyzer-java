package com.github.lechandonga.statictiminganalyzerjava.tests;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.StaService;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.CombinationalLoopException;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.ReportRenderer;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Business-scenario coverage of the local static timing analyzer.
 *
 * <p>Every nested class is one business module; its display name is printed in the test
 * log together with the PASS result of every case, so {@code mvn test} output shows which
 * businesses and cases ran.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaticTimingAnalyzerTests {

    private static final Logger log = LoggerFactory.getLogger("STA-TESTS");

    private static final String LIB = resource("sta-cases/standard.lib");
    private static final String GATE = resource("sta-cases/multiclock.gate");
    private static final String SDC = resource("sta-cases/multiclock.sdc");
    private static final String EXC = resource("sta-cases/exceptions.sdc");
    private static final String LOOP_GATE = resource("sta-cases/loop.gate");
    private static final String LOOP_SDC = resource("sta-cases/loop.sdc");
    private static final String FREE_GATE = resource("sta-cases/unconstrained.gate");
    private static final String FREE_SDC = resource("sta-cases/unconstrained.sdc");
    private static final String VIOLATION_SDC = resource("sta-cases/violation.sdc");

    private static void pass(String business, String caseName) {
        log.info("[{}] PASS - {}", business, caseName);
    }

    private static TimingReport run(String gate, String sdc) {
        return StaService.analyzeText("standard.lib", LIB, "case.gate", gate,
                "case.sdc", sdc);
    }

    private static String resource(String name) {
        try (InputStream stream = StaticTimingAnalyzerTests.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(stream, "missing test resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StaException expectFail(String business, String caseName,
                                           StaException.Code expected, String gate,
                                           String sdc, String libraryOverride) {
        String lib = libraryOverride == null ? LIB : libraryOverride;
        StaException error = assertThrows(StaException.class,
                () -> StaService.analyzeText("standard.lib", lib, "case.gate", gate,
                        "case.sdc", sdc));
        assertEquals(expected, error.getCode(),
                "case '" + caseName + "' expected error code " + expected
                        + " but got " + error.getCode() + ": " + error.getMessage());
        pass(business, caseName + " -> " + expected);
        return error;
    }

    @Nested
    @Order(1)
    @DisplayName("多时钟域与跨时钟域 (multi/ cross clock domains)")
    class CrossClockDomain {

        @Test
        @DisplayName("CDC endpoint classified separately, not single-period checked")
        void cdcIsClassifiedSeparately() {
            TimingReport report = run(GATE, SDC);
            TimingReport.EndpointResult cdc = endpoint(report, "ff3/D");
            assertEquals(TimingReport.Category.CROSS_CLOCK_DOMAIN, cdc.category,
                    "ff3/D receives ff2 data launched by CLK1, captured by CLK2");
            assertEquals("CLK2", cdc.captureClock);
            assertTrue(cdc.launchClocks.contains("CLK1"));
            assertEquals(0, report.summary.violated,
                    "CDC endpoints must not leak into violation counters");
            assertEquals(1, report.summary.crossClockDomain);
            log.info("\n{}", ReportRenderer.renderText(report));
            pass("cross-clock-domain", "ff2/Q -> ff3/D reported as CDC");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("未约束端点 (unconstrained endpoints)")
    class Unconstrained {

        @Test
        @DisplayName("uncovered endpoint is UNCONSTRAINED, neither met nor violated")
        void unconstrainedEndpointIsolated() {
            TimingReport report = run(FREE_GATE, FREE_SDC);
            TimingReport.EndpointResult output = endpoint(report, "Z");
            assertEquals(TimingReport.Category.UNCONSTRAINED, output.category);
            assertTrue(report.unconstrainedReasons.stream()
                    .anyMatch(reason -> reason.startsWith("Z:")));
            assertEquals(0, report.summary.violated);
            assertTrue(report.summary.met + report.summary.violated
                    < report.summary.totalEndpoints);
            pass("unconstrained", "output Z without set_output_delay is isolated");
        }

        @Test
        @DisplayName("endpoint fed by an unclocked input is UNCONSTRAINED")
        void unclockedLaunchIsolated() {
            TimingReport report = run(FREE_GATE,
                    FREE_SDC.replace("set_input_delay 0.5 clock CLK1 A", ""));
            TimingReport.EndpointResult flop = endpoint(report, "ff1/D");
            assertEquals(TimingReport.Category.UNCONSTRAINED, flop.category);
            assertEquals(0, report.summary.violated);
            pass("unconstrained", "flip-flop D fed without launch coverage is isolated");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("组合环 (combinational loops)")
    class Loops {

        @Test
        @DisplayName("loop analysis aborts with locatable nodes")
        void loopIsLocated() {
            CombinationalLoopException error = assertThrows(
                    CombinationalLoopException.class, () -> run(LOOP_GATE, LOOP_SDC));
            List<String> loop = error.getLoops().get(0);
            assertTrue(loop.contains("inv1/Z"), "loop nodes: " + loop);
            assertTrue(loop.contains("inv2/Z"), "loop nodes: " + loop);
            assertTrue(error.getLoops().size() >= 1);
            pass("combinational-loop",
                    "inv1/Z <-> inv2/Z loop reported, analysis aborted");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("例外声明 (false path / multicycle)")
    class Exceptions {

        @Test
        @DisplayName("false path and multicycle take effect on the right endpoints")
        void exceptionsTakeEffect() {
            TimingReport report = run(GATE, EXC);
            TimingReport.EndpointResult cdc = endpoint(report, "ff3/D");
            assertEquals(TimingReport.Category.FALSE_PATH, cdc.category);
            assertNotNull(cdc.appliedException);

            TimingReport.EndpointResult ff2 = endpoint(report, "ff2/D");
            // setup required = (10 - 0.05 - 0.05) * 2 = 19.8ns with 2-cycle multicycle
            assertEquals(19800.0, ff2.setupRequiredPs, 1e-6);
            assertTrue(ff2.appliedException.contains("multicycle"));
            assertEquals(0, report.summary.violated);
            assertEquals(1, report.summary.falsePaths);
            pass("exceptions",
                    "false path disables CDC check; 2-cycle multicycle relaxes setup");
        }

        @Test
        @DisplayName("equal-specificity conflicting multicycle rules fail deterministically")
        void conflictingExceptionsFail() {
            String conflicting = EXC + "\nset_multicycle_path 3 setup from ff1/Q to ff2/D\n";
            expectFail("exceptions", "same specificity 2 vs 3 setup multicycle",
                    StaException.Code.CONSTRAINT_CONFLICT, GATE, conflicting, null);
        }

        @Test
        @DisplayName("declaration order does not change the result")
        void orderDoesNotMatter() {
            TimingReport first = run(GATE, EXC);
            List<String> lines = new java.util.ArrayList<>(EXC.lines().toList());
            java.util.Collections.reverse(lines);
            String reordered = String.join("\n", lines);
            TimingReport second = run(GATE, reordered);
            assertEquals(canonical(ReportRenderer.renderText(first)),
                    canonical(ReportRenderer.renderText(second)));
            pass("exceptions", "reversed declaration order yields identical report");
        }
    }

    @Nested
    @Order(5)
    @DisplayName("非法输入 (illegal input handling)")
    class IllegalInputs {

        @Test
        @DisplayName("error codes distinguish every failure class")
        void distinguishableErrors() {
            expectFail("illegal-input", "duplicate cell",
                    StaException.Code.DUPLICATE_DEFINITION, GATE, SDC,
                    LIB + "\ncell BUF {\n    arc A -> Z comb delay 0.3 0.3\n}\n");
            expectFail("illegal-input", "unknown cell instance",
                    StaException.Code.UNKNOWN_REFERENCE,
                    GATE.replace("instance buf1 BUF", "instance buf1 MISSING"), SDC, null);
            expectFail("illegal-input", "dangling net pin",
                    StaException.Code.UNKNOWN_REFERENCE,
                    GATE.replace("load ff2/D", "load ff2/MISSING"), SDC, null);
            expectFail("illegal-input", "negative delay value",
                    StaException.Code.ILLEGAL_VALUE, GATE,
                    SDC.replace("period 10.0", "period -10.0"), null);
            expectFail("illegal-input", "bad time unit",
                    StaException.Code.ILLEGAL_UNIT, GATE,
                    SDC.replace("time_unit ns", "time_unit MHz"), null);
            expectFail("illegal-input", "duplicate clock",
                    StaException.Code.DUPLICATE_DEFINITION, GATE,
                    SDC + "create_clock CLK1 period 11 sources CLK1\n", null);
            expectFail("illegal-input", "clock referencing missing pin",
                    StaException.Code.UNKNOWN_REFERENCE, GATE,
                    SDC.replace("sources CLK1", "sources CLK9"), null);
            expectFail("illegal-input", "malformed statement",
                    StaException.Code.FORMAT_ERROR, GATE,
                    SDC + "totally_bogus_statement\n", null);
        }
    }

    @Nested
    @Order(6)
    @DisplayName("重复分析一致性 (repeatability)")
    class Repeatability {

        @Test
        @DisplayName("a genuinely failing design is VIOLATED with locatable worst path")
        void violationIsLocated() {
            TimingReport report = run(GATE, VIOLATION_SDC);
            assertTrue(report.summary.violated > 0,
                    "0.2ns clock cannot close a 0.54ns path");
            assertNotNull(report.summary.worstSetupEndpoint);
            TimingReport.EndpointResult worst = endpoint(report,
                    report.summary.worstSetupEndpoint);
            assertEquals(TimingReport.Category.VIOLATED, worst.category);
            assertTrue(worst.worstSetupPath.stream().anyMatch(
                    hop -> hop.via != null && hop.via.startsWith("arc:")));
            pass("violation",
                    "worst violating endpoint " + report.summary.worstSetupEndpoint
                            + " traced to concrete cells and pins");
        }

        @Test
        @DisplayName("repeated runs return identical reports and counters")
        void repeatableAnalysis() {
            TimingReport first = run(GATE, SDC);
            TimingReport second = run(GATE, SDC);
            TimingReport third = run(GATE, SDC);
            String text = ReportRenderer.renderText(first);
            assertEquals(text, ReportRenderer.renderText(second));
            assertEquals(text, ReportRenderer.renderText(third));
            assertEquals(first.summary.totalEndpoints, second.summary.totalEndpoints);
            assertEquals(first.summary.violated, second.summary.violated);
            pass("repeatability", "three identical runs produced byte-equal reports");
        }

        @Test
        @DisplayName("per-endpoint slacks sum-consistent with summary counters")
        void countersMatchEndpoints() {
            TimingReport report = run(GATE, SDC);
            long setupViolations = report.endpoints.stream()
                    .filter(endpoint -> endpoint.setupSlackPs != null
                            && endpoint.setupSlackPs < 0).count();
            long holdViolations = report.endpoints.stream()
                    .filter(endpoint -> endpoint.holdSlackPs != null
                            && endpoint.holdSlackPs < 0).count();
            assertEquals(setupViolations, report.summary.setupViolations);
            assertEquals(holdViolations, report.summary.holdViolations);
            assertEquals(report.endpoints.size(),
                    report.summary.met + report.summary.violated
                            + report.summary.crossClockDomain
                            + report.summary.unconstrained + report.summary.falsePaths);
            pass("repeatability", "per-endpoint results match summary statistics");
        }
    }

    private static TimingReport.EndpointResult endpoint(TimingReport report, String name) {
        return report.endpoints.stream()
                .filter(endpoint -> endpoint.endpoint.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "endpoint " + name + " missing; got "
                                + report.endpoints.stream().map(e -> e.endpoint).toList()));
    }

    /** Strips source line numbers so declaration reordering compares like-for-like. */
    private static String canonical(String text) {
        return text.replaceAll("lines?[ \\d/]*\\d", "line ?");
    }

}
