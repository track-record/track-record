cmake_minimum_required(VERSION 3.22)
project(trackrecord_fingerprint C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Chromaprint 1.6.1 (chromaprint/, unmodified sources; LGPL 2.1 as a whole, see chromaprint/LICENSE.md),
# built as its own shared library so it stays replaceable, with KissFFT (BSD-3-Clause) for the FFT.
set(CP ${CMAKE_CURRENT_SOURCE_DIR}/chromaprint)
add_library(chromaprint SHARED
    ${CP}/src/audio_processor.cpp
    ${CP}/src/chroma.cpp
    ${CP}/src/chroma_resampler.cpp
    ${CP}/src/chroma_filter.cpp
    ${CP}/src/spectrum.cpp
    ${CP}/src/fft.cpp
    ${CP}/src/fingerprinter.cpp
    ${CP}/src/image_builder.cpp
    ${CP}/src/simhash.cpp
    ${CP}/src/silence_remover.cpp
    ${CP}/src/fingerprint_calculator.cpp
    ${CP}/src/fingerprint_compressor.cpp
    ${CP}/src/fingerprint_decompressor.cpp
    ${CP}/src/fingerprinter_configuration.cpp
    ${CP}/src/fingerprint_matcher.cpp
    ${CP}/src/utils/base64.cpp
    ${CP}/src/chromaprint.cpp
    ${CP}/src/fft_lib_kissfft.cpp
    ${CP}/src/avresample/resample2.c
    ${CP}/kissfft/kiss_fft.c
    ${CP}/kissfft/kiss_fftr.c
)
target_include_directories(chromaprint PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CP}/src ${CP}/kissfft)
target_compile_definitions(chromaprint PRIVATE HAVE_CONFIG_H CHROMAPRINT_API_EXPORTS)
target_compile_options(chromaprint PRIVATE -O2 -w)
target_link_libraries(chromaprint m)

add_library(trackrecord_fingerprint SHARED fingerprint_jni.cpp)
target_include_directories(trackrecord_fingerprint PRIVATE ${CP}/src)
target_compile_options(trackrecord_fingerprint PRIVATE -O2)
target_link_libraries(trackrecord_fingerprint chromaprint)
