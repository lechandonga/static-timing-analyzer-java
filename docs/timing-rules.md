# 时序计算与例外处理规则

## 1. 模型与节点

- 时序图节点：单元引脚（`实例/引脚`）与主输入/主输出端口。
- 起点（startpoint）：主输入端口、时序单元 `Q` 脚。
- 终点（endpoint）：主输出端口、时序单元 `D` 脚。
- 组合单元按库延时弧产生有向边；线网延时为 0。
- 时钟从 `create_clock` 的主输入沿组合单元传播（理想时钟，0 延时），到达时序单元 `clk` 脚即确定该寄存器的时钟域；
  时钟路径上的组合弧不参与数据路径。不同时钟在同一节点汇合报 `CLOCK_CONFLICT`。

## 2. 到达时间（arrival）

- 分析器同时维护两个角的到达时间：
  - **late（最晚）**：用于建立检查，所有单元延时与 `clk_to_q` 乘以 late 降额系数，沿最长路径（max）传播；
  - **early（最早）**：用于保持检查，乘以 early 降额系数，沿最短路径（min）传播。
- 带 `set_input_delay d` 的主输入：两个角的 arrival 均为 d（IO 延时不参与降额）；其余主输入：0，且没有发射时钟。
- 时序单元 `Q` 脚：arrival = 库参数 `clk_to_q` × 该实例对应角的降额系数，发射时钟 = 该单元 `clk` 脚传播到的时钟。
- 同一到达时间并列时按前驱节点名取字典序最小，保证路径唯一可复现。

## 3. 建立 / 保持检查（同一时钟域）

对寄存器 `D` 脚（setup 库值 `su`，hold 库值 `h`，发射/捕获时钟周期 `T`）：

```
setup required = N_setup * T - su - uncertainty_setup
hold  required = N_hold  * T + h  + uncertainty_hold
setup slack = setup required - arrival(late)
hold  slack = arrival(early) - hold required
```

对主输出端口（`set_output_delay = d`，时钟周期 `T`）：

```
setup required = N_setup * T - d - uncertainty_setup
hold  required = N_hold  * T + d + uncertainty_hold
```

默认 `N_setup = 1`、`N_hold = 0`，建立与保持使用同一套多周期参数（见第 5 节）。
判定阈值：slack < -1e-9 ns 记为违例。端点结论分四类：

- 只有建立违例：`SETUP_VIOLATED`；只有保持违例：`HOLD_VIOLATED`；
- **建立、保持最差裕量同时为负：`SETUP_AND_HOLD_VIOLATED`**（不再 setup 优先而吞掉保持违例）；
- 两者均非负：`MET`。

同时违例端点在汇总中**两侧各计一次**：既计入 `setupViolations` 也计入
`holdViolations`，并进入 `holdViolationEndpoints` 清单；仅建立违例的端点不进入保持清单。
`holdViolations`、`holdViolationEndpoints` 长度、最差保持裕量与其端点四者始终对得上。

> 保持检查口径：hold required = N_hold × T **+ h**（保持时间为正要求，到达必须不早于它）。
> 早期版本曾误写为 −h，导致寄存器路径不可能报出保持违例；已修正，B1 用例的旧期望值同步按正确口径更新。

## 4. 跨时钟域（CDC）

同一路径对的发射时钟与捕获时钟不同即为跨时钟域：

- 默认归类为 `CDC`：**不计算 slack、不计入通过/违例统计**，只在端点备注中给出路径对数；
- 用 `set_multicycle_path N -from <时钟A> -to <端点>` 显式声明后才进行检查：
  - setup required = N × 捕获时钟周期 − su（或输出延时）− uncertainty_setup
  - hold required = −(N−1) × **发射时钟周期** + h（输出端口为 +d）+ uncertainty_hold

> 多周期保持检查口径（SDC/PrimeTime 通行做法）：建立检查移动的是捕获时钟边沿，
> 所以 `N_setup` 乘**捕获**时钟周期；保持检查移动的是**发射**时钟边沿（默认回退
> N−1 个发射周期，符号为负），所以无论路径是否跨时钟域，`N_hold` 一律乘**发射**
> 时钟周期。早期版本在跨时钟域时误用捕获周期：捕获周期远大于保持时间（常见量级）时，
> hold required 被压到极负，同一组数值在同时钟多周期路径上能报出保持违例，换成跨时钟域
> 却怎么调都是正裕量。现按通行口径修正；保持方向的时钟不确定度仍按第 7 节照常加入
> hold required。B8-5 用例的旧期望值（−0.73/1.11，按捕获周期 0.8ns 算出）已按正确口径
> 更新为 −0.93/1.31（发射周期 1.0ns）。

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

## 6. 组合环

