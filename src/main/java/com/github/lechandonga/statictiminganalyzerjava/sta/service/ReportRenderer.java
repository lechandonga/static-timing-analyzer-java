package com.github.lechandonga.statictiminganalyzerjava.sta.service;

import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingReport;

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
        for (TimingReport.EndpointResult ep : r.endpoints) {
            sb.append("端点 ").append(ep.endpoint).append("  单元: ").append(ep.cell)
                    .append("  结论: ").append(ep.status).append('\n');
            if (ep.worstSetupSlack != null) {
                sb.append(String.format(Locale.ROOT,
                        "  setup slack=%.3f ns, hold slack=%.3f ns%n",
                        ep.worstSetupSlack, ep.worstHoldSlack));
                sb.append("  最差 setup 路径: ").append(String.join(" -> ", ep.worstSetupPath)).append('\n');
                sb.append("  最差 hold  路径: ").append(String.join(" -> ", ep.worstHoldPath)).append('\n');
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
}
