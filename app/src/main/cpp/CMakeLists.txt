cmake_minimum_required(VERSION 3.22.1)

project(codepocket C)

# pty.c    — the real terminal (forkpty)
# cpwin.c  — the graphics window for user programs. It lives in this same library on
#            purpose: a user program is compiled to a .so that calls cp_open()/cp_pixel()/…,
#            and those symbols resolve because this library is already loaded by the app.
#            No separate helper .so is needed.
add_library(codepocket SHARED
        pty.c
        cpwin.c)

# libc + liblog + libandroid (ANativeWindow_*)
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(codepocket
        ${log-lib}
        ${android-lib})

set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG")
