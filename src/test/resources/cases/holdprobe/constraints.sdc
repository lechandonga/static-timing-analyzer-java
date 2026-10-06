create_clock CLK -period 1.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_output_delay 0.10 -clock CLK [get_ports OUTS]
set_output_delay 0.10 -clock CLK [get_ports OUTL]
