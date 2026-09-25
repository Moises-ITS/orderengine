cmake_minimum_required(VERSION 3.20)
project(orderengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# On MinGW, link the C++ runtime into the binary. A UCRT-built executable that
# finds an MSVCRT-built libstdc++-6.dll earlier on PATH -- Git for Windows ships
# one -- crashes inside <fstream>, and a self-contained binary cannot.
if(MINGW)
  add_link_options(-static-libstdc++ -static-libgcc)
endif()

add_library(ob_core STATIC
  src/order_book.cpp
  src/matching_engine.cpp
  src/latency.cpp
  src/bench.cpp
  src/cli.cpp
)
target_include_directories(ob_core PUBLIC include)

if(MSVC)
  target_compile_options(ob_core PRIVATE /W4 /permissive-)
else()
  target_compile_options(ob_core PRIVATE -Wall -Wextra -Wpedantic -Wconversion)
endif()

# The CLI. Benchmarks live behind --bench so there is a single binary to ship.
add_executable(orderengine src/main.cpp)
target_link_libraries(orderengine PRIVATE ob_core)

enable_testing()
add_executable(ob_tests
  tests/test_main.cpp
  tests/test_order_book.cpp
  tests/test_engine.cpp
  tests/test_priority.cpp
)
target_link_libraries(ob_tests PRIVATE ob_core)
target_include_directories(ob_tests PRIVATE tests)
add_test(NAME unit COMMAND ob_tests)

# The example feed doubles as an end-to-end smoke test.
add_test(NAME feed
         COMMAND orderengine --feed ${CMAKE_CURRENT_SOURCE_DIR}/examples/feed.txt)
