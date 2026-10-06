# Discord Rich Presence bridge (docs/DISCORD_PRESENCE_PLAN.md). Only configured when the proprietary Discord Social
# SDK is present in third_party/discord-social-sdk/; app/build.gradle.kts unpacks its AAR and passes DISCORD_SDK_DIR.
cmake_minimum_required(VERSION 3.22.1)
project(mihon_discord LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(discord_partner_sdk SHARED IMPORTED)
set_target_properties(discord_partner_sdk PROPERTIES
    IMPORTED_LOCATION "${DISCORD_SDK_DIR}/jni/${ANDROID_ABI}/libdiscord_partner_sdk.so")

add_library(mihon_discord SHARED discord_bridge.cpp)
target_include_directories(mihon_discord PRIVATE "${DISCORD_SDK_DIR}/prefab/modules/discord_partner_sdk/include")
target_link_libraries(mihon_discord PRIVATE discord_partner_sdk log)
# 16 KB page alignment (required for new Play uploads, harmless elsewhere).
target_link_options(mihon_discord PRIVATE "-Wl,-z,max-page-size=16384")
