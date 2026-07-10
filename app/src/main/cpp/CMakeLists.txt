cmake_minimum_required(VERSION 3.22.1)
project("omega_core")

# Ép biên dịch C++20, tối ưu hóa tốc độ và ẩn danh
set(CMAKE_CXX_STANDARD 20)
add_compile_options(-O3 -fvisibility=hidden -ffunction-sections -fdata-sections)

add_library(omega_core SHARED VoidEngine.cpp)

# Link thư viện log và zlib của Android
find_library(log-lib log)
find_library(z-lib z)
target_link_libraries(omega_core ${log-lib} ${z-lib})