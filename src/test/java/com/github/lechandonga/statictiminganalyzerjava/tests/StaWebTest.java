package com.github.lechandonga.statictiminganalyzerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 入口测试：确认不确定度/降额/保持违例清单等新信息在接口返回中可见。 */
@SpringBootTest
@AutoConfigureMockMvc
class StaWebTest {

    private static final Logger log = LoggerFactory.getLogger("STA-BUSINESS");

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("B12-1 HTTP 入口：保持违例清单与最差路径完整数据可见")
    void httpExposesNewReportFields() throws Exception {
        log.info("[B12 HTTP 入口] POST /sta/analyze 用例 hold_probe");
        String body = """
                {"library":"src/test/resources/cases/hold_probe/cells.lib",
                 "netlist":"src/test/resources/cases/hold_probe/design.net",
                 "constraints":"src/test/resources/cases/hold_probe/constraints.sdc"}
                """;
        mvc.perform(post("/sta/analyze").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.holdViolations").value(2))
                .andExpect(jsonPath("$.summary.holdViolationEndpoints[0]").value("pin:U2/D"))
                .andExpect(jsonPath("$.summary.holdViolationEndpoints[1]").value("port:OUT1"))
                .andExpect(jsonPath("$.endpoints[?(@.endpoint=='pin:U2/D')].worstHoldSlack").value(-0.42))
                .andExpect(jsonPath("$.endpoints[?(@.endpoint=='pin:U2/D')].worstHold.holdUncertainty").exists())
                .andExpect(jsonPath("$.endpoints[?(@.endpoint=='pin:U2/D')].worstHold.derateEarly").exists());
        log.info("[B12 HTTP 入口] 结果：summary 与端点级新字段均可见 ✓");
    }
}
