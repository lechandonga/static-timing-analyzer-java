create_clock CLK -period 2.0
set_input_delay 0.10 -clock CLK [get_ports IN1]
set_timing_derate -early 0.50 -late 1.20
set_timing_derate -early 0.80 -late 1.10 -cell BUF
set_timing_derate -early 0.90 -late 1.30 -instance U2
