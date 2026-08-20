// ============================================================================
// ocr_postprocess.h — Pure, host-testable PP-OCR post-processing core.
//
// This header holds the parts of the Phase 1 native pipeline that do not
// depend on OpenCV, Paddle Lite, or JNI, so they can be unit-tested on the
// host (g++) with synthetic tensors — exercising the EXACT code path the
// on-device JNI layer calls (see paddle_ocr_jni.cpp).
//
// Stages covered here:
//   - DB detector output post-processing (unclip / box scoring / clipping)
//   - reading-order box sorting
//   - CTC greedy decoding of the recognition (CRNN) branch
//   - JSON serialization matching com.kinonn.ocrmobile.core.parse.parseOcrResult
// ============================================================================
#pragma once

#include <algorithm>
#include <cmath>
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace ocrpp {

struct Pt {
    float x, y;
};

// A rotated rectangle (OpenCV minAreaRect convention): center, size, angle-deg.
struct Box {
    Pt center{0, 0};
    float w = 0, h = 0;
    float angle = 0;  // degrees
};

inline float deg2rad(float d) { return d * static_cast<float>(M_PI) / 180.0f; }

// ---------------------------------------------------------------------------
// Helpers operating on Box (rotated rect)
// ---------------------------------------------------------------------------

// The 4 corners of a rotated rect, in OpenCV's minAreaRect point order
// (bottom-left → top-left → top-right → bottom-right for standard angles).
inline std::vector<Pt> rotated_rect_points(const Box& b) {
    const float a = deg2rad(b.angle);
    const float ca = std::cos(a);
    const float sa = std::sin(a);
    const float w2 = b.w * 0.5f;
    const float h2 = b.h * 0.5f;
    // Local axis (the +y axis is flipped vs screen; keep arithmetic consistent).
    float ux = ca, uy = sa;      // unit vector along width axis
    float vx = -sa, vy = ca;     // unit vector along height axis
    std::vector<Pt> pts(4);
    pts[0] = {b.center.x - ux * w2 - vx * h2, b.center.y - uy * w2 - vy * h2};
    pts[1] = {b.center.x - ux * w2 + vx * h2, b.center.y - uy * w2 + vy * h2};
    pts[2] = {b.center.x + ux * w2 + vx * h2, b.center.y + uy * w2 + vy * h2};
    pts[3] = {b.center.x + ux * w2 - vx * h2, b.center.y + uy * w2 - vy * h2};
    return pts;
}

inline float shoelace_area(const std::vector<Pt>& pts) {
    float area = 0;
    const size_t n = pts.size();
    for (size_t i = 0; i < n; ++i) {
        const Pt& a = pts[i];
        const Pt& b = pts[(i + 1) % n];
        area += a.x * b.y - b.x * a.y;
    }
    return std::abs(area) * 0.5f;
}

inline float perimeter(const std::vector<Pt>& pts) {
    float p = 0;
    const size_t n = pts.size();
    for (size_t i = 0; i < n; ++i) {
        const Pt& a = pts[i];
        const Pt& b = pts[(i + 1) % n];
        p += std::hypot(b.x - a.x, b.y - a.y);
    }
    return p;
}

// Inflate a rotated rect uniformly by `dist` pixels on every side (unclip,
// PP-OCR det_db_unclip_ratio). Keeps center and angle, widens w/h.
inline Box inflate_box(const Box& b, float dist) {
    Box out = b;
    out.w = std::max(0.0f, b.w + 2.0f * dist);
    out.h = std::max(0.0f, b.h + 2.0f * dist);
    return out;
}

// Expand a box by the PaddleOCR unclip rule given its area & perimeter.
inline Box unclip(const Box& b, float unclip_ratio) {
    const auto pts = rotated_rect_points(b);
    const float area = shoelace_area(pts);
    const float peri = perimeter(pts);
    const float dist = (peri > 1e-6f) ? area * unclip_ratio / peri : 0.0f;
    return inflate_box(b, dist);
}

// Clamp a rotated rect so that it stays within [0,W]x[0,H], keeping center on
// image if possible. Used to (a) bound the unclipped box and (b) protect
// box_score indexing.
inline void clip_box_to_image(Box& b, float W, float H) {
    const float hw = b.w * 0.5f;
    const float hh = b.h * 0.5f;
    // Ignore rotation for clipping bounds (over-approximates the extent, safe).
    float cx = std::clamp(b.center.x, hw, W - hw);
    float cy = std::clamp(b.center.y, hh, H - hh);
    if (W - hw <= 0 || H - hh <= 0) {  // degenerate box
        return;
    }
    b.center.x = cx;
    b.center.y = cy;
}

// Point-in-rotated-rect test: transform point into the rect's local frame and
// check it is within [-w/2,w/2] x [-h/2,h/2].
inline bool point_in_box(const Box& b, float px, float py) {
    const float a = deg2rad(b.angle);
    const float ca = std::cos(a), sa = std::sin(a);
    const float dx = px - b.center.x;
    const float dy = py - b.center.y;
    const float lx = ca * dx + sa * dy;   // project onto width axis
    const float ly = -sa * dx + ca * dy;  // onto height axis
    return std::abs(lx) <= b.w * 0.5f && std::abs(ly) <= b.h * 0.5f;
}

// Mean detection probability inside a rotated box (PP-OCR BoxScoreFast).
// `prob` is the model's probability map, width-major, size pw*ph.
inline float box_score(const float* prob, int pw, int ph, const Box& b) {
    const float minx = std::max(0.0f, b.center.x - b.w * 0.5f - 1.0f);
    const float maxx = std::min(static_cast<float>(pw - 1), b.center.x + b.w * 0.5f + 1.0f);
    const float miny = std::max(0.0f, b.center.y - b.h * 0.5f - 1.0f);
    const float maxy = std::min(static_cast<float>(ph - 1), b.center.y + b.h * 0.5f + 1.0f);
    const int x0 = static_cast<int>(std::floor(minx));
    const int x1 = static_cast<int>(std::ceil(maxx));
    const int y0 = static_cast<int>(std::floor(miny));
    const int y1 = static_cast<int>(std::ceil(maxy));

    double sum = 0.0;
    int count = 0;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            if (x < 0 || x >= pw || y < 0 || y >= ph) continue;
            if (point_in_box(b, static_cast<float>(x), static_cast<float>(y))) {
                // Mean over ALL pixels inside the box mask (matches PaddleOCR's
                // BoxScoreFast / cv2.mean over the polygon mask): background
                // near-zero pixels are included, which keeps scores discriminative.
                sum += prob[y * pw + x];
                ++count;
            }
        }
    }
    return count > 0 ? static_cast<float>(sum / count) : 0.0f;
}

