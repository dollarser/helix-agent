cmake_minimum_required(VERSION 3.22)
project(proot_native C)

# HXA-084: the native page-size seam. The installer's ELF pre-activation check
# needs the DEVICE page size (16 KiB devices must not admit 4 KiB-only ELFs),
# and no Java API exposes getpagesize(); this tiny library is the seam.
add_library(proot_native SHARED proot_native.c)
target_link_libraries(proot_native log)
