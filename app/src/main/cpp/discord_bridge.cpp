// Thin JNI bridge from SocialSdkDiscordPresence (Kotlin) to the Discord Social SDK. Rich Presence only, through RPC
// to the signed-in Discord app on the device: no Connect(), no OAuth, no tokens. All calls come from one thread.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <android/log.h>
#include <jni.h>

#include <memory>
#include <optional>
#include <string>

namespace {

constexpr const char* kTag = "MihonDiscord";
std::shared_ptr<discordpp::Client> client;
// Result of the last UpdateRichPresence/ClearRichPresence, reported back to Kotlin by nativeRunCallbacks.
// Only touched on the single presence thread (callbacks run inside RunCallbacks).
std::optional<std::string> pendingResult;

std::optional<std::string> text(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::nullopt;
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_discord_SocialSdkDiscordPresence_nativeInit(JNIEnv*, jclass, jlong applicationId) {
    if (!client) {
        client = std::make_shared<discordpp::Client>();
        client->AddLogCallback(
            [](std::string message, discordpp::LoggingSeverity) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
            },
            discordpp::LoggingSeverity::Info);
    }
    client->SetApplicationId(static_cast<uint64_t>(applicationId));
}

// Drops the client and any pending result; nativeInit then builds a fresh one (recovery after an RPC failure).
extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_discord_SocialSdkDiscordPresence_nativeReset(JNIEnv*, jclass) {
    client.reset();
    pendingResult.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_discord_SocialSdkDiscordPresence_nativeUpdate(
    JNIEnv* env, jclass, jstring name, jstring details, jstring state, jstring largeImage, jstring largeText,
    jstring smallImage, jstring smallText, jlong startedAt,
    jstring partyId, jint partySize, jint partyMax, jstring buttonLabel, jstring buttonUrl, jboolean watching) {
    if (!client) return;
    discordpp::Activity activity;
    // "Watching" by default: a "Playing" activity competes with real games for the status line.
    activity.SetType(watching ? discordpp::ActivityTypes::Watching : discordpp::ActivityTypes::Playing);
    // The title is the activity name: without it Discord shows the SDK's default "Game".
    if (auto title = text(env, name)) activity.SetName(*title);
    activity.SetDetails(text(env, details));
    // Level and room ("Level 12 • Reading with X"); Discord adds the party size after it.
    activity.SetState(text(env, state));
    // Member lists show "Watching <title>" (or "Playing <title>").
    activity.SetStatusDisplayType(discordpp::StatusDisplayTypes::Name);

    // The cover (or the logo without one), and the logo as a small badge on the cover.
    if (auto picture = text(env, largeImage)) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(picture);
        assets.SetLargeText(text(env, largeText));
        if (auto badge = text(env, smallImage)) {
            assets.SetSmallImage(badge);
            assets.SetSmallText(text(env, smallText));
        }
        activity.SetAssets(assets);
    }

    auto label = text(env, buttonLabel);
    auto url = text(env, buttonUrl);
    if (label && url) {
        discordpp::ActivityButton button;
        button.SetLabel(*label);
        button.SetUrl(*url);
        activity.AddButton(std::move(button));
    }

    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(static_cast<uint64_t>(startedAt));
    activity.SetTimestamps(timestamps);

    if (auto id = text(env, partyId); id && partySize > 0) {
        discordpp::ActivityParty party;
        party.SetId(*id);
        party.SetCurrentSize(partySize);
        party.SetMaxSize(partyMax > partySize ? partyMax : partySize);
        party.SetPrivacy(discordpp::ActivityPartyPrivacy::Private);
        activity.SetParty(party);
    }

    client->UpdateRichPresence(std::move(activity), [](discordpp::ClientResult result) {
        if (result.Successful()) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "Rich Presence updated");
            pendingResult = "ok";
        } else {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Rich Presence failed: %s", result.ToString().c_str());
            std::string detail = result.Error();
            if (detail.empty()) detail = result.ToString();
            // RPC failures (the link to the Discord app broke) get their own prefix so Kotlin can rebuild the client.
            pendingResult = (result.Type() == discordpp::ErrorType::RPCError ? "rpcerror:" : "error:") + detail;
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_discord_SocialSdkDiscordPresence_nativeClear(JNIEnv*, jclass) {
    if (client) client->ClearRichPresence();
}

// Pumps the SDK and returns the newest update result ("ok" or "error:<message>"), or null if none arrived.
extern "C" JNIEXPORT jstring JNICALL
Java_mihon_feature_discord_SocialSdkDiscordPresence_nativeRunCallbacks(JNIEnv* env, jclass) {
    discordpp::RunCallbacks();
    if (!pendingResult) return nullptr;
    jstring result = env->NewStringUTF(pendingResult->c_str());
    pendingResult.reset();
    return result;
}
