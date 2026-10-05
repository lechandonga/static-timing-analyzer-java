package com.github.lechandonga.statictiminganalyzerjava.sta;

import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingReport;
import com.github.lechandonga.statictiminganalyzerjava.sta.service.ReportRenderer;
import com.github.lechandonga.statictiminganalyzerjava.sta.service.StaService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 命令行入口：以参数 --sta.run 启动时执行一次本地分析并退出，
 * 用法：--sta.run --sta.library=a.lib --sta.netlist=a.net --sta.constraints=a.sdc
 */
@Component
public class StaCliRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("sta.run")) {
            return;
        }
        String lib = require(args, "sta.library");
        String net = require(args, "sta.netlist");
        String sdc = require(args, "sta.constraints");
        TimingReport report = new StaService().analyze(Path.of(lib), Path.of(net), Path.of(sdc));
        System.out.println(ReportRenderer.render(report));
        System.exit(0);
    }

    private String require(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new StaException("CLI_ARG_MISSING", name, 0, "缺少命令行参数 --" + name);
        }
        return values.get(0);
    }
}
