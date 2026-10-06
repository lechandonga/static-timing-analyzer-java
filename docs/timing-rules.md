# 时序计算与例外处理规则

## 1. 模型与节点

- 时序图节点：单元引脚（`实例/引脚`）与主输入/主输出端口。
- 起点（startpoint）：主输入端口、时序单元 `Q` 脚。
- 终点（endpoint）：主输出端口、时序单元 `D` 脚。
- 组合单元按库延时弧产生有向边；线网延时为 0。
- 时钟从 `create_clock` 的主输入沿组合单元传播（理想时钟，0 延时），到达时序单元 `clk` 脚即确定该寄存器的时钟域；
  时钟路径上的组合弧不参与数据路径。不同时钟在同一节点汇合报 `CLOCK_CONFLICT`。

## 2. 到达时间（arrival，晚角/早角各算一次）

每条起点→终点路径对分别传播两次：

- **晚角（late）**：建立检查使用，沿最长路径（max）传播；
- **早角（early）**：保持检查使用，沿最短路径（min）传播。

基础到达时间：

- 带 `set_input_delay d` 的主输入：arrival = d（片外数据，**不参与降额**）；其余主输入：0，且没有发射时钟；
- 时序单元 `Q` 脚：基础 arrival = 库参数 `clk_to_q`，发射时钟 = 该单元 `clk` 脚传播到的时钟；
  `clk_to_q` 与组合单元延时一样按对应角的降额系数缩放；
- 每个单元在路径上的延时 = 库延时 × 该单元在该角生效的降额系数（见第 5a 节）。

同角并列时按前驱节点名取字典序最小，保证最差路径唯一、可复现。

## 3. 建立 / 保持检查（同一时钟域）

对寄存器 `D` 脚（setup 库值 `su`，hold 库值 `h`，发射/捕获时钟周期 `T`，
生效时钟不确定度 `U`，见第 5b 节）：

```
setup required = N_setup * T - su - U
hold  required = N_hold  * T + h  + U
setup slack = setup required - arrival(晚角)
hold  slack = arrival(早角) - hold required
```

对主输出端口（`set_output_delay = d`，时钟周期 `T`）：

```
setup required = N_setup * T - d - U
hold  required = N_hold  * T + d + U
```

**保持检查口径**：`hold`/输出延时是下一拍数据必须躲开的最小间隔，因此加在 required 一侧
（旧实现曾错误地从 required 中扣减库 hold 时间，导致保持 slack 偏大一个 hold 值，已修正）。
默认 `N_setup = 1`、`N_hold = 0`，建立与保持使用同一套多周期参数（见第 5 节）。
不确定度方向恒为悲观：建立扣减、保持加回。
判定阈值：slack < -1e-9 ns 记为违例（`SETUP_VIOLATED` / `HOLD_VIOLATED`，setup 优先）。
建立与保持同时违例记 `SETUP_AND_HOLD_VIOLATED`，在建立/保持两个汇总计数中各计一次。

## 4. 跨时钟域（CDC）

同一路径对的发射时钟与捕获时钟不同即为跨时钟域：

- 默认归类为 `CDC`：**不计算 slack、不计入通过/违例统计**，只在端点备注中给出路径对数；
- 用 `set_multicycle_path N -from <时钟A> -to <端点>` 显式声明后才进行检查：
  - setup required = N × 捕获时钟周期 − su − U（输出端口为 − d − U）
  - hold required = (N−1) × 捕获时钟周期 + h + U（输出端口为 (N−1)×T + d + U）

即：**靠多周期例外放开后才受约束的 CDC 路径，不确定度与降额同样必须生效**，不允许漏算。
时钟级不确定度取发射/捕获两侧相关值的悲观最大值。

端点级状态：只要存在受约束路径对，端点由受约束对决定；没有任何受约束对、但存在 CDC 对时记 `CDC`；
其余记 `UNCONSTRAINED`（发射或捕获侧缺少时钟/IO 延时的路径对）。未约束与 CDC 端点既不计通过也不计违例。

## 5. 例外的匹配、优先级与冲突

`set_false_path` 与 `set_multicycle_path` 的匹配方式相同：

1. 对一条“起点→终点”路径对，分别计算 `-from` 与起点、`-to` 与终点的匹配粒度：
   - 不匹配 = 0；
   - 引用为时钟名 / 端口名 = 1（时钟匹配该侧实际时钟，端口匹配同名主输入/输出）；
   - 引用为具体单元引脚且与该侧节点同名 = 2。
2. 两侧都 >0 时规则适用；先按 from 粒度、再按 to 粒度取最大，粒度最大者胜出。
3. **优先级（确定性）**：同粒度下 `set_false_path` 优先于 `set_multicycle_path`。
   - 同粒度同时存在伪路径与多周期规则 → 直接报错 `EXCEPTION_CONFLICT`（不静默选择）；
   - 同粒度存在倍数不同的多条 multicycle 规则 → 报 `EXCEPTION_CONFLICT`；
   - 等价的重复声明在解析期以 `DUPLICATE_DEFINITION` 拒绝。
4. 伪路径命中：路径对归类 `FALSE_PATH`，不计算 slack、不计违例、不计通过。
5. 多周期命中：按 N_setup=N、N_hold=N−1 放宽/收紧，报告中记录命中的规则原文。

## 5a. 时序降额（timing derate）的粒度与角

`set_timing_derate` 模拟同一单元在不同批次/条件下的快慢差异：

1. 三种粒度，高粒度覆盖低粒度：**实例（`-instance`） > 单元类型（`-cell`） > 全局**。
   不同粒度的声明不是冲突，而是按此优先级选用（例如给 BUF 声明 1.1、又给实例 U2 声明 1.3，
   则 U2 用 1.3、其它 BUF 用 1.1、其余单元用全局）。
