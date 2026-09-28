cmake_minimum_required(VERSION 3.22.1)
project(whisalt_whisper C CXX)

# whisper.cpp source is fetched and checksum-verified by `make deps`
set(WHISPER_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp)
if(NOT EXISTS ${WHISPER_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp source missing at ${WHISPER_SRC}; run `make deps`")
endif()

# ggml picks the best CPU variant at runtime (e.g. i8mm on Snapdragon 8 Gen 2) instead of
# hard-coding one that would crash older phones
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_SRC} whisper EXCLUDE_FROM_ALL)

# Which ggml CPU variants get built and packaged is chosen in app/build.gradle.kts (`targets`)
add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper ggml log)
