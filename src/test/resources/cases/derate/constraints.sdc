create_clock CLK -period 1.0
set_input_delay 0.20 -clock CLK [get_ports IN1]
set_output_delay 0.10 -clock CLK [get_ports OUT1]
set_timing_derate 1.10 -late
set_timing_derate 0.90 -early
set_timing_derate 1.20 -late -cell_type BUF
set_timing_derate 0.80 -early -instance U2
