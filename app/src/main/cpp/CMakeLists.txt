cmake_minimum_required(VERSION 3.22.1)
project("omega_core")

add_library(omega_core SHARED VoidEngine.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(omega_core ${log-lib} ${android-lib})