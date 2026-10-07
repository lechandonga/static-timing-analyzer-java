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

    @Test
    @DisplayName("B12-2 HTTP 入口：建立+保持同时违例端点双侧计数，文本报告与 JSON 结论一致")
    void httpExposesSetupAndHoldViolation() throws Exception {
        log.info("[B12 HTTP 入口] POST /sta/analyze 用例 both_violate");
        String body = """
                {"library":"src/test/resources/cases/both_violate/cells.lib",
                 "netlist":"src/test/resources/cases/both_violate/design.net",
                 "constraints":"src/test/resources/cases/both_violate/constraints.sdc"}
                """;
        String resp = mvc.perform(post("/sta/analyze").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.setupViolations").value(1))
                .andExpect(jsonPath("$.summary.holdViolations").value(2))
                .andExpect(jsonPath("$.summary.holdViolationEndpoints[0]").value("pin:U2/D"))
                .andExpect(jsonPath("$.summary.holdViolationEndpoints[1]").value("port:OUT1"))
                .andExpect(jsonPath("$.summary.worstHoldSlack").value(-0.87))
                .andExpect(jsonPath("$.summary.worstHoldEndpoint").value("port:OUT1"))
                .andExpect(jsonPath("$.endpoints[?(@.endpoint=='port:OUT1')].status")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("SETUP_AND_HOLD_VIOLATED"))))
                .andReturn().getResponse().getContentAsString();
        // HTTP 返回自带文本报告，其中端点结论行与 JSON 一致
        org.junit.jupiter.api.Assertions.assertTrue(resp.contains("SETUP_AND_HOLD_VIOLATED"),
                "text 字段必须包含同时违例结论: " + resp);
        org.junit.jupiter.api.Assertions.assertTrue(resp.contains("保持违例端点: pin:U2/D, port:OUT1"));
        log.info("[B12 HTTP 入口] 结果：同时违例双侧计数、清单与文本结论一致 ✓");
    }

    @Test
    @DisplayName("B12-3 HTTP 入口：跨时钟域多周期保持违例按发射周期口径报出并计入清单")
    void httpExposesCdcMulticycleHoldViolation() throws Exception {
        log.info("[B12 HTTP 入口] POST /sta/analyze 用例 cdc_mc_hold");
        String body = """
                {"library":"src/test/resources/cases/cdc_mc_hold/cells.lib",
                 "netlist":"src/test/resources/cases/cdc_mc_hold/design.net",
                 "constraints":"src/test/resources/cases/cdc_mc_hold/constraints.sdc"}
                """;
        mvc.perform(post("/sta/analyze").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.holdViolations").value(1))
                .andExpect(jsonPath("$.summary.holdViolationEndpoints[0]").value("pin:U2/D"))
                .andExpect(jsonPath("$.summary.worstHoldEndpoint").value("pin:U2/D"))
                .andExpect(jsonPath("$.endpoints[?(@.endpoint=='pin:U2/D')].status")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("HOLD_VIOLATED"))))
                .andExpect(jsonPath("$.endpoints[?(@.endpoint=='pin:U5/D')].status")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("MET"))));
        log.info("[B12 HTTP 入口] 结果：CDC 多周期保持违例 JSON/清单/最差项一致，对照路径 MET ✓");
    }
}
