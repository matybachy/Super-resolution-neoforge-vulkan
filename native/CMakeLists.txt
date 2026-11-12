cmake_minimum_required(VERSION 3.20)
project(superresolution_dlss LANGUAGES CXX)

set(DLSS_SDK_DIR "C:/dev/DLSS" CACHE PATH "External NVIDIA DLSS SDK checkout")
find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)

if(WIN32)
    set(DLSS_LIB_DIR "${DLSS_SDK_DIR}/lib/Windows_x86_64/x64")
    set(DLSS_LIB "nvsdk_ngx_s.lib")
else()
    set(DLSS_LIB_DIR "${DLSS_SDK_DIR}/lib/Linux_x86_64")
    set(DLSS_LIB "libnvsdk_ngx.a")
endif()

add_library(superresolution_dlss SHARED src/superresolution_dlss.cpp)
target_compile_features(superresolution_dlss PRIVATE cxx_std_20)
target_include_directories(superresolution_dlss PRIVATE
        "${DLSS_SDK_DIR}/include"
        ${JNI_INCLUDE_DIRS}
        ${Vulkan_INCLUDE_DIRS})
target_link_directories(superresolution_dlss PRIVATE "${DLSS_LIB_DIR}")
target_link_libraries(superresolution_dlss PRIVATE ${DLSS_LIB} Vulkan::Vulkan)

if(WIN32)
    set_target_properties(superresolution_dlss PROPERTIES OUTPUT_NAME "superresolution_dlss")
endif()
