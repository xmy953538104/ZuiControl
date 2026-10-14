// Actual RuleStore, fsync and rename; legacy bytes are immutable migration inputs.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_store.h"
#include <filesystem>
#include <sys/wait.h>
using namespace ZUIopt;
const std::string legacy = "schema 2\nenabled true\ndebug false\nprofile P 2,3,4,5,6\n"
                           "thread P C1 contains \"Render\" selector=rank:2 20 7\n"
                           "package contains com.example P 101\npackage exact org.example.game P 100\n";
const std::string factory3 = "schema 3\nenabled true\ndebug false\nruntime_defaults 0 0\n";
const std::string id = "g111111111111111111111111";
void seed(const std::string &path, bool mixed) {
    require(mkdir(path.c_str(), 0700) == 0, "exclusive migration fixture");
    PrivateDir root(path), generations(root, "generations", true), generation(generations, id, true);
    auto user = legacy;
    if (mixed) {
        user.replace(0, 9, "schema 3\n");
        auto at = user.find("profile P");
        user.insert(at, "runtime_defaults 2 1\n");
        at = user.find("thread P");
        user.insert(at, "runtime P -1 -1 explicit none\n");
        at = user.find("package contains");
        user.insert(at, "profile Q 0,1,2,3,4,5,6,7\nruntime Q 1 0 preset double\n");
        user += "package exact org.owner.new Q 99\n";
    }
    auto effective = "# ZUIOPT_GENERATION " + id + "\n" + user;
    std::string source = "immutable-old-import-receipt", provenance = "source=user\n";
    auto metadata = "{\"source\":\"FROZEN_FACTORY_SEED\",\"rulesHash\":\"" + sha256(legacy) + "\"}";
    generation.put("source.bin", source, true);
    generation.put("last_good.conf", "", true);
    generation.put("provenance.txt", provenance, true);
    generation.put("upstream.conf", legacy, true);
    generation.put("upstream.metadata.json", metadata, true);
    generation.put("canonical_state.v3",
                   "ZUIOPT_CANONICAL_STATE_V3\n" + id + "\n" + std::string(24, 'a') + "\n" + sha256(user) +
                       "\nschema2-1\n" + sha256(provenance) + "\nnone\n" + sha256(source) + "\n" + sha256(legacy) +
                       "\n" + sha256(metadata) + "\n",
                   true);
    generation.put("effective.conf", effective, true);
    root.put("migration_factory.conf", legacy, true);
    root.put("upload.meta", std::string(24, 'b') + ":old-generation-bound-upload");
    root.put("upload.bin", "old-partial-body");
    root.put("library-preview.v1", "old-preview");
    root.put("effective.conf", effective);
}
void check(const std::string &path, bool mixed) {
    auto original = read(path + "/generations/" + id + "/effective.conf");
    auto upstream = read(path + "/generations/" + id + "/upstream.conf");
    RuleStore store(path, factory3);
    store.initialize();
    auto current = store.current();
    require(current.user.rfind("schema 3\n", 0) == 0 && current.upstream.rfind("schema 3\n", 0) == 0,
            "schema3 required in both canonical and upstream");
    auto config = rules(current.user);
    PrivateDir stateRoot(path);
    require(!stateRoot.exists("upload.meta") && !stateRoot.exists("upload.bin") &&
                !stateRoot.exists("library-preview.v1"),
            "exact old draft invalidated after migration with backup retained");
    require(config.mode == (mixed ? 2 : 0) && config.rt == (mixed ? 1 : 0), "Owner globals preserved");
    const auto &p = config.profiles.at("P");
    require(p.general == 124 && !p.runtime.preset && p.runtime.mode == -1 && p.runtime.rt == -1 &&
                p.rules.size() == 1 && p.rules[0].rank == 2 && p.rules[0].mask == 128 &&
                config.packages[0].kind == "contains" && config.packages[0].priority == 101,
            "explicit masks inheritance rank and package ordering preserved");
    if (mixed) {
        const auto &custom = config.profiles.at("Q").runtime;
        require(custom.mode == 1 && custom.rt == 0 && custom.preset && custom.marker == "double",
                "Owner explicit override and marker preserved independently of global defaults");
    }
    require(read(path + "/generations/" + id + "/effective.conf") == original &&
                read(path + "/generations/" + id + "/upstream.conf") == upstream &&
                read(path + "/migration_factory.conf") == legacy,
            "legacy evidence bytes immutable");
    auto generation = generationOf(current.effective);
    store.initialize();
    require(generationOf(store.current().effective) == generation, "migration idempotent");
    store.apply("rollback", generation, "");
    auto rolled = store.current();
    require(generationOf(rolled.effective) != generation && rolled.user == current.user &&
                rolled.upstream == current.upstream,
            "Schema3 rollback point with forward generation");
}
int main(int argc, char **argv) {
    try {
        require(argc == 2 && getuid() == 0, "root fixture only");
        alarm(30);
        std::string root = argv[1];
        require(root.find("zuiopt-r5-migration-") != root.npos && mkdir(root.c_str(), 0700) == 0,
                "exclusive test root");
        auto began = now();
        auto baseline = rules("schema 3\nenabled true\nprofile P 0-7\npackage exact org.example.app P 100\n");
        auto owner = baseline;
        owner.mode = 1;
        owner.rt = 1;
        auto incoming = baseline;
        incoming.profiles.at("P").runtime = {-1, -1, true, "double"};
        auto changed = mergeLibrary(baseline, owner, incoming);
        require(changed.changes.size() == 1 && changed.changes[0].runtimeChanged &&
                    libraryPreview(changed, 0).find("\"newRuntime\":{") != std::string::npos &&
                    rules(changed.canonical).mode == 1 && rules(changed.canonical).rt == 1,
                "upstream runtime diff visible without overriding Owner globals");
        auto globalsOnly = mergeLibrary(baseline, owner, baseline);
        require(!globalsOnly.changes[0].runtimeChanged && globalsOnly.changes[0].provenance == "UPSTREAM",
                "global defaults do not fabricate per-App modifications");
        for (bool mixed : {false, true}) {
            auto path = root + (mixed ? "/mixed" : "/schema2");
            seed(path, mixed);
            check(path, mixed);
        }
        int faults = 0;
        for (int boundary = 1; boundary <= 5; ++boundary) {
            auto path = root + "/kill-" + std::to_string(boundary);
            seed(path, boundary % 2);
            auto child = fork();
            require(child >= 0, "migration fault fork");
            if (!child) {
                RuleStore::testMigrationFault = boundary;
                RuleStore store(path, factory3);
                store.initialize();
                _exit(7);
            }
            int status = 0;
            require(waitpid(child, &status, 0) == child && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL,
                    "actual migration SIGKILL boundary");
            ++faults;
            check(path, boundary % 2);
        }
        require(faults == 5, "actual migration fault denominator");
        printf("ACTUAL_SIGKILL_BOUNDARIES=%d\n", faults);
        puts("SCHEMA3_ATOMIC_MIGRATION_IMMUTABLE_HISTORY_INHERITANCE_ROLLBACK=PASS");
        printf("LOCAL_ELAPSED_MS=%lld\n", static_cast<long long>(now() - began));
        return 0;
    } catch (const std::exception &error) {
        fprintf(stderr, "MIGRATION_FIXTURE_FAIL %s\n", error.what());
        return 1;
    }
}
