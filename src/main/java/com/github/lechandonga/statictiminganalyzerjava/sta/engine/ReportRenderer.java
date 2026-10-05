package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import java.util.Locale;

/** Renders a {@link TimingReport} as deterministic plain text. */
public final class ReportRenderer {

    private ReportRenderer() {
    }

    public static String renderText(TimingReport report) {
        StringBuilder builder = new StringBuilder();
        TimingReport.Summary summary = report.summary;
        builder.append("================ Static Timing Analysis Report ================\n");
        builder.append(String.format(Locale.ROOT,
                "endpoints=%d met=%d violated=%d (setup=%d hold=%d) cdc=%d "
                        + "unconstrained=%d false_path=%d%n",
                summary.totalEndpoints, summary.met, summary.violated,
                summary.setupViolations, summary.holdViolations,
                summary.crossClockDomain, summary.unconstrained, summary.falsePaths));
        builder.append(String.format(Locale.ROOT,
                "worst setup slack=%.3f ps @ %s%n", summary.worstSetupSlackPs,
                nz(summary.worstSetupEndpoint)));
        builder.append(String.format(Locale.ROOT,
                "worst hold  slack=%.3f ps @ %s%n", summary.worstHoldSlackPs,
                nz(summary.worstHoldEndpoint)));
        builder.append('\n');

        for (TimingReport.EndpointResult endpoint : report.endpoints) {
            builder.append("Endpoint ").append(endpoint.endpoint)
                    .append("  [").append(endpoint.category).append("]\n");
            if (endpoint.instance != null) {
                builder.append("  cell    : ").append(endpoint.cell)
                        .append(" (instance ").append(endpoint.instance).append(")\n");
            }
            builder.append("  launch  : ")
                    .append(endpoint.launchClocks.isEmpty() ? "-" : endpoint.launchClocks)
                    .append("  capture: ").append(nz(endpoint.captureClock)).append('\n');
            builder.append(String.format(Locale.ROOT,
                    "  arrival : %.3f ps%n", endpoint.arrivalPs));
            if (endpoint.setupRequiredPs != null) {
                builder.append(String.format(Locale.ROOT,
                        "  setup   : required=%.3f ps  slack=%.3f ps%n",
                        endpoint.setupRequiredPs, endpoint.setupSlackPs));
            }
            if (endpoint.holdRequiredPs != null) {
                builder.append(String.format(Locale.ROOT,
                        "  hold    : required=%.3f ps  slack=%.3f ps%n",
                        endpoint.holdRequiredPs, endpoint.holdSlackPs));
            }
            if (endpoint.appliedException != null) {
                builder.append("  exception: ").append(endpoint.appliedException).append('\n');
            }
            if (endpoint.reason != null) {
                builder.append("  note    : ").append(endpoint.reason).append('\n');
            }
            appendPath(builder, "  worst setup path", endpoint.worstSetupPath);
            appendPath(builder, "  worst hold  path", endpoint.worstHoldPath);
            builder.append('\n');
        }

        if (!report.unconstrainedReasons.isEmpty()) {
            builder.append("Unconstrained endpoints (not counted as met or violated):\n");
            report.unconstrainedReasons.stream().sorted().distinct()
                    .forEach(reason -> builder.append("  - ").append(reason).append('\n'));
            builder.append('\n');
        }
        if (!report.combinationalLoops.isEmpty()) {
            builder.append("COMBINATIONAL LOOPS:\n");
            for (var loop : report.combinationalLoops) {
                builder.append("  ").append(loop).append('\n');
            }
        }
        return builder.toString();
    }

    private static void appendPath(StringBuilder builder, String label,
                                   java.util.List<TimingReport.PathHop> hops) {
        if (hops == null || hops.isEmpty()) {
            return;
        }
        builder.append(label).append(':');
        for (TimingReport.PathHop hop : hops) {
            builder.append("\n      ").append(hop.node);
            if (hop.via != null) {
                builder.append("  <- ").append(hop.via)
                        .append(String.format(Locale.ROOT, " (%.3f ps)", hop.delayPs));
            }
        }
        builder.append('\n');
    }

    private static String nz(String value) {
        return value == null ? "-" : value;
    }
}
