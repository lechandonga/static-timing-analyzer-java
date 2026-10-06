create_clock CLK -period 1.0
set_input_delay 0.60 -clock CLK [get_ports IN1]
set_output_delay 0.50 -clock CLK [get_ports OUT1]
