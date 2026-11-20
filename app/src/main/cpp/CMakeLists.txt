cmake_minimum_required(VERSION 3.18)
project(solderlessjni C)

# The emulator is shared with the host build (emu/).
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../emu ${CMAKE_CURRENT_BINARY_DIR}/emu)

add_library(solderlessjni SHARED jni.c)
target_link_libraries(solderlessjni PRIVATE slemu log)
target_compile_options(solderlessjni PRIVATE -Wall -Wextra)
