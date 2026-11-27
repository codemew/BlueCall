cmake_minimum_required(VERSION 3.22.1)
project(bluecall C)

# ---- libopus (vendored 1.5.2, float build, no DNN features) -----------------
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/opus EXCLUDE_FROM_ALL)
set_target_properties(opus PROPERTIES C_VISIBILITY_PRESET hidden POSITION_INDEPENDENT_CODE ON)

# ---- RNNoise (vendored v0.1.1, model compiled in) ---------------------------
add_library(rnnoise STATIC
        third_party/rnnoise/src/denoise.c
        third_party/rnnoise/src/rnn.c
        third_party/rnnoise/src/rnn_data.c
        third_party/rnnoise/src/celt_lpc.c
        third_party/rnnoise/src/kiss_fft.c
        third_party/rnnoise/src/pitch.c)
target_include_directories(rnnoise
        PUBLIC third_party/rnnoise/include
        PRIVATE third_party/rnnoise/src)
target_compile_options(rnnoise PRIVATE -O2 -w)
set_target_properties(rnnoise PROPERTIES C_VISIBILITY_PRESET hidden POSITION_INDEPENDENT_CODE ON)

# ---- JNI bridge -------------------------------------------------------------
add_library(bluecall SHARED bluecall_jni.c)
target_compile_options(bluecall PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(bluecall PRIVATE opus rnnoise m log)
target_link_options(bluecall PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
