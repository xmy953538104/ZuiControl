// Typed classification is independent of display literals and never emits private what().
#include "../../native/zuiopt/ZUIopt_lifecycle.h"
#include <sys/stat.h>
using namespace ZUIopt;
int main(int argc, char **argv) {
    try {
        require(argc == 2 && getuid() == 0, "dedicated root fixture");
        auto began = now();
        std::string path = argv[1];
        require(path.find("zuiopt-r5-fatal-") != path.npos && mkdir(path.c_str(), 0700) == 0, "exclusive fixture path");
        const std::string boot = "12345678-1234-1234-1234-123456789abc";
        bool classified = false;
        try {
            require(false, "new guard private.package tid=98765");
        } catch (const std::exception &error) {
            classified = fatalCode(error) == ErrorCode::INVARIANT && fatalReason(error) == "invariant_failed";
            require(recordLifecycle(path, boot, StartupStage::EVENT_LOOP, &error), "typed fatal receipt");
        }
        require(classified, "new require classified without string whitelist");
        auto receipt = read(path + "/fatal.v1");
        require(receipt.find("error_code=INVARIANT\n") != receipt.npos &&
                    receipt.find("private.package") == receipt.npos && receipt.find("98765") == receipt.npos,
                "typed receipt privacy");
        std::runtime_error unknown("factory read bound");
        require(fatalCode(unknown) == ErrorCode::UNKNOWN && fatalReason(unknown) == "factory_read_bound",
                "legacy display cannot spoof typed classification");
        NativeError binder(ErrorCode::BINDER, "future Binder condition private payload");
        require(fatalCode(binder) == ErrorCode::BINDER && fatalReason(binder) == "binder_failed",
                "typed Binder category");
        NativeError recovery(ErrorCode::RECOVERY, "future recovery condition");
        require(recordLifecycle(path, boot, StartupStage::EVENT_LOOP, &binder, EventSubstage::SCAN_APPLY, &recovery,
                                EventSubstage::CATCH_RELEASE_ALL),
                "primary and recovery categories");
        receipt = read(path + "/fatal.v1");
        require(receipt.find("error_code=BINDER\n") != receipt.npos &&
                    receipt.find("cleanup_error_code=RECOVERY\n") != receipt.npos && receipt.size() < 1024,
                "cleanup never erases primary failure");
        require(fatalCode(std::bad_alloc()) == ErrorCode::ALLOCATION &&
                    fatalCode(std::runtime_error("totally unknown")) == ErrorCode::UNKNOWN,
                "standard and unknown errors fail explicitly");
        puts("TYPED_FATAL_NEW_REQUIRE_MAPPING_PRIVACY_AND_RECOVERY=PASS");
        printf("LOCAL_ELAPSED_MS=%lld\n", static_cast<long long>(now() - began));
        return 0;
    } catch (const std::exception &error) {
        fprintf(stderr, "FATAL_FIXTURE_FAIL %s\n", error.what());
        return 1;
    }
}
