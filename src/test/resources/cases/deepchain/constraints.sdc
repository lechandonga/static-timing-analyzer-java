create_clock CLK -period 1000.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_timing_derate -early 0.9 -late 1.1
set_clock_uncertainty 0.02
