import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_bytes()
output = pathlib.Path(sys.argv[2])
values = ", ".join(f"0x{byte:02x}" for byte in source)
output.write_text(
    "#pragma once\n"
    f"static const unsigned char camera_motion_comp_spv[] = {{{values}}};\n"
    f"static const unsigned int camera_motion_comp_spv_len = {len(source)};\n",
    encoding="ascii",
)
