cmake_minimum_required(VERSION 3.22.1)
# Always optimise native code (even in debug APKs): un-optimised ggml is 10x+ slower.
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)
project(privateai_native C CXX)
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b6000
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(privateai_native SHARED llama_jni.cpp)
target_link_libraries(privateai_native llama log android)
# Keep llama.cpp's ggml private: the whisper module ships its own ggml.
target_link_options(privateai_native PRIVATE -Wl,--exclude-libs,ALL)
