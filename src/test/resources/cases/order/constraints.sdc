set_output_delay 0.10 -clock CLK [get_ports OUT1]
set_input_delay 0.20 -clock CLK [get_ports IN1]
create_clock CLK -period 1.0
