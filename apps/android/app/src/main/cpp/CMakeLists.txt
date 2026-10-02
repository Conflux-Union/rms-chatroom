# RNNoise neural noise suppression for the microphone track.
#
# Upstream lives in the git submodule rnnoise/ (https://github.com/xiph/rnnoise,
# BSD, see rnnoise/COPYING). Only the six library .c files are built; training,
# reader and example code is left out. No config.h needed: every include is
# guarded by HAVE_CONFIG_H.
cmake_minimum_required(VERSION 3.22.1)
project(rnnoise)

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

add_library(rnnoise SHARED
    rnnoise_jni.c
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/rnn_data.c
)

target_include_directories(rnnoise PRIVATE
    ${RNNOISE_DIR}/src
    ${RNNOISE_DIR}/include
)
target_link_libraries(rnnoise log)
