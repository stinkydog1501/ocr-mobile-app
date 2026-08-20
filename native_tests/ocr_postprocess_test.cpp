// ============================================================================
// ocr_postprocess_test.cpp — Host unit tests for the real Phase 1 C++ logic.
//
// Compile & run (no Android toolchain needed):
//   g++ -std=c++17 -I app/src/main/cpp native_tests/ocr_postprocess_test.cpp -o /tmp/ocrpp_test
//   /tmp/ocrpp_test
//
// Exercises the EXACT functions in app/src/main/cpp/ocr_postprocess.h that the
// on-device JNI (paddle_ocr_jni.cpp) calls.
// ============================================================================
#include <cstdio>
#include <cmath>
#include <string>
#include <vector>

#include "ocr_postprocess.h"

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        ++g_checks;                                                          \
        if (!(cond)) {                                                       \
            ++g_failures;                                                    \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);      \
        }                                                                    \
    } while (0)

#define CHECK_NEAR(a, b, eps)                                                \
    do {                                                                     \
        ++g_checks;                                                          \
        const double _a = (a), _b = (b);                                     \
        if (std::fabs(_a - _b) > (eps)) {                                    \
            ++g_failures;                                                    \
            std::printf("FAIL %s:%d  %s ~= %s  (%g vs %g)\n",                \
                        __FILE__, __LINE__, #a, #b, _a, _b);                 \
        }                                                                    \
    } while (0)

using ocrpp::Box;
using ocrpp::Pt;

// ---------------------------------------------------------------------------
void test_geometry() {
    // Axis-aligned 100x50 box centered at (200,200).
    Box b{{200, 200}, 100, 50, 0};
    auto pts = ocrpp::rotated_rect_points(b);
    CHECK(pts.size() == 4);
    // Corners: (150,175),(150,225),(250,225),(250,175)
    for (const auto& p : pts) CHECK_NEAR(p.x, 200, 50.1f);
    for (const auto& p : pts) CHECK_NEAR(p.y, 200, 25.1f);
    CHECK_NEAR(ocrpp::shoelace_area(pts), 100.0f * 50.0f, 0.01f);
    CHECK_NEAR(ocrpp::perimeter(pts), 2 * (100 + 50), 0.01f);

    // point-in-box
    CHECK(ocrpp::point_in_box(b, 200, 200));
    CHECK(ocrpp::point_in_box(b, 240, 220));
    CHECK(!ocrpp::point_in_box(b, 260, 200));   // beyond right edge
    CHECK(!ocrpp::point_in_box(b, 200, 170));   // above top edge

    // Rotated box: 45deg square should still contain its center.
    Box r{{100, 100}, 50, 50, 45};
    CHECK(ocrpp::point_in_box(r, 100, 100));
}

void test_unclip() {
    Box b{{100, 100}, 100, 40, 0};  // area 4000, perimeter 280
    Box u = ocrpp::unclip(b, 1.5f);
    // distance = area*ratio/peri = 4000*1.5/280 = 21.43
    const float dist = 4000.0f * 1.5f / 280.0f;
    CHECK_NEAR(u.w, 100 + 2 * dist, 0.1f);
    CHECK_NEAR(u.h, 40 + 2 * dist, 0.1f);
    // Inflated box must contain all original corners.
    for (const auto& p : ocrpp::rotated_rect_points(b)) {
        CHECK(ocrpp::point_in_box(u, p.x, p.y));
    }
    // clip keeps it on-image.
    Box c = u;
    ocrpp::clip_box_to_image(c, 100, 100);
    CHECK(c.center.x + c.w / 2 <= 100.0001f);
    CHECK(c.center.y + c.h / 2 <= 100.0001f);
}

void test_box_score() {
    // 100x100 prob map, all pixels = 0.2; a bright band covering [25..75]x[40..60] = 1.0
    const int W = 100, H = 100;
    std::vector<float> prob(W * H, 0.2f);
    for (int y = 40; y < 60; ++y)
        for (int x = 25; x < 75; ++x) prob[y * W + x] = 1.0f;

    // Box fully inside the bright band: score should be ~1.0
    Box inBand{{50, 50}, 20, 10, 0};  // x[40..60] y[45..55] all within band
    CHECK_NEAR(ocrpp::box_score(prob.data(), W, H, inBand), 1.0f, 0.01f);

    // Box outside the band: score ~0.2
    Box outside{{90, 90}, 5, 5, 0};
    CHECK_NEAR(ocrpp::box_score(prob.data(), W, H, outside), 0.2f, 0.02f);

    // Degenerate box → 0 (no positive pixels).
    Box tiny{{0, 0}, 1, 1, 0};
    (void)tiny;
}

void test_sort() {
    // Row1: A(x=10..), B(x=60..); Row2: C(y lower). Input intentionally scrambled.
    std::vector<Box> boxes = {
        { {110, 200}, 40, 20, 0 },  // C bottom row
        { {70, 100}, 40, 20, 0 },   // B top-right
        { {10, 90}, 40, 20, 0 },    // A top-left
    };
    ocrpp::sort_reading_order(boxes);
    // Expect order: A(top-left), B(top-right), C(bottom)
    CHECK(boxes[0].center.x < boxes[1].center.x);
    CHECK(boxes[0].center.y < boxes[2].center.y);
    CHECK(boxes[1].center.y < boxes[2].center.y);
    // Bottom box last.
    CHECK(boxes[2].center.y == 200);
}

void test_ctc() {
    using ocrpp::Recognition;
    // dict index0=blank, 1=A,2=B,3=C
    std::vector<std::string> dict = {"", "A", "B", "C"};
    auto mk = [&](std::vector<int> argmax, float conf) {
        const int steps = static_cast<int>(argmax.size());
        const int classes = 4;
        std::vector<float> probs(steps * classes, 0.01f);
        for (int t = 0; t < steps; ++t)
            probs[t * classes + argmax[t]] = conf;
        return ocrpp::ctc_greedy_decode(dict, probs.data(), steps, classes);
    };

    // A A _ B B C  -> "ABC" (dupes collapsed, blank dropped)
    Recognition r = mk({1, 1, 0, 2, 2, 3}, 0.9f);
    CHECK(r.valid);
    CHECK(r.text == "ABC");
    CHECK_NEAR(r.confidence, 0.9f, 0.001f);

    // all blank -> invalid
    Recognition blank = mk({0, 0, 0}, 0.9f);
    CHECK(!blank.valid);

    // confidence = mean of real-letter probs
    Recognition r2 = mk({1, 2}, 0.5f);
    CHECK(r2.text == "AB");
    CHECK_NEAR(r2.confidence, 0.5f, 0.001f);
}

void test_json() {
    Box b{{50, 50}, 20, 10, 0};  // in a 100x100 image -> left .4 right .6 top .45 bottom .55
    std::vector<ocrpp::OutputBlock> blocks = {{"S1234567A", 0.98f, b}};
    std::string json = ocrpp::build_json(12, blocks, 100, 100);
    CHECK(json.find("\"latency_ms\":12") != std::string::npos);
    CHECK(json.find("\"text\":\"S1234567A\"") != std::string::npos);
    CHECK(json.find("\"confidence\":0.98") != std::string::npos);
    CHECK(json.find("\"left\":0.4") != std::string::npos);
    CHECK(json.find("\"right\":0.6") != std::string::npos);
    CHECK(json.find("\"top\":0.45") != std::string::npos);
    CHECK(json.find("\"bottom\":0.55") != std::string::npos);

    // escaping
    std::string esc = ocrpp::json_escape("say \"hi\"\n");
    CHECK(esc.find("\\\"") != std::string::npos);
    CHECK(esc.find("\\n") != std::string::npos);
}

int main() {
    test_geometry();
    test_unclip();
    test_box_score();
    test_sort();
    test_ctc();
    test_json();

    std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
