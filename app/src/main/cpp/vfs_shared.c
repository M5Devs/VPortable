#include <stdio.h>

__attribute__((visibility("default")))
const char* vfs_get_dynamic_marker(void) {
    return "VPORTABLE_GATE_4_DYNAMIC_SUCCESS";
}
