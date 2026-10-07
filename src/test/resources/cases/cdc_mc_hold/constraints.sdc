// 捕获周期（8ns）远大于保持时间，用于复现旧口径下保持永不违例的问题。
create_clock CLK1 -period 1.0
create_clock CLK2 -period 8.0
set_input_delay 0.02 -clock CLK1 [get_ports IN1]
set_input_delay 0.02 -clock CLK1 [get_ports IN2]
set_multicycle_path 2 -from CLK1 -to U2/D
set_multicycle_path 2 -from CLK1 -to U5/D
// 保持方向不确定度按发射时钟口径照常计入
set_clock_uncertainty 0.05 -hold -from CLK1 -to U2/D
set_clock_uncertainty 0.05 -hold -from CLK1 -to U5/D
