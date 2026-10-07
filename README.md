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
- 未约束端点、跨时钟域（CDC）路径对单独归类，不计通过也不计违例；逐端点结论与汇总统计一致；
- 组合环用迭代 Tarjan SCC 检测并报告环节点，分析不死循环、不产生伪正常结论；
- 伪路径 / 多周期路径按“粒度最大、伪路径优先、冲突报错”的确定规则生效（见文档第 5 节）；
- 时钟不确定度支持全局/时钟/路径三级，建立扣减、保持增加，同层取最大、与声明顺序无关；
- 时序降额支持全局/单元类型/实例三级与早/晚角分离，clk_to_q 同步降额，最差路径报告可见生效系数；
- 每个端点给出最差 setup/hold 路径对的完整签核数据（到达/要求/裕量/不确定度/降额/例外），
  保持违例端点单独列出；
- 建立与保持同时违例的端点结论为 `SETUP_HOLD_VIOLATED`，双向计入统计并进入保持违例清单；
- 跨时钟域路径经 `set_multicycle_path` 放开后，保持检查与同时钟同口径（含保持不确定度），
  该报的保持违例会进入清单与统计；
- 全部排序输出，重复分析与声明顺序调整结果逐字节一致。

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

返回 JSON：`status`（`OK`/`COMBINATIONAL_CYCLE`）、`summary`、`endpoints`、`cycles` 与文本报告 `text`。
输入非法时返回 HTTP 400 与可区分的 `errorCode/source/line/message`。

## 单元测试（含业务覆盖日志）

```bash
mvn -o test
# 只看业务日志：
mvn -o test -Dtest=StaBusinessTest 2>&1 | grep "STA-BUSINESS"
```

`StaBusinessTest` 按业务分组（日志标签 B1–B7）：

| 分组 | 场景 |
| --- | --- |
| B1 | 正常 setup/hold、最差路径定位、汇总与逐端点对账 |
| B2 | 组合环检测与环节点定位 |
| B3 | 未约束端点 / 未覆盖路径对单列 |
| B4 | 跨时钟域默认归 CDC、多周期例外按捕获时钟放宽 |
| B5 | 伪路径 × 多周期同粒度冲突 → `EXCEPTION_CONFLICT` |
| B6 | 非法单位 / 单元引用缺失 / 时钟源缺失，错误码可区分 |
| B7 | 重复分析逐字节一致；约束声明顺序调整不影响结论 |
| B8 | 时钟不确定度：setup/hold 两个方向落点、三级优先级、乱序一致、非法声明、多周期 CDC 叠加 |
| B9 | 时序降额：早/晚角分离、实例>类型>全局覆盖、clk_to_q 降额、非法声明 |
| B10 | 保持违例探针：hold≈0.5ns 寄存器与输出延时>到达的输出路径报出负 slack 并定位 |
| B11 | 2000 级深组合链回归：不爆栈、叠加新规则结论正确 |
| B12 | HTTP 入口：保持违例清单与最差路径完整数据在 JSON 中可见 |
| B13 | 建立+保持同时违例：端点结论双向可见，清单/计数/最差值互相对账 |
| B14 | 跨时钟域多周期保持：违例报出并入清单、对照路径不误报、不确定度计入、乱序一致 |

## 探针验证（本地复现保持违例）

`src/test/resources/cases/hold_probe/` 是一个可直接运行的探针：
自定义库把寄存器 `hold` 提到 0.5 ns，网表包含一条 Q 直达 D 的近零到达路径、
一条输出延时（0.5 ns）大于数据到达（0.08 ns）的输出路径，以及一条到达足够大的对照路径。
运行方式（任选一个入口）：

```bash
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar --sta.run \
  --sta.library=src/test/resources/cases/hold_probe/cells.lib \
  --sta.netlist=src/test/resources/cases/hold_probe/design.net \
  --sta.constraints=src/test/resources/cases/hold_probe/constraints.sdc
```

预期：`pin:U2/D` 与 `port:OUT1` 报 `HOLD_VIOLATED`、slack=-0.42 ns，
汇总区“保持违例端点”列出两者，对照路径 `pin:U4/D` 保持 MET。

## 探针验证（同时违例与跨时钟域多周期保持）

`src/test/resources/cases/both_violated/`：自定义库中 `DFFSH`（setup=0.95, hold=0.5）
使 `pin:U2/D` 建立、保持同时不达标，`DFFS`（hold=0.02）使 `pin:U3/D` 仅建立违例。
预期：`pin:U2/D` 结论 `SETUP_HOLD_VIOLATED`，建立违例数=2、保持违例数=1，
保持违例清单仅含 `pin:U2/D`，`pin:U3/D` 不混入。

`src/test/resources/cases/cdc_mcp_hold/`：CLK1(1.0ns)→CLK2(0.8ns) 两条跨时钟域路径均以
`set_multicycle_path 2` 放开，并声明 0.05ns 保持不确定度。
预期：`pin:U3/D`（hold=0.5 寄存器）保持要求 = 1×0.8+0.5+0.05 = 1.35ns，裕量 -0.97ns 报
`HOLD_VIOLATED` 并进入清单；`pin:U6/D`（慢路径）裕量 +0.01ns 保持 MET。

两个用例均可按上一节的方式用命令行 JAR 运行，或启动 HTTP 服务后 POST `/sta/analyze`，
两个入口的结论（`summary` / `endpoints[].status` / 文本报告 `text`）一致。

## 兼容范围

- 不含不确定度/降额声明、也不涉及多周期例外的输入，分析结论与旧版本完全一致；
- 本次仅修正两处缺陷口径：同时违例端点的归类（旧版只记建立违例、漏记保持违例）、
  跨时钟域多周期路径的保持要求符号（旧版恒为负、永远查不出保持违例）；
  涉及这两类场景的旧结论会按正确口径变化，属于缺陷修复而非行为回归。
