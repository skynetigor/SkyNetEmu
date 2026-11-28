cmake_minimum_required(VERSION 3.22.1)

set(lsfg_dxbc_root ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/lsfg/dxbc)
set(lsfg_spirv_root ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/lsfg/spirv-headers)
file(GLOB lsfg_compiler_sources CONFIGURE_DEPENDS
    "${lsfg_dxbc_root}/src/dxbc/*.cpp"
    "${lsfg_dxbc_root}/src/spirv/*.cpp"
    "${lsfg_dxbc_root}/src/util/*.cpp")

add_library(lsfg_dxbc STATIC
    ${lsfg_compiler_sources}
    ${CMAKE_CURRENT_SOURCE_DIR}/../winlator/renderer/vulkan/lsfg/lsfg_dxbc.cpp)
target_compile_features(lsfg_dxbc PRIVATE cxx_std_20)
target_include_directories(lsfg_dxbc PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/../winlator/renderer/vulkan/lsfg
    ${lsfg_dxbc_root}/include/dxbc
    ${lsfg_dxbc_root}/include/dxvk
    ${lsfg_dxbc_root}/include/spirv
    ${lsfg_dxbc_root}/include/util
    ${lsfg_spirv_root}/include)
