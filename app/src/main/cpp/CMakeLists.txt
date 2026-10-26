cmake_minimum_required(VERSION 3.22.1)

project("vportable_native")

add_executable(libprobe_ptrace.so probe_ptrace.c)
set_target_properties(libprobe_ptrace.so PROPERTIES OUTPUT_NAME "libprobe_ptrace.so")

add_library(vfs_shared SHARED vfs_shared.c)

add_executable(vfs_dynamic_bin vfs_dynamic_bin.c)
target_link_libraries(vfs_dynamic_bin vfs_shared)
set_target_properties(vfs_dynamic_bin PROPERTIES
    OUTPUT_NAME "libvfs_dynamic_bin.so"
    LINK_FLAGS "-Wl,-rpath,/lib -Wl,-rpath,/lib64"
)