在“数据图”（去掉时钟路径弧）上用迭代式 Tarjan 算法求强连通分量；节点数 >1 的 SCC 或自环即为组合环。
检测到任何环时：

- 分析状态置为 `COMBINATIONAL_CYCLE`，报告给出按名字排序、可直接定位到单元引脚的环节点序列；
- 不输出任何看似正常的 slack/端点结论；分析器不死循环、不依赖递归栈深度。

## 7. 时钟不确定度（set_clock_uncertainty）

模拟真实时钟的抖动与偏差，分 SETUP / HOLD 两个方向（不写 `-setup/-hold` 则两个方向都生效）：

```
set_clock_uncertainty 0.05 -setup                       // 全局默认
set_clock_uncertainty 0.10 -setup -clock CLK            // 指定时钟
set_clock_uncertainty 0.20 -setup -from U1/Q -to U3/D   // 指定起点→终点路径
```

- 生效方式（悲观方向）：建立检查从 required **减去**不确定度；保持检查向 required **加上**不确定度；
  对寄存器端点、输出端口路径、以及多周期例外放开的跨时钟域路径一律生效。
- 优先级：**路径级 > 时钟级 > 全局**，只取最高优先级的一层，不跨层叠加。
  - 路径级：`-from/-to` 引用匹配规则与例外完全一致（时钟/端口=1，单元引脚=2，两侧都命中才适用）；
  - 时钟级：发射时钟或捕获时钟任一被 `-clock` 命中即适用。
- 同粒度冲突的确定性处理：同一层内多条规则命中同一路径对时**取最大值**（最悲观），
  结果与声明顺序无关；完全相同的重复声明在解析期以 `UNCERTAINTY_DUPLICATE` 失败；
  负数/NaN/无穷以 `UNCERTAINTY_INVALID` 失败；引用不存在以 `UNCERTAINTY_REF_NOT_FOUND` / `CLOCK_NOT_FOUND` 失败。
  任何非法声明都不会被静默按默认值顶替。

## 8. 时序降额（set_timing_derate）

模拟同一单元在不同批次/条件下的快慢差异，系数直接乘到单元延时弧与 `clk_to_q` 上：

```
set_timing_derate 1.10 -late                      // 全局
set_timing_derate 0.90 -early -cell_type BUF      // 按单元类型
set_timing_derate 0.80 -early -instance U2        // 按实例
set_timing_derate 0.95                            // 不写 -early/-late：两个方向都生效
```

- 建立检查用 **late** 系数（数据更晚到），保持检查用 **early** 系数（数据更早到）；
  寄存器 `clk_to_q` 同样按对应角的系数缩放。
- 覆盖优先级：**实例 > 单元类型 > 全局 > 缺省 1.0**，按单元独立解析，不叠加。
- 完全相同的重复声明以 `DERATE_DUPLICATE` 失败；系数非正数/NaN 以 `DERATE_INVALID` 失败；
  单元类型/实例不存在以 `DERATE_REF_NOT_FOUND` 失败。
- 报告中最差路径对的 `derateLate/derateEarly` 字段逐个单元列出最终生效的系数与来源层级。

## 9. 报告与签核视图

- 每个端点给出最差 setup 与最差 hold 路径对的完整数据：到达、要求、裕量、
  生效的不确定度、逐单元降额系数、命中的例外来源；命令行文本报告与 HTTP JSON 均包含。
- 端点结论字段 `status` 取值：`SETUP_VIOLATED` / `HOLD_VIOLATED` /
  `SETUP_AND_HOLD_VIOLATED` / `MET` / `UNCONSTRAINED` / `CDC`。
  同一端点建立、保持最差裕量同时为负时给 `SETUP_AND_HOLD_VIOLATED`，
  文本报告“结论:”列与 HTTP JSON 的 `endpoints[].status` 用同一个枚举字符串。
- 汇总区单独列出全部保持违例端点（`holdViolationEndpoints`），同时违例端点也在其中；
  该清单长度 = `holdViolations`，且与逐端点 `status` 集合、`worstHoldSlack` /
  `worstHoldEndpoint` 互相一致。
- 两个入口查看同一份结论：
  - 命令行：`--sta.run ...` 直接打印文本报告，汇总区“保持违例端点:”一行即清单；
  - HTTP：`POST /sta/analyze` 返回的 `text` 字段与命令行文本逐字节同源，
    结构化数据在 `summary` 与 `endpoints` 中，两处结论必然一致。

## 10. 可复现性

- 所有端点、路径对、环按名称排序输出；并列延时按字典序打破平局；
- 约束的时钟引用在“全部解析完成后”统一校验，分析结论与声明顺序无关；
- 分析过程不读取可变全局状态，同一份输入重复分析得到逐字节一致的文本报告。
