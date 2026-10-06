create_clock CLK -period 1.0
set_input_delay 0.20 -clock CLK [get_ports IN1]
set_output_delay 0.10 -clock CLK [get_ports OUT1]
set_clock_uncertainty 0.05 -setup
set_clock_uncertainty 0.03 -hold
set_clock_uncertainty 0.10 -setup -clock CLK
set_clock_uncertainty 0.15 -setup -from CLK -to U3/D
set_clock_uncertainty 0.20 -setup -from U1/Q -to U3/D
