cmake_minimum_required(VERSION 3.10)
project(PS3Dec C)

# Default to Release builds
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE "Release")
    message(STATUS "No build type selected, defaulting to a Release build")
endif()

# Set C standard requirements
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

# mbedTLS 4.x (AES is used through the PSA Crypto API, provided by TF-PSA-Crypto)
find_package(MbedTLS 4 CONFIG QUIET)
if (TARGET MbedTLS::tfpsacrypto)
    message(STATUS "Using mbedTLS ${MbedTLS_VERSION}")
    add_library(PS3Dec::Crypto INTERFACE IMPORTED)
    target_link_libraries(PS3Dec::Crypto INTERFACE MbedTLS::tfpsacrypto)
else()
    find_package(TF-PSA-Crypto 1 CONFIG REQUIRED)
    message(STATUS "Using TF-PSA-Crypto ${TF-PSA-Crypto_VERSION}")
    add_library(PS3Dec::Crypto INTERFACE IMPORTED)
    target_link_libraries(PS3Dec::Crypto INTERFACE TF-PSA-Crypto::tfpsacrypto)
endif()

# Add project files
add_subdirectory(src)
