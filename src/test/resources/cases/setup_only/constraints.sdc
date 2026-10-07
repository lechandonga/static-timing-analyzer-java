create_clock CLK -period 1.0
set_input_delay 0.02 -clock CLK [get_ports IN1]
set_input_delay 0.02 -clock CLK [get_ports IN2]
