cmake_minimum_required(VERSION 3.4.1)

add_library(native-lib SHARED native-lib.cpp)
find_library(log-lib log)

# The 'jnigraphics' part is crucial for Bitmap processing!
target_link_libraries(native-lib ${log-lib} jnigraphics)