cmake_minimum_required(VERSION 3.22.1)

project(kgking_native)

add_library(
        kgking_native
        SHARED
        native-lib.cpp)

find_library(log-lib log)

target_link_libraries(
        kgking_native
        ${log-lib})
