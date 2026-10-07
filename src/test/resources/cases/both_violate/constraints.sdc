create_clock CLK -period 1.0
set_input_delay 0.60 -clock CLK [get_ports IN1]
// 输出延时 0.95：setup req = 0.05 < arrival 0.08（建立违例 -0.03）；
// hold req = 0.95 > arrival 0.08（保持违例 -0.87）——同一端点建立、保持同时为负
set_output_delay 0.95 -clock CLK [get_ports OUT1]