// ---------------------------------------------------------------------------
// Reading-order sort (top-to-bottom, then left-to-right within a row)
// ---------------------------------------------------------------------------
inline void sort_reading_order(std::vector<Box>& boxes) {
    const size_t n = boxes.size();
    std::vector<Box> work = boxes;
    std::vector<size_t> idx(n);
    for (size_t i = 0; i < n; ++i) idx[i] = i;

    // Vertical extent (min corner y) and average height for the row threshold.
    std::vector<float> topY(n), avgH(n);
    float sumH = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        const auto pts = rotated_rect_points(work[i]);
        float minY = 1e30f, maxY = -1e30f;
        for (const auto& p : pts) { minY = std::min(minY, p.y); maxY = std::max(maxY, p.y); }
        topY[i] = minY;
        avgH[i] = maxY - minY;
        sumH += avgH[i];
    }
    const float meanH = n > 0 ? sumH / n : 0.0f;
    const float yThresh = std::max(meanH * 0.7f, 6.0f);

    // Primary sort by top edge.
    for (size_t i = 0; i < n; ++i)
        for (size_t j = i + 1; j < n; ++j)
            if (topY[idx[j]] < topY[idx[i]]) std::swap(idx[i], idx[j]);

    // Group into rows by the y threshold, then sort each row left-to-right.
    std::vector<std::vector<size_t>> rowGroups;
    for (size_t i = 0; i < n; ++i) {
        const size_t id = idx[i];
        if (rowGroups.empty() ||
            std::abs(topY[id] - topY[rowGroups.back().front()]) > yThresh) {
            rowGroups.push_back({id});
        } else {
            rowGroups.back().push_back(id);
        }
    }
    std::vector<size_t> perm;
    for (auto& row : rowGroups) {
        std::sort(row.begin(), row.end(), [&](size_t a, size_t b) {
            const auto ap = rotated_rect_points(work[a]);
            const auto bp = rotated_rect_points(work[b]);
            float ax = 1e30f, bx = 1e30f;
            for (const auto& p : ap) ax = std::min(ax, p.x);
            for (const auto& p : bp) bx = std::min(bx, p.x);
            return ax < bx;
        });
        perm.insert(perm.end(), row.begin(), row.end());
    }
    for (size_t i = 0; i < n; ++i) boxes[i] = work[perm[i]];
}

