// Value types shared by the accepted engine and the bounded rule manager.
#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>
namespace ZUIopt {
enum class ErrorCode {
    UNKNOWN,
    INVARIANT,
    CONFIG,
    PROC,
    PERMISSION,
    BINDER,
    JOURNAL,
    OWNERSHIP,
    RECOVERY,
    ALLOCATION,
    SYSTEM
};
struct NativeError : std::runtime_error {
    ErrorCode errorCode;
    NativeError(ErrorCode code, const std::string &why) : std::runtime_error(why), errorCode(code) {}
};
inline const char *errorCodeName(ErrorCode code) {
    switch (code) {
    case ErrorCode::INVARIANT:
        return "INVARIANT";
    case ErrorCode::CONFIG:
        return "CONFIG";
    case ErrorCode::PROC:
        return "PROC";
    case ErrorCode::PERMISSION:
        return "PERMISSION";
    case ErrorCode::BINDER:
        return "BINDER";
    case ErrorCode::JOURNAL:
        return "JOURNAL";
    case ErrorCode::OWNERSHIP:
        return "OWNERSHIP";
    case ErrorCode::RECOVERY:
        return "RECOVERY";
    case ErrorCode::ALLOCATION:
        return "ALLOCATION";
    case ErrorCode::SYSTEM:
        return "SYSTEM";
    default:
        return "UNKNOWN";
    }
}
inline void require(bool ok, const char *why, ErrorCode code = ErrorCode::INVARIANT) {
    if (!ok)
        throw NativeError(code, why);
}
struct Snapshot {
    std::string name;
    int pid = 0, uid = 0, flags = 0, importance = 0, state = 0;
    bool focused = false;
    std::vector<std::string> packages;
};
}
