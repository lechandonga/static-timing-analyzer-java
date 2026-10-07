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
| B12 | HTTP 入口：保持清单、同时违例双侧计数、CDC 多周期保持在 JSON 中可见，与文本一致 |
| B13 | 建立+保持同时违例：双违例端点双侧计数/清单/最差项对账；仅建立违例不误入保持清单 |
| B14 | 跨时钟域多周期保持按发射周期口径：短路径该违例、缓冲对照不违例、不确定度计入、重排一致 |

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

## 探针验证（建立/保持同时违例 与 跨时钟域多周期保持）

### 1. 建立+保持同时违例（`src/test/resources/cases/both_violate/`）

输出端口 OUT1 的输出延时取 0.95 ns（周期 1.0 ns）：

- setup required = 1.0 − 0.95 = 0.05 ns，数据到达 0.08 ns ⇒ setup slack = −0.03 ns；
- hold  required = 0.95 ns，early 到达 0.08 ns ⇒ hold slack = −0.87 ns。

同一端点两类裕量同时为负，结论为 `SETUP_AND_HOLD_VIOLATED`，同时计入建立/保持计数并进入
保持违例端点清单；同用例 `pin:U2/D` 仅保持为负（`HOLD_VIOLATED`），`pin:U4/D` 经 0.8 ns
缓冲保持 MET。运行：

```bash
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar --sta.run \
  --sta.library=src/test/resources/cases/both_violate/cells.lib \
  --sta.netlist=src/test/resources/cases/both_violate/design.net \
  --sta.constraints=src/test/resources/cases/both_violate/constraints.sdc
```

### 2. 跨时钟域多周期保持（`src/test/resources/cases/cdc_mc_hold/`）

发射时钟 CLK1=1 ns、捕获时钟 CLK2=8 ns（捕获周期远大于保持时间），两条路径都声明
`set_multicycle_path 2` 并加 0.05 ns 保持不确定度。保持检查按通行口径移动发射边沿：

- hold required = −(2−1)×1.0 + 1.20（捕获寄存器保持时间）+ 0.05 = 0.25 ns；
- `pin:U2/D` 为 Q 直达（early 到达 0.08 ns）⇒ hold slack = −0.17 ns，报保持违例；
- `pin:U5/D` 中间有 0.30 ns 缓冲（early 到达 0.38 ns）⇒ hold slack = +0.13 ns，MET；
- 建立方向仍按捕获时钟：setup required = 2×8 − 0.05 = 15.95 ns，两条路径均不违例。

旧口径在此量级下用捕获周期（−8+1.20+0.05=−6.75 ns）永远得到正保持裕量；改用发射周期后
该报的违例报得出。把同一组数值（T=1、N=2）放回同时钟多周期路径，hold required 同为
0.25 ns，两个域口径一致。运行参数把上例目录换成 `cdc_mc_hold` 即可。

## 兼容范围

- 不写任何不确定度/降额声明、也不涉及多周期例外的老输入：分析结论与此前版本逐字节一致；
- 单周期（默认）建立/保持口径不变；同时钟多周期路径口径不变；
- 行为变化仅两处：跨时钟域**多周期**路径的保持检查改用发射时钟周期；
  同一端点建立/保持同时为负时新增 `SETUP_AND_HOLD_VIOLATED` 结论并双侧计数。
