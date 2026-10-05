time_unit ns
create_clock CLK1 period 10.0 uncertainty 0.05 sources CLK1
set_input_delay 0.5 clock CLK1 A
set_output_delay 0.1 clock CLK1 Y
