#pragma once

// A 60-line test harness, so the project stays dependency free: tests are
// plain functions that self-register at static-init time.

#include <cstdio>
#include <functional>
#include <sstream>
#include <string>
#include <vector>

namespace test {

struct Case {
    std::string           name;
    std::function<void()> fn;
};

inline std::vector<Case>& registry() {
    static std::vector<Case> cases;
    return cases;
}

inline int& failures() {
    static int n = 0;
    return n;
}

inline int& checks() {
    static int n = 0;
    return n;
}

inline bool add(const char* name, void (*fn)()) {
    registry().push_back(Case{name, fn});
    return true;
}

inline void fail(const char* file, int line, const std::string& what) {
    ++failures();
    std::printf("    FAIL %s:%d  %s\n", file, line, what.c_str());
}

inline void check(bool ok, const char* file, int line, const char* expr) {
    ++checks();
    if (!ok) fail(file, line, expr);
}

template <class A, class B>
void check_eq(const A& a, const B& b, const char* file, int line, const char* expr) {
    ++checks();
    if (!(a == b)) {
        std::ostringstream msg;
        msg << expr << "  (left=" << a << " right=" << b << ")";
        fail(file, line, msg.str());
    }
}

}  // namespace test

#define TEST(name)                                                       \
    static void name();                                                  \
    static const bool test_reg_##name = ::test::add(#name, name);        \
    static void name()

#define CHECK(cond) ::test::check((cond), __FILE__, __LINE__, #cond)

#define CHECK_EQ(a, b) ::test::check_eq((a), (b), __FILE__, __LINE__, #a " == " #b)
