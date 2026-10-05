package com.github.lechandonga.statictiminganalyzerjava.sta.web;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingReport;
import com.github.lechandonga.statictiminganalyzerjava.sta.service.ReportRenderer;
import com.github.lechandonga.statictiminganalyzerjava.sta.service.StaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/** 本地 HTTP 入口：POST /sta/analyze，请求体给出三份文件的本地路径。 */
@RestController
@RequestMapping("/sta")
public class StaController {

    private final StaService service = new StaService();

    public record AnalyzeRequest(String library, String netlist, String constraints) {
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest req) {
        if (req.library() == null || req.netlist() == null || req.constraints() == null) {
            throw new StaException("SYNTAX_ERROR", "request", 0,
                    "必须同时提供 library/netlist/constraints 三个本地文件路径");
        }
        TimingReport report = service.analyze(
                Path.of(req.library()), Path.of(req.netlist()), Path.of(req.constraints()));
        return Map.of(
                "status", report.status.name(),
                "summary", report.summary,
                "endpoints", report.endpoints,
                "cycles", report.cycles,
                "text", ReportRenderer.render(report));
    }

    @ExceptionHandler(StaException.class)
    public ResponseEntity<Map<String, String>> handle(StaException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "errorCode", e.getCode(),
                "source", e.getSource(),
                "line", String.valueOf(e.getLine()),
                "message", e.getMessage()));
    }
}
