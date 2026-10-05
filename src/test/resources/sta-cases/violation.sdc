time_unit ns
create_clock CLK1 period 0.2 uncertainty 0.01 sources CLK1
set_input_delay 0.05 clock CLK1 A
set_output_delay 0.15 clock CLK1 Y
set_output_delay 0.15 clock CLK2 CDC
create_clock CLK2 period 7.0 uncertainty 0.04 sources CLK2
