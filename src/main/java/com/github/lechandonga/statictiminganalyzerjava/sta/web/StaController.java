package com.github.lechandonga.statictiminganalyzerjava.sta.web;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.StaService;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.CombinationalLoopException;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.ReportRenderer;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingReport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local HTTP entry point for the analyzer. */
@RestController
@RequestMapping("/api/sta")
public class StaController {

    public static final class FileRequest {
        public String library;
        public String netlist;
        public String constraints;
        public boolean textReport = true;
    }

    @PostMapping
    public Map<String, Object> analyze(@RequestBody FileRequest request) {
        if (request.library == null || request.netlist == null
                || request.constraints == null) {
            throw new StaException(StaException.Code.FORMAT_ERROR,
                    "request must provide 'library', 'netlist' and 'constraints' file paths");
        }
        TimingReport report = StaService.analyzeFiles(
                Path.of(request.library), Path.of(request.netlist),
                Path.of(request.constraints));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("report", report);
        if (request.textReport) {
            response.put("text", ReportRenderer.renderText(report));
        }
        return response;
    }

    @ExceptionHandler(CombinationalLoopException.class)
    public ResponseEntity<Map<String, Object>> loop(CombinationalLoopException error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorBody(error));
    }

    @ExceptionHandler(StaException.class)
    public ResponseEntity<Map<String, Object>> failure(StaException error) {
        HttpStatus status = error.getCode() == StaException.Code.COMBINATIONAL_LOOP
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(errorBody(error));
    }

    private Map<String, Object> errorBody(StaException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", error.getCode().name());
        body.put("message", error.getMessage());
        if (error instanceof CombinationalLoopException loopException) {
            body.put("loops", loopException.getLoops());
        }
        return body;
    }
}
