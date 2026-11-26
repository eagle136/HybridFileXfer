cmake_minimum_required(VERSION 3.22.1)

project("hybridfilexfer" LANGUAGES CXX)

set(ANDROID_STL c++_static)

add_library(native-memory SHARED native-memory.cpp)

target_compile_options(native-memory PRIVATE -O3)

set_target_properties(native-memory PROPERTIES
        LINK_FLAGS "-Wl,-z,max-page-size=16384")

find_library(log-lib log)

target_link_libraries(native-memory
        c++_static
        ${log-lib})
