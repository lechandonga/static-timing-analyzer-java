package com.github.lechandonga.statictiminganalyzerjava.sta.web;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.StaService;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.CombinationalLoopException;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.ReportRenderer;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingReport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Local command-line entry: run with
 * {@code --sta=<library.lib>,<netlist.gate>,<constraints.sdc>}.
 * Exit code is 0 for a clean run (violations included), 2 for input failures and 3 for
 * combinational loops. Without the option the application starts as a normal web service.
 */
@Component
public class StaCommandLineRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues("sta");
        if (values == null || values.isEmpty()) {
            return;
        }
        String[] files = values.get(0).split(",");
        if (files.length != 3) {
            System.err.println("usage: --sta=<library>,<netlist>,<constraints>");
            System.exit(2);
        }
        try {
            TimingReport report = StaService.analyzeFiles(
                    Path.of(files[0]), Path.of(files[1]), Path.of(files[2]));
            System.out.print(ReportRenderer.renderText(report));
            System.exit(0);
        } catch (CombinationalLoopException error) {
            System.err.println(error.getMessage());
            System.exit(3);
        } catch (StaException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }
}
