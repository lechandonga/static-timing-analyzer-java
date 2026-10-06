# 输入文件格式

分析器读取三份本地纯文本文件，所有数值在内部统一换算为 **纳秒(ns)**。
行注释以 `//` 开头；除注释外不允许出现无法识别的关键字。

## 1. 单元库（`.lib`）

```
unit ns                       // 必填，仅支持 ns 或 ps；必须出现在第一个 cell 之前；重复声明报错

cell BUF2 comb                // cell <名字> comb|seq；同名重复定义报错
  pin A comb                  // 组合单元引脚角色固定为 comb
  pin Y comb
  delay A Y 0.10              // delay <输入脚> <输出脚> <延时>；弧重复定义报错
endcell

cell DFFX seq
  pin D  seq_in               // 时序单元必须恰好各一个 seq_in / clk / seq_out
  pin CK clk
  pin Q  seq_out
  timing setup=0.05 hold=0.02 clk_to_q=0.08   // 三项必须同时给出，非负有限数
endcell
```

`unit ps` 时所有延时/约束数值按 ÷1000 换算为 ns。

## 2. 网表（`.net`）

```
input  CLK                    // 主输入线网；重复声明报错
output OUT1                   // 主输出线网

inst U1 DFFX                  // inst <实例名> <库单元类型>；类型必须存在
  pin D  N1                   // pin <库引脚名> <线网名>；引脚必须存在、不得重复连接
  pin CK CLK
  pin Q  N2
endinst
```

结构约束（违反即失败，不做默认值顶替）：

- 单元的每个库引脚都必须连接到线网；
- 每条线网至多一个驱动：组合单元延时弧的输出脚、时序单元的 `seq_out` 脚为驱动；
- 非 `input` 线网必须有驱动；`input` 线网不允许再有内部驱动；
- 时序单元的 `clk` 脚只能由时钟约束传播到的时钟网络驱动。

## 3. 时序约束（`.sdc`）

时间单位固定为 ns。

```
create_clock CLK  -period 1.0 [-waveform {0.0 0.5}]
set_input_delay  0.20 -clock CLK [get_ports IN1]      // [get_ports X] 与 [get_ports X ] 两种写法都支持
set_output_delay 0.10 -clock CLK [get_ports OUT1]
set_false_path      -from CLK  -to U3/D
set_multicycle_path 2 -from CLK  -to U6/D
set_clock_uncertainty 0.05                              // 全局：所有时钟、所有受约束路径
set_clock_uncertainty 0.05 [get_clocks CLK]             // 单时钟（[get_clocks CLK] 两种括号写法都支持）
set_clock_uncertainty 0.02 -from CLK1 -to U6/D          // 路径级：from/to 引用规则同例外
set_timing_derate 0.95                                  // 全局降额：早/晚角同系数
set_timing_derate -early 0.90 -late 1.10                // 全局，早/晚角分别给
set_timing_derate -late 1.08 -cell BUF                  // 按单元类型
set_timing_derate -early 0.95 -late 1.05 -instance U2   // 按实例
```

- `create_clock` 的时钟名必须等于一个主输入线网名；同名重复定义报错；
- `-waveform` 仅校验 `0 <= rise < fall < period`，本分析器使用理想单周期时钟，边沿相位不参与计算；
- `-from/-to` 可以引用：时钟名、主输入/主输出端口名、单元引脚（`实例/引脚`）；引用不存在即报错；
- 同一对 `(from,to)` 的例外声明重复出现报错（`DUPLICATE_DEFINITION`）。

### 时钟不确定度 `set_clock_uncertainty`

- 值单位为 ns，必须是非负有限数；三种粒度：
  - 裸值：全局默认，**只允许声明一次**，重复为 `DUPLICATE_DEFINITION`；
  - `[get_clocks <时钟>]`：单时钟，时钟必须已由 `create_clock` 定义，否则 `UNCERTAINTY_CLOCK_NOT_FOUND`；
  - `-from/-to`：路径级，引用解析规则与伪路径/多周期完全相同，不存在为 `EXCEPTION_REF_NOT_FOUND`；
    同一 `(from,to)` 重复声明为 `DUPLICATE_DEFINITION`。
