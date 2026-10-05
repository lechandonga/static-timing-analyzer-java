# 时序计算与例外处理规则

## 1. 模型与节点

- 时序图节点：单元引脚（`实例/引脚`）与主输入/主输出端口。
- 起点（startpoint）：主输入端口、时序单元 `Q` 脚。
- 终点（endpoint）：主输出端口、时序单元 `D` 脚。
- 组合单元按库延时弧产生有向边；线网延时为 0。
- 时钟从 `create_clock` 的主输入沿组合单元传播（理想时钟，0 延时），到达时序单元 `clk` 脚即确定该寄存器的时钟域；
  时钟路径上的组合弧不参与数据路径。不同时钟在同一节点汇合报 `CLOCK_CONFLICT`。

## 2. 到达时间（arrival）

- 带 `set_input_delay d` 的主输入：arrival = d；其余主输入：0，且没有发射时钟。
- 时序单元 `Q` 脚：arrival = 库参数 `clk_to_q`，发射时钟 = 该单元 `clk` 脚传播到的时钟。
- 沿最长路径（max）向后传播；同一到达时间并列时按前驱节点名取字典序最小，保证路径唯一可复现。

## 3. 建立 / 保持检查（同一时钟域）

对寄存器 `D` 脚（setup 库值 `su`，hold 库值 `h`，发射/捕获时钟周期 `T`）：

```
setup required = N_setup * T - su
hold  required = N_hold  * T - h
setup slack = setup required - arrival
hold  slack = arrival - hold required
```

对主输出端口（`set_output_delay = d`，时钟周期 `T`）：

```
setup required = N_setup * T - d
hold  required = N_hold  * T + d
```

默认 `N_setup = 1`、`N_hold = 0`，建立与保持使用同一套多周期参数（见第 5 节）。
判定阈值：slack < -1e-9 ns 记为违例（`SETUP_VIOLATED` / `HOLD_VIOLATED`，setup 优先）。

## 4. 跨时钟域（CDC）

同一路径对的发射时钟与捕获时钟不同即为跨时钟域：

- 默认归类为 `CDC`：**不计算 slack、不计入通过/违例统计**，只在端点备注中给出路径对数；
- 用 `set_multicycle_path N -from <时钟A> -to <端点>` 显式声明后才进行检查：
  - setup required = N × 捕获时钟周期 − su（或输出延时）
  - hold required = −(N−1) × 捕获时钟周期 − h（输出端口为 −(N−1)×T + d）

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

## 7. 可复现性

- 所有端点、路径对、环按名称排序输出；并列延时按字典序打破平局；
- 约束的时钟引用在“全部解析完成后”统一校验，分析结论与声明顺序无关；
- 分析过程不读取可变全局状态，同一份输入重复分析得到逐字节一致的文本报告。
