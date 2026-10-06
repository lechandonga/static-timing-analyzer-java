# Static Timing Analyzer（本地静态时序分析）

一个只依赖 Spring Boot 自带组件（`spring-boot-starter-web` / `spring-boot-starter-test`）的本地静态时序分析器：
用三份本地文本文件模拟单元库、网表与时序约束，输出可复现、可解释的 setup/hold 结论。
不引入任何新的三方库，不依赖外部 EDA 工具或网络服务。

## 文档

- 输入文件格式与全部错误码：`docs/input-format.md`
- 时序计算、跨时钟域、例外优先级与组合环规则：`docs/timing-rules.md`
- 可直接运行的样例：`src/main/resources/samples/`（`cells.lib` / `design.net` / `constraints.sdc`）

## 能力概览

- 严格解析：重复定义、引用缺失、非法单位/取值、线网无驱动/多驱动均以独立错误码失败，不静默兜底；
- 按时钟域计算每个端点的 arrival / required / setup slack / hold slack，给出定位到单元引脚的最差路径；
- 时钟不确定度支持全局 / 单时钟 / 路径级（`set_clock_uncertainty`），路径级 > 时钟级 > 全局，
  建立悲观扣减、保持悲观加回，输出端口与多周期放开的 CDC 路径同样生效；
- 时序降额支持全局 / 单元类型 / 实例三级（`set_timing_derate`），实例 > 单元 > 全局，
  早角供保持、晚角供建立，寄存器 clk->q 随角缩放，报告列出每个单元最终生效系数；
- 签核报告：每端点分别给出最差 setup/hold 的到达、要求、裕量、路径及不确定度/降额/例外来源，
  另单列全部保持违例端点，命令行文本与 HTTP JSON（`holdViolations` 等）同源；
- 未约束端点、跨时钟域（CDC）路径对单独归类，不计通过也不计违例；逐端点结论与汇总统计一致；
- 组合环用迭代 Tarjan SCC 检测并报告环节点，分析不死循环、不产生伪正常结论；
- 伪路径 / 多周期路径按“粒度最大、伪路径优先、冲突报错”的确定规则生效（见文档第 5 节）；
- 全部排序输出，重复分析与声明顺序调整结果逐字节一致；不写新声明的老输入无需改动即可分析。

## 本地运行

前置：JDK 21+、Maven（依赖已在本机仓库时可加 `-o` 离线运行）。

### 1. 命令行一次性分析

```bash
mvn -o package -DskipTests
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar --sta.run \
  --sta.library=src/main/resources/samples/cells.lib \
  --sta.netlist=src/main/resources/samples/design.net \
  --sta.constraints=src/main/resources/samples/constraints.sdc
```

### 2. HTTP 接口

```bash
mvn -o spring-boot:run
curl -s -X POST localhost:8080/sta/analyze \
  -H 'Content-Type: application/json' \
  -d '{"library":"src/main/resources/samples/cells.lib",
       "netlist":"src/main/resources/samples/design.net",
       "constraints":"src/main/resources/samples/constraints.sdc"}'
```

返回 JSON：`status`（`OK`/`COMBINATIONAL_CYCLE`）、`summary`、`endpoints`、
`holdViolations`（保持违例端点清单）、`cycles` 与文本报告 `text`。
输入非法时返回 HTTP 400 与可区分的 `errorCode/source/line/message`。

## 单元测试（含业务覆盖日志）

```bash
mvn -o test
# 只看业务日志：
mvn -o test -Dtest=StaBusinessTest 2>&1 | grep "STA-BUSINESS"
```

`StaBusinessTest` 按业务分组（日志标签 B1–B12），不跳过任何既有用例：

| 分组 | 场景 |
| --- | --- |
| B1 | 正常 setup/hold、最差路径定位、汇总与逐端点对账 |
| B2 | 组合环检测与环节点定位 |
| B3 | 未约束端点 / 未覆盖路径对单列 |
| B4 | 跨时钟域默认归 CDC、多周期例外按捕获时钟放宽 |
| B5 | 伪路径 × 多周期同粒度冲突 → `EXCEPTION_CONFLICT` |
| B6 | 非法单位 / 单元引用缺失 / 时钟源缺失，错误码可区分 |
| B7 | 重复分析逐字节一致；约束声明顺序调整不影响结论 |
| B8 | 不确定度：建立扣减/保持加回、路径>时钟>全局、CDC 多周期与非法声明 |
| B9 | 降额：早晚角、实例>单元>全局、clk->q 随角、老输入兼容、非法声明 |
| B10 | 保持违例探针：hold≈0.5 短路径、输出延时路径报负 slack 并定位，长路径不误报 |
| B11 | 1200 单元深组合链性能/栈深度回归 |
| B12 | 新约束声明顺序无关；HTTP 入口暴露全部签核字段 |
