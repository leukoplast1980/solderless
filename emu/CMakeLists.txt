cmake_minimum_required(VERSION 3.18)
project(solderless_emu C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(SL_SOURCES
  core/armv7m.c
  core/fparith.c
  soc/soc.c
  soc/rcc.c
  soc/flash.c
  soc/gpio.c
  soc/usart.c
  soc/exti.c
  soc/tim.c
  soc/adc.c
  soc/dac.c
  soc/i2c.c
  soc/spi.c
  board/board.c
  board/bus.c
  parts/ssd1306.c
  parts/w25q.c
  parts/max7219.c
  parts/st7789.c
  parts/bmp280.c
  parts/scd41.c
  soc/vm.c
  gen/g431_data.c
)

add_library(slemu STATIC ${SL_SOURCES})
target_include_directories(slemu PUBLIC include PRIVATE core soc gen)
target_compile_options(slemu PRIVATE -Wall -Wextra -Wno-unused-parameter)
set_property(TARGET slemu PROPERTY POSITION_INDEPENDENT_CODE ON)
find_package(Threads REQUIRED)
target_link_libraries(slemu PUBLIC Threads::Threads m)

# Host-only tools and tests (not built for Android)
if(NOT ANDROID)
  add_executable(slemu-cli cli/slemu.c)
  set_target_properties(slemu-cli PROPERTIES OUTPUT_NAME slemu)
  target_link_libraries(slemu-cli PRIVATE slemu)

  add_library(cmtest SHARED ../tests/cpu/harness.c core/armv7m.c core/fparith.c)
  target_include_directories(cmtest PRIVATE core)
  target_link_libraries(cmtest PRIVATE m)

  add_executable(exc_test ../tests/cpu/exc_test.c core/armv7m.c core/fparith.c)
  target_include_directories(exc_test PRIVATE core)
  target_link_libraries(exc_test PRIVATE m)

  # The exception test firmware needs the ARM cross assembler
  find_program(ARM_AS arm-none-eabi-as)
  find_program(ARM_LD arm-none-eabi-ld)
  find_program(ARM_OBJCOPY arm-none-eabi-objcopy)
  if(ARM_AS AND ARM_LD AND ARM_OBJCOPY)
    set(EXC_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../tests/cpu/exc_test.S)
    add_custom_command(
      OUTPUT exc_test.bin
      COMMAND ${ARM_AS} -mcpu=cortex-m4 -mthumb -o exc_test.o ${EXC_SRC}
      COMMAND ${ARM_LD} -Ttext=0 -o exc_test.elf exc_test.o
      COMMAND ${ARM_OBJCOPY} -O binary exc_test.elf exc_test.bin
      DEPENDS ${EXC_SRC})
    add_custom_target(exc_test_fw ALL DEPENDS exc_test.bin)
  endif()

  # Peripheral and board tests: C firmware for the chip, and a host harness
  add_executable(periph_test ../tests/periph/periph_test.c)
  target_link_libraries(periph_test PRIVATE slemu)
  find_program(ARM_GCC arm-none-eabi-gcc)
  if(ARM_GCC AND ARM_OBJCOPY)
    set(PERIPH_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../tests/periph/periph_fw.c)
    set(PERIPH_LD ${CMAKE_CURRENT_SOURCE_DIR}/../tests/periph/periph.ld)
    add_custom_command(
      OUTPUT periph_fw.bin
      COMMAND ${ARM_GCC} -mcpu=cortex-m4 -mthumb -mfloat-abi=soft -O2 -ffreestanding -nostdlib
              -Wall -Wextra -Wno-override-init -T ${PERIPH_LD} -o periph_fw.elf ${PERIPH_SRC}
      COMMAND ${ARM_OBJCOPY} -O binary periph_fw.elf periph_fw.bin
      DEPENDS ${PERIPH_SRC} ${PERIPH_LD})
    add_custom_target(periph_fw ALL DEPENDS periph_fw.bin)
  endif()
endif()
