cmake_minimum_required(VERSION 3.18)
project(solderlessjni C)

# The emulator is shared with the host build (emu/).
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../emu ${CMAKE_CURRENT_BINARY_DIR}/emu)

# The emulator is optimised in every build type: a debug APK built at -O0
# runs the chip about three times slower. (Later flags win over the
# build type's -O0.)
target_compile_options(slemu PRIVATE -O2)

add_library(solderlessjni SHARED jni.c)
target_link_libraries(solderlessjni PRIVATE slemu log)
target_compile_options(solderlessjni PRIVATE -Wall -Wextra)
