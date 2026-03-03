cmake_minimum_required(VERSION 3.4.1)

# 1. LOCK DOWN LIBJPEG-TURBO: Only build the core library, no extra bloat/programs
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_EXECUTABLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_JPEG8 ON CACHE BOOL "" FORCE)
set(WITH_SIMD ON CACHE BOOL "" FORCE)

# 2. Add the downloaded folder
add_subdirectory(libjpeg-turbo)

add_library(native-lib SHARED native-lib.cpp)

# NEW: Tell the compiler where to find the auto-generated ghost files (jconfig.h)
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo
)

find_library(log-lib log)

# 3. Link explicitly to "jpeg-static" (the internal name used by libjpeg-turbo)
target_link_libraries(native-lib jpeg-static ${log-lib})