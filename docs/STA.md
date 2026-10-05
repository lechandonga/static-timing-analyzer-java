# 本地静态时序分析（STA）说明

本工程在不引入任何新三方库、不依赖外部工具/服务的前提下，用本地文本文件模拟
网表、单元库和时序约束，完成可复现、可解释的静态时序分析。所有计算在 JVM 内完成，
单位统一为 **ps**（源文件可用 `ns`，解析时一次性换算）。

## 1. 输入文件

三个文件均为行格式、UTF-8、以 `#` 起始注释；除 `time_unit`（可放任意位置，全局生效）
外，语句顺序不影响分析结果。

### 1.1 单元库（`.lib`）

```
time_unit ns
cell INV {
    arc A -> Z comb delay 0.08 0.10      # 组合弧：rise fall
}
cell DFF {
    arc CLK -> Q c2q delay 0.20 0.24     # 时序弧：clock-to-Q
    check D vs CLK setup 0.05            # 建立约束
    check D vs CLK hold 0.02             # 保持约束
}
```

- `arc <from> -> <to> <comb|c2q> delay <rise> <fall>`：延迟必须为有限非负数，
  分析取 rise/fall 的较差值；同一 cell 内同类型、同端点的弧重复定义即报错。
- `check <data> vs <clk> <setup|hold> <value>`：被 check 的数据脚自动识别为时序端点。

### 1.2 网表（`.gate`）

```
input CLK1 A
output Y
instance ff1 DFF
net n1 driver A load ff1/D
net n2 driver ff1/Q load inv1/A inv2/A    # 一个 driver 可带多个 load
```

- 端口用裸名；单元引脚用 `实例/引脚`。
- 每个实例的 cell 必须在库中存在；每条 net 的 driver/load 引脚必须存在；
  输入端口不能被驱动，输出端口不能驱动网络；每个非端口引脚必须有完整连接。
- 一个引脚可以分多条 net 扇出；同一 driver-load 连接在两条 net 中重复则报错。

### 1.3 时序约束（`.sdc`）

```
time_unit ns
create_clock CLK1 period 10.0 uncertainty 0.05 sources CLK1 ff_clk_gen/CLK
set_input_delay  0.5 clock CLK1 A
set_output_delay 0.1 clock CLK1 Y
set_false_path     from ff2/Q to ff3/D
set_multicycle_path 2 setup from ff1/Q through u1/Z to ff2/D
set_multicycle_path 1 hold  from ff1/Q to ff2/D
```

- 时钟：`period` 必须为正的有限数；`uncertainty` 非负且必须小于周期；
  同名时钟、同一 source 多时钟覆盖、同引脚被两个时钟源到达均为失败。
- 端口延迟引用不存在的时钟或未声明的端口即失败。
- 例外引用的 `from/through/to` 必须是真实引脚；多周期 setup≥1、hold≥0。

## 2. 时序计算口径

1. **时钟域解析**：从每个 `create_clock` 的 sources 出发，沿网络传播（跨实例边界和
   出入端口时继续），给时钟引脚打上时钟名；同一引脚被多个时钟到达即报
   `CONSTRAINT_CONFLICT`。
2. **到达时间（arrival）**：起点为带时钟的输入端口（叠加 `set_input_delay`）或
   触发器 c2q 输出；沿组合弧累加延迟，网络延迟为 0。同一节点按
   `(launch clock, 起点)` 保留最差（最大）到达值，同时保留完整跳点用于路径回溯。
3. **端点**：触发器数据引脚（cell 含 setup/hold check）和输出端口。
4. **要求到达时间与 slack**（同一组传播结果同时用于 setup/hold，口径一致）：
   - 寄存器 setup：`T_period - T_uncertainty - T_setup_check`；
     hold：`T_uncertainty + T_hold_check`。
   - 输出端口 setup：`T_period - T_uncertainty - output_delay`；
     hold：`T_uncertainty + output_delay`。
   - `setup_slack = required_setup - arrival`；
     `hold_slack  = min_arrival - required_hold`。
5. **最差路径**：setup 取最大到达路径，hold 取最小到达路径；报告中逐跳打印
   `节点 <- 来源弧/网络 (延迟)`，可定位到具体单元和端点。

## 3. 端点分类与统计

| 分类 | 含义 | 是否计入违例 |
| --- | --- | --- |
| `MET` | setup/hold slack 均 ≥ 0 | 否 |
| `VIOLATED` | 至少一项 slack < 0 | 是（setup/hold 分别计数） |
| `CROSS_CLOCK_DOMAIN` | launch 与 capture 时钟不同 | 否，单独统计 |
| `UNCONSTRAINED` | 无 capture 时钟、无路径到达，或数据由未覆盖时钟的输入发起；输出端口无 `set_output_delay` | 否，单独统计并给出原因 |
| `FALSE_PATH` | 被伪路径例外屏蔽 | 否，单独统计 |

恒等关系：`endpoints = met + violated + cdc + unconstrained + false_path`；
汇总里的 setup/hold 违例数等于逐端点负数 slack 的计数。

## 4. 组合环

在组合子图（c2q 时序弧是顺序边界，不参与）上运行**迭代式** Tarjan SCC，
因此不会栈溢出或死循环；发现任何 SCC（含自环）立即以 `COMBINATIONAL_LOOP`
失败终止，错误体给出按名称排序的环上节点列表，不产出可能被误当正常的时序结论。

## 5. 例外声明与优先级

- `set_false_path`：命中的（起点, 端点, 有序 through 序列）路径不做任何时序检查。
- `set_multicycle_path n setup|hold`：
  - setup 要求 = 单周期要求 × `n_setup`（默认 1）；
  - hold 要求 = 默认 hold 要求 − `n_hold × T_period`（默认 `n_hold = 0`）。
  - setup 与 hold 是相互独立的两条声明；只放松 setup 时 hold 仍按默认（0 周期）检查。

**确定的优先级（与声明顺序无关）：**
1. 显式例外永远优先于隐式单周期默认；
2. `false_path` 优先于任何命中同一路径的 multicycle；
3. 同类规则按特异性取胜：`through` 引脚数权重最高（每个 2），其次 `from+to`，
   再次单侧 `from`/`to`；
4. 特异性相同但效果不同（如同一模式 setup 2 与 setup 3）直接报
   `CONSTRAINT_CONFLICT`，绝不靠声明先后“猜”一个结果；完全重复的声明报
   `DUPLICATE_DEFINITION`。

CDC 路径不会默认按单周期计算：未被例外覆盖时归类 `CROSS_CLOCK_DOMAIN`；
若显式声明 `false_path` 则归类 `FALSE_PATH`（例外优先于 CDC 分类）。

## 6. 失败原因（可区分的错误码）

`PARSE_ERROR`、`FORMAT_ERROR`、`DUPLICATE_DEFINITION`、`UNKNOWN_REFERENCE`、
`ILLEGAL_VALUE`、`ILLEGAL_UNIT`、`COMBINATIONAL_LOOP`、`CONSTRAINT_CONFLICT`。
每条错误都带文件名、行号和原始行文本；任何非法输入都不会被静默忽略或用默认值顶替。

## 7. 本地运行与验证

```bash
# 全部测试（含 Spring 上下文）
mvn test

# 只跑 STA 业务测试，日志按业务分组打印每个用例的 PASS 行
mvn -Dtest=StaticTimingAnalyzerTests test

# 打包后命令行运行（退出码：0 完成（含违例），2 输入失败，3 组合环）
mvn -DskipTests package
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar \
     --sta=samples/standard.lib,samples/multiclock.gate,samples/exceptions.sdc

# 组合环样例
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar \
     --sta=samples/standard.lib,samples/loop.gate,samples/loop.sdc

# HTTP 入口（正常启动 Web 服务，端口随机可用）
# POST /api/sta
curl -s -X POST http://localhost:<port>/api/sta -H 'Content-Type: application/json' -d '{
  "library":"samples/standard.lib",
  "netlist":"samples/multiclock.gate",
  "constraints":"samples/exceptions.sdc"
}'
```

`samples/` 提供三套可直接运行的样例：多时钟/跨域（`multiclock.*`）、
例外生效（`exceptions.sdc`）、组合环（`loop.*`）。

## 8. 可复现性保证

- 所有集合按插入序/名称序遍历，拓扑排序同层按名称排序；
- 报告数值统一保留 6 位小数（HALF_UP）；
- `time_unit` 预扫描，放置位置不影响换算；冲突不靠行序消解；
- 同一份输入重复分析产生字节级一致的文本报告（由 `repeatability` 用例守护）。
