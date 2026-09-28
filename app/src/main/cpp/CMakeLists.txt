cmake_minimum_required(VERSION 3.22)
project(helix_model_runtime C CXX)
include(FetchContent)
# Four ABI/build-type configurations may be built by Gradle together. Bound native compiler memory.
set_property(GLOBAL PROPERTY JOB_POOLS helix_native_compile=2)
set(CMAKE_JOB_POOL_COMPILE helix_native_compile)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Quantized CPU kernels are not usable for on-device evaluation at -O0. Keep debug
# symbols and assertions, but optimize native computation in debug APKs as well.
add_compile_options("$<$<CONFIG:Debug>:-O2>")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/7fe450e19305b828c199d602c23a8337aaa1f03b.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    URL_HASH SHA256=a6861d549427f814dc591c439e08206f67ffaba0248344d421589abf18199e67
)
FetchContent_MakeAvailable(llama)
add_library(helix_model SHARED helix_model.cpp)
target_compile_features(helix_model PRIVATE cxx_std_17)
target_link_libraries(helix_model PRIVATE llama-common llama log)
target_link_options(helix_model PRIVATE "-Wl,-z,max-page-size=16384")
