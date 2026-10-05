create_clock CLK -period 1.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_false_path -from CLK -to U3/D
set_multicycle_path 2 -from CLK -to U3/D