2. 早/晚角独立：建立检查（max 传播）用晚角 `-late`，保持检查（min 传播）用早角 `-early`；
   单个裸系数表示早晚相同；只写一侧时另一侧取同值。
3. 作用对象为片内延时：组合单元的库延时弧、时序单元的 `clk_to_q`；
   `set_input_delay`/`set_output_delay` 是片外数据，恒不缩放。
4. 同一目标（全局 / 某单元类型 / 某实例）只允许一条声明，早晚角必须在同一条中给出；
   重复为 `DUPLICATE_DEFINITION`，系数必须为大于 0 的有限数。
5. 报告在每条最差路径上列出沿路每个单元最终生效的系数与来源粒度，形如
   `U2[BUF]*1.300(late:instance:U2)`；无降额声明时全部为 `*1.000(...:global)`。

## 5b. 时钟不确定度（clock uncertainty）的粒度与叠加规则

1. 三种粒度：路径级（`-from/-to`）> 时钟级（`[get_clocks]`）> 全局（裸值）。
   路径级的匹配分值、仲裁方式与第 5 节的例外**完全相同**（时钟/端口=1，单元引脚=2，
   先比 from 粒度再比 to 粒度），因此一个引脚级路径声明天然压过时钟级与全局声明。
2. 时钟级对路径的发射时钟与捕获时钟都相关：两侧都命中时取悲观最大值；
   该规则只依赖数值大小，不依赖声明顺序。
3. 同粒度冲突：同一对 `(from,to)` 的重复路径声明在解析期以 `DUPLICATE_DEFINITION` 拒绝；
   全局/单时钟值同样只允许声明一次。分析器不会静默挑一个或回退默认值。
4. 与例外叠加：
   - 伪路径、未放开的纯 CDC、未约束路径对：不做检查，因此不应用不确定度；
   - 受约束路径对（含多周期放开的 CDC）：建立扣减、保持加回，来源（`path(...)`/`clock(...)`/
     `global`）写入报告。

## 6. 组合环

在“数据图”（去掉时钟路径弧）上用迭代式 Tarjan 算法求强连通分量；节点数 >1 的 SCC 或自环即为组合环。
检测到任何环时：

- 分析状态置为 `COMBINATIONAL_CYCLE`，报告给出按名字排序、可直接定位到单元引脚的环节点序列；
- 不输出任何看似正常的 slack/端点结论；分析器不死循环、不依赖递归栈深度。

## 7. 可复现性

- 所有端点、路径对、环按名称排序输出；并列延时按字典序打破平局；
- 约束的时钟引用在“全部解析完成后”统一校验，分析结论与声明顺序无关；
- 分析过程不读取可变全局状态，同一份输入重复分析得到逐字节一致的文本报告。

## 8. 签核报告内容

每个端点分别给出最差 setup 与最差 hold 两条路径的完整数据（命令行文本与 HTTP JSON 同源）：

- 起点、发射/捕获时钟；
- arrival（setup=晚角、hold=早角）、required、slack；
- 实际生效的时钟不确定度数值与来源粒度；
- 沿路每个单元最终生效的降额系数（早/晚角各自一份）与覆盖来源；
- 命中的例外（伪路径/多周期）或“(无)”；
- 完整引脚级路径序列，可直接定位到具体单元和端点引脚。

报告顶部另有独立的“保持违例端点清单”，按 hold slack 升序列出全部保持违例端点
（含同时违例），HTTP JSON 对应字段 `holdViolations`；其条数与 `summary.holdViolations`
及逐端点状态严格一致。

## 9. 本地探针验证（如何造出保持违例输入）

测试资源 `src/test/resources/cases/holdprobe/` 提供可复现探针，三种情形同时覆盖：

1. **短寄存器路径**：单元库把捕获寄存器的保持时间调大（`timing ... hold=0.50 ...`），
   数据起点用小的输入延时（如 0.10）直接进 D 脚：hold required = 0.50，arrival = 0.10，
   hold slack = −0.40，报 `HOLD_VIOLATED` 并定位到该寄存器 D 脚；
2. **输出延时大于数据到达的输出路径**：`set_output_delay 0.10` 的端口由
   `clk_to_q=0.02` 的寄存器经一个 0.05 反相器驱动（到达 0.07）：
   hold slack = 0.07 − 0.10 = −0.03，输出端口同样必须报保持违例；
3. **到达足够大的路径不误报**：同网表中用 0.80 的慢单元驱动另一个输出端口
   （到达 0.88+），以及 0.07 到达对普通 hold=0.02 寄存器的路径，结论必须是 `MET`。

运行：

```bash
mvn -o test                      # B10 用例断言上述 slack、定位与清单一致性
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar --sta.run \
  --sta.library=src/test/resources/cases/cells.lib \
  --sta.netlist=src/test/resources/cases/holdprobe/design.net \
  --sta.constraints=src/test/resources/cases/holdprobe/constraints.sdc
```

## 10. 兼容范围

- 三份输入文件格式只做增量扩展：不出现 `set_clock_uncertainty` / `set_timing_derate` 时，
  不确定度按 0、全部降额系数按 1.0 处理，老输入文件一个字不改即可继续分析；
- 唯一的数值口径修正是第 3 节的保持检查（库 hold 时间改为加在 required 侧）；
  该修正在任何降额/不确定度声明下都生效，使探针描述的违例能被正确报出；
- 命令行参数、HTTP 请求/错误响应结构保持不变，新增信息仅做字段追加
  （`holdViolations`、`worstSetupPair/worstHoldPair`、早角 arrival、不确定度与降额明细）。
