cmake_minimum_required(VERSION 3.22.1)
project(aihub_node_runtime)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(aihub_node_runtime SHARED src/main/cpp/node-runtime.cpp)

target_include_directories(aihub_node_runtime PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/node-runtime/include/node)

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

target_link_libraries(aihub_node_runtime
        node
        ${log-lib})
