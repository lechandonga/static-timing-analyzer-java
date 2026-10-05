create_clock CLK1 -period 1.0
create_clock CLK2 -period 0.8
set_input_delay 0.10 -clock CLK1 [get_ports IN1]