// ---------------------------------------------------------------------------
// CTC greedy decoding (recognition branch)
// ---------------------------------------------------------------------------
//
// dict[0] is the CTC blank placeholder ("" — skipped). dict[c] for c>=1 is the
// character for class c. collapse consecutive duplicates, drop blanks.
//
struct Recognition {
    std::string text;
    float confidence = 0.0f;
    bool valid = false;
};

inline Recognition ctc_greedy_decode(const std::vector<std::string>& dict,
                                     const float* probs, int steps, int classes) {
    Recognition r;
    if (dict.empty() || steps <= 0 || classes <= 0) return r;

    std::string chars;
    double confSum = 0.0;
    int confCount = 0;
    int prevIdx = -1;
    for (int t = 0; t < steps; ++t) {
        const float* row = probs + t * classes;
        int best = 0;
        float bestP = row[0];
        for (int c = 1; c < classes; ++c) {
            if (row[c] > bestP) { bestP = row[c]; best = c; }
        }
        if (best != 0 && best != prevIdx) {  // non-blank and not a duplicate
            if (best < static_cast<int>(dict.size())) {
                chars += dict[best];  // dict[0]=blank placeholder, class c -> dict[c]
            }
        }
        if (best != 0) {  // accumulate confidence over real letters only
            confSum += bestP;
            ++confCount;
        }
        prevIdx = best;
    }
    r.text = chars;
    r.valid = !chars.empty();
    r.confidence = confCount > 0 ? static_cast<float>(confSum / confCount) : 0.0f;
    return r;
}

// ---------------------------------------------------------------------------
// JSON serialization (matches the Kotlin parser contract exactly)
// ---------------------------------------------------------------------------
inline std::string box_to_json(const Box& b, float imgW, float imgH) {
    const auto pts = rotated_rect_points(b);
    float l = 1e30f, t = 1e30f, r = -1e30f, bb = -1e30f;
    for (const auto& p : pts) {
        l = std::min(l, p.x); t = std::min(t, p.y);
        r = std::max(r, p.x); bb = std::max(bb, p.y);
    }
    // Normalize to 0..1 (the Kotlin side BoundingBox is normalized).
    const float nw = imgW > 0 ? imgW : 1.0f;
    const float nh = imgH > 0 ? imgH : 1.0f;
    std::ostringstream os;
    os << "{\"left\":" << std::clamp(l / nw, 0.0f, 1.0f)
       << ",\"top\":" << std::clamp(t / nh, 0.0f, 1.0f)
       << ",\"right\":" << std::clamp(r / nw, 0.0f, 1.0f)
       << ",\"bottom\":" << std::clamp(bb / nh, 0.0f, 1.0f) << "}";
    return os.str();
}

inline std::string json_escape(const std::string& s) {
    std::ostringstream os;
    for (const char c : s) {
        switch (c) {
            case '"': os << "\\\""; break;
            case '\\': os << "\\\\"; break;
            case '\n': os << "\\n"; break;
            case '\r': os << "\\r"; break;
            case '\t': os << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    os << buf;
                } else {
                    os << c;
                }
        }
    }
    return os.str();
}

struct OutputBlock {
    std::string text;
    float confidence = 0.0f;
    Box box;
};

inline std::string build_json(long latency_ms,
                              const std::vector<OutputBlock>& blocks,
                              float imgW, float imgH) {
    std::ostringstream os;
    os << "{\"latency_ms\":" << latency_ms << ",\"blocks\":[";
    for (size_t i = 0; i < blocks.size(); ++i) {
        if (i) os << ",";
        os << "{\"text\":\"" << json_escape(blocks[i].text)
           << "\",\"confidence\":" << blocks[i].confidence
           << ",\"box\":" << box_to_json(blocks[i].box, imgW, imgH) << "}";
    }
    os << "]}";
    return os.str();
}

}  // namespace ocrpp
