create_clock CLK1 -period 1.0
create_clock CLK2 -period 0.8
set_input_delay 0.60 -clock CLK1 [get_ports IN1]
set_multicycle_path 2 -from CLK1 -to U3/D
set_multicycle_path 2 -from CLK1 -to U6/D
set_clock_uncertainty 0.05 -hold
