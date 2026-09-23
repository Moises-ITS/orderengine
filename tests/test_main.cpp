#include <cstdio>

#include "test_harness.hpp"

int main() {
    std::printf("running %zu tests\n", test::registry().size());

    for (const test::Case& c : test::registry()) {
        const int before = test::failures();
        c.fn();
        std::printf("  %-44s %s\n", c.name.c_str(),
                    test::failures() == before ? "ok" : "FAILED");
    }

    std::printf("\n%d checks, %d failures\n", test::checks(), test::failures());
    return test::failures() == 0 ? 0 : 1;
}
