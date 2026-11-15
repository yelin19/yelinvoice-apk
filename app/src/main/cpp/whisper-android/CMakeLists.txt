cmake_minimum_required(VERSION 3.22.1)
project(yelinvoice_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(WHISPER_LIB_DIR "${CMAKE_CURRENT_LIST_DIR}/../whisper.cpp")

# Build only the CPU inference library; avoid desktop examples, tests and tools.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)

include(FetchContent)

add_library(yelinvoice_whisper SHARED
    whisper_jni.cpp
    "${WHISPER_LIB_DIR}/src/whisper.cpp"
    "${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp"
)
target_compile_definitions(yelinvoice_whisper PRIVATE GGML_USE_CPU WHISPER_VERSION="1.9.5")
target_include_directories(yelinvoice_whisper PRIVATE
    "${WHISPER_LIB_DIR}"
    "${WHISPER_LIB_DIR}/include"
    "${WHISPER_LIB_DIR}/src"
    "${WHISPER_LIB_DIR}/ggml/include"
    "${WHISPER_LIB_DIR}/ggml/src"
    "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu"
)

if (GGML_HOME)
    FetchContent_Declare(ggml SOURCE_DIR "${GGML_HOME}")
else()
    FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_LIB_DIR}/ggml")
endif()
FetchContent_MakeAvailable(ggml)
target_link_libraries(yelinvoice_whisper PRIVATE ggml android log)
target_compile_options(yelinvoice_whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
