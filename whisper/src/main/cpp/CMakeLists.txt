cmake_minimum_required(VERSION 3.22.1)
# Always optimise native code (even in debug APKs): un-optimised ggml is 10x+ slower.
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)
project(whisper_jni C CXX)
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_Declare(whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni whisper log android)
# Keep this copy of ggml private: llama.cpp (another module) ships its own ggml.
target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)
