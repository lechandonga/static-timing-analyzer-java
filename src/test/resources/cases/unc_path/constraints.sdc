create_clock CLK -period 1.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_clock_uncertainty 0.20
set_clock_uncertainty 0.05 [get_clocks CLK]
set_clock_uncertainty 0.01 -from CLK -to U3/D
