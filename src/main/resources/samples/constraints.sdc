// 示例约束（时间单位 ns）
create_clock CLK  -period 1.0
create_clock CLK2 -period 0.6 -waveform {0.0 0.3}

set_input_delay  0.20 -clock CLK [get_ports IN1]
set_output_delay 0.10 -clock CLK [get_ports OUT1]

// U1/Q -> U6/D 是跨时钟域路径，显式声明为 2 周期例外
set_multicycle_path 2 -from CLK -to U6/D
