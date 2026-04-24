cmake_minimum_required(VERSION 3.22.1)
project("snapapp-search")

add_library(snapapp-search SHARED search_jni.cpp)

target_compile_options(snapapp-search PRIVATE -O3 -march=armv8-a+simd -ffast-math)

find_library(log-lib log)
target_link_libraries(snapapp-search ${log-lib})
