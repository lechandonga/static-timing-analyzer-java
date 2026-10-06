// 示例约束（时间单位 ns）
create_clock CLK  -period 1.0
create_clock CLK2 -period 0.6 -waveform {0.0 0.3}

set_input_delay  0.20 -clock CLK [get_ports IN1]
set_output_delay 0.10 -clock CLK [get_ports OUT1]

// U1/Q -> U6/D 是跨时钟域路径，显式声明为 2 周期例外
set_multicycle_path 2 -from CLK -to U6/D

// 时钟不确定度：全局 0.03，CLK2 单独给 0.05（时钟级优先）
set_clock_uncertainty 0.03
set_clock_uncertainty 0.05 [get_clocks CLK2]

// 时序降额：全局晚角 1.05；BUF2 单元晚角 1.10；实例 U3 单独给早晚角
set_timing_derate -late 1.05
set_timing_derate -late 1.10 -cell BUF2
set_timing_derate -early 0.95 -late 1.08 -instance U3
