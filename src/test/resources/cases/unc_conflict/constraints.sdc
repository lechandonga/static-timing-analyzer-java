create_clock CLK -period 1.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_clock_uncertainty 0.05 -from CLK -to U3/D
set_clock_uncertainty 0.08 -from CLK -to U3/D