- 生效优先级与例外一致：**路径级 > 时钟级 > 全局**；仲裁只取决于匹配粒度，与声明顺序无关。
- 时钟级取值对一条路径的发射时钟与捕获时钟**同时相关**：两侧都有时取悲观最大值
  （顺序无关的确定性规则）；只有一侧有时钟级声明时用该侧；都没有时回退全局。
- 建立检查把生效不确定度从 required 中**扣减**，保持检查把它**加回** required，两侧都真正受影响；
  走输出端口的路径、以及靠 `set_multicycle_path` 放开的跨时钟域路径同样适用。

### 时序降额 `set_timing_derate`

- 系数必须是大于 0 的有限数；一个裸系数表示早/晚角相同；也可用 `-early <f> [-late <f>]`
  分别给出（只给一侧时另一侧取同值）；裸系数不得与 `-early/-late` 混用。
- 三种粒度（同目标只允许一条声明，早晚角必须写在同一条里，重复为 `DUPLICATE_DEFINITION`）：
  - 全局（不带目标）；
  - `-cell <单元类型>`：类型必须存在于单元库，否则 `DERATE_CELL_NOT_FOUND`；
  - `-instance <实例名>`：实例必须存在于网表，否则 `DERATE_INSTANCE_NOT_FOUND`。
- 生效优先级：**实例 > 单元类型 > 全局**；建立检查用晚角系数、保持检查用早角系数。
- 降额作用于片内单元延时（组合弧与寄存器 `clk_to_q`），按“原始延时 × 系数”计入到达时间；
  `set_input_delay` / `set_output_delay` 代表片外数据，不降额。
- 不写任何降额声明时，所有单元系数等价为 1.0；三份老输入文件无需任何修改即可继续分析。

## 错误码（可程序化区分失败原因）

| 错误码 | 触发场景 |
| --- | --- |
| `FILE_NOT_FOUND` / `FILE_UNREADABLE` | 文件不存在或不可读 |
| `SYNTAX_ERROR` | 关键字、字段数量、括号结构非法 |
| `UNIT_MISSING` / `UNIT_UNSUPPORTED` | 缺少 unit，或单位不是 ns/ps |
| `VALUE_INVALID` | 数值非法、负数、无穷/NaN、波形边沿关系错误 |
| `DUPLICATE_DEFINITION` | unit/单元/引脚/延时弧/实例/端口/时钟/IO 延时/例外重复 |
| `CELL_NOT_FOUND` / `PIN_NOT_FOUND` | 网表引用了库中不存在的单元或引脚 |
| `PIN_UNCONNECTED` | 单元库引脚未连接线网 |
| `NET_UNDRIVEN` / `NET_MULTI_DRIVER` / `NET_DRIVER_CONFLICT` | 线网无驱动、多驱动、主输入被内部驱动 |
| `CELL_DEF_INVALID` / `LIBRARY_EMPTY` / `NETLIST_EMPTY` | 库或网表定义不完整 |
| `CLOCK_SOURCE_NOT_FOUND` / `CLOCK_NOT_FOUND` | 时钟源不是主输入，或约束引用未定义时钟 |
| `PORT_NOT_FOUND` | IO 延时引用了不存在的端口 |
| `EXCEPTION_REF_NOT_FOUND` | 例外的 from/to 无法解析 |
| `EXCEPTION_CONFLICT` | 同粒度例外互相冲突（见 timing-rules.md） |
| `UNCERTAINTY_CLOCK_NOT_FOUND` / `UNCERTAINTY_CONFLICT` | 不确定度引用未定义时钟；同粒度路径声明取值冲突 |
| `DERATE_CELL_NOT_FOUND` / `DERATE_INSTANCE_NOT_FOUND` | 降额引用了不存在的单元类型或实例 |
| `CLOCK_CONFLICT` | 不同时钟在同一时钟网络节点汇合 |
