package com.github.lechandonga.statictiminganalyzerjava.sta.service;

import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingReport;

import java.util.List;
import java.util.Locale;

/** 将 TimingReport 渲染为确定性的纯文本报告。 */
public final class ReportRenderer {

    private ReportRenderer() {
    }

    public static String render(TimingReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("================ Static Timing Analysis Report ================\n");
        if (r.status == TimingReport.Status.COMBINATIONAL_CYCLE) {
            sb.append("分析中止：检测到 ").append(r.cycles.size()).append(" 个组合环\n");
            for (TimingReport.Cycle c : r.cycles) {
                sb.append("  * ").append(c.description).append('\n');
            }
            sb.append("==============================================================\n");
            return sb.toString();
        }
        TimingReport.Summary s = r.summary;
        sb.append(String.format(Locale.ROOT,
                "端点总数=%d  建立违例=%d  保持违例=%d  通过=%d  未约束=%d  纯跨时钟域=%d%n",
                s.totalEndpoints, s.setupViolations, s.holdViolations, s.met,
                s.unconstrained, s.cdc));
        sb.append(String.format(Locale.ROOT,
                "伪路径对=%d  跨时钟域对=%d  未覆盖路径对=%d%n",
                s.falsePathPairs, s.cdcPairs, s.uncoveredPairs));
        if (s.worstSetupEndpoint != null) {
            sb.append(String.format(Locale.ROOT,
                    "最差建立 slack=%.3f ns @ %s ; 最差保持 slack=%.3f ns @ %s%n",
                    s.worstSetupSlack, s.worstSetupEndpoint,
                    s.worstHoldSlack, s.worstHoldEndpoint));
        }
        sb.append("----------------------------------------------------------------\n");
        sb.append("保持违例端点清单（").append(r.holdViolations.size()).append(" 个，按保持 slack 升序）:\n");
        for (TimingReport.EndpointResult hv : r.holdViolations) {
            sb.append(String.format(Locale.ROOT,
                    "  * hold slack=%.3f ns  端点 %s  单元 %s  违例引脚 %s%n",
                    hv.worstHoldSlack, hv.endpoint, hv.cell,
                    hv.worstHoldPair != null && !hv.worstHoldPair.holdDataPath.isEmpty()
                            ? hv.worstHoldPair.holdDataPath.get(hv.worstHoldPair.holdDataPath.size() - 1)
                            : hv.endpoint));
        }
        sb.append("----------------------------------------------------------------\n");
        for (TimingReport.EndpointResult ep : r.endpoints) {
            sb.append("端点 ").append(ep.endpoint).append("  单元: ").append(ep.cell)
                    .append("  结论: ").append(ep.status).append('\n');
            if (ep.worstSetupSlack != null) {
                renderWorst(sb, "setup", ep, ep.worstSetupPair);
                renderWorst(sb, "hold ", ep, ep.worstHoldPair);
            }
            if (!ep.note.isEmpty()) {
                sb.append("  备注: ").append(ep.note).append('\n');
            }
            for (TimingReport.PathPair p : ep.pairs) {
                sb.append("  [").append(p.category).append("] ")
                        .append(p.startpoint).append(" -> ").append(ep.endpoint);
                if (p.launchClock != null || p.captureClock != null) {
                    sb.append("  时钟 ").append(p.launchClock).append("=>").append(p.captureClock);
                }
                if (p.setupSlack != null) {
                    sb.append(String.format(Locale.ROOT,
                            "  arrival=%.3f setup_req=%.3f hold_req=%.3f setup_slack=%.3f hold_slack=%.3f",
                            p.arrival, p.setupRequired, p.holdRequired,
                            p.setupSlack, p.holdSlack));
                }
                if (p.exception != null) {
                    sb.append("  例外: ").append(p.exception);
                }
                sb.append('\n');
            }
        }
        sb.append("==============================================================\n");
        return sb.toString();
    }

    private static void renderWorst(StringBuilder sb, String label,
                                    TimingReport.EndpointResult ep, TimingReport.PathPair p) {
        if (p == null) {
            return;
        }
        boolean setup = label.startsWith("setup");
        List<String> derates = setup ? p.setupDerates : p.holdDerates;
        double arrival = setup ? p.arrival : p.holdArrival;
        double required = setup ? p.setupRequired : p.holdRequired;
        double slack = setup ? p.setupSlack : p.holdSlack;
        double uncertainty = setup ? p.setupUncertainty : p.holdUncertainty;
        String uSource = setup ? p.setupUncertaintySource : p.holdUncertaintySource;
        List<String> path = setup ? p.dataPath : p.holdDataPath;
        sb.append(String.format(Locale.ROOT,
                "  最差 %s: 起点 %s  时钟 %s=>%s  arrival=%.3f required=%.3f slack=%.3f%n",
                label, p.startpoint, p.launchClock, p.captureClock, arrival, required, slack));
        sb.append("    路径: ").append(String.join(" -> ", path)).append('\n');
        sb.append(String.format(Locale.ROOT,
                "    不确定度=%.3f ns[%s]  降额: %s%n",
                uncertainty, uSource,
                derates.isEmpty() ? "(无生效降额)" : String.join(" ; ", derates)));
        sb.append("    例外: ").append(p.exception != null ? p.exception : "(无)").append('\n');
    }
}
