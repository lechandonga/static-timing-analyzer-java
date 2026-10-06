create_clock CLK -period 2.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_timing_derate 0.90 -instance GHOST
