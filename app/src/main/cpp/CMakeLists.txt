cmake_minimum_required(VERSION 3.22.1)

project("vportable_native")

add_executable(libprobe_ptrace.so probe_ptrace.c)

set_target_properties(libprobe_ptrace.so PROPERTIES OUTPUT_NAME "libprobe_ptrace.so")
