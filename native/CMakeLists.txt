cmake_minimum_required(VERSION 3.20)
project(srvmr_ngx CXX)

# The NVIDIA DLSS SDK (https://github.com/NVIDIA/DLSS) and the Khronos Vulkan headers are not part of this repository.
# Point DLSS_SDK_DIR and VULKAN_HEADERS_DIR at checkouts of them; see native/README.md.
set(DLSS_SDK_DIR "" CACHE PATH "Root of the NVIDIA DLSS SDK")
set(VULKAN_HEADERS_DIR "" CACHE PATH "Root of the Khronos Vulkan-Headers repository")
if(NOT EXISTS "${DLSS_SDK_DIR}/include/nvsdk_ngx_vk.h")
    message(FATAL_ERROR "DLSS_SDK_DIR does not point at the DLSS SDK: ${DLSS_SDK_DIR}")
endif()
if(NOT EXISTS "${VULKAN_HEADERS_DIR}/include/vulkan/vulkan.h")
    message(FATAL_ERROR "VULKAN_HEADERS_DIR does not point at Vulkan-Headers: ${VULKAN_HEADERS_DIR}")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# The static runtime, to match nvsdk_ngx_s.lib and to need no Visual C++ runtime on the player's computer.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

find_package(JNI REQUIRED)

add_library(srvmr_ngx SHARED src/srvmr_ngx.cpp)
target_include_directories(srvmr_ngx PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${VULKAN_HEADERS_DIR}/include"
    "${DLSS_SDK_DIR}/include")
target_compile_definitions(srvmr_ngx PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
target_link_libraries(srvmr_ngx PRIVATE "${DLSS_SDK_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib")
if(SRVMR_EXTRA_LIBS)
    target_link_libraries(srvmr_ngx PRIVATE ${SRVMR_EXTRA_LIBS})
endif()
