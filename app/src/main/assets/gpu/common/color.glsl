// StageClearColor / StageClearProtections / R7Base の数式を移す。
// include 用。#version / precision / SC_* 定数は ShaderLibrary が用意する。
float cs_signedCbrt(float x) {
    if (x == 0.0) return 0.0;
    precise float a = abs(x);
    precise float y = pow(a, 1.0 / 3.0);
    precise float cube = y * y * y;
    precise float denominator = 3.0 * y * y;
    // One Newton step reduces the pow seed's error. At finite extremes, keep the
    // finite seed if its cube overflows or the denominator underflows to zero.
    if (!isinf(cube) && denominator > 0.0) {
        y = y - (cube - a) / denominator;
    }
    return sign(x) * y;
}

vec3 cs_linearToLab(vec3 rgb) {
    // 行優先。Kotlin の Float の乗加算順序を維持する（mat3 は列優先）。
    precise float l = SC_STAGECLEAR_RGB_TO_LMS_0 * rgb.r +
        SC_STAGECLEAR_RGB_TO_LMS_1 * rgb.g + SC_STAGECLEAR_RGB_TO_LMS_2 * rgb.b;
    precise float m = SC_STAGECLEAR_RGB_TO_LMS_3 * rgb.r +
        SC_STAGECLEAR_RGB_TO_LMS_4 * rgb.g + SC_STAGECLEAR_RGB_TO_LMS_5 * rgb.b;
    precise float s = SC_STAGECLEAR_RGB_TO_LMS_6 * rgb.r +
        SC_STAGECLEAR_RGB_TO_LMS_7 * rgb.g + SC_STAGECLEAR_RGB_TO_LMS_8 * rgb.b;
    l = cs_signedCbrt(l);
    m = cs_signedCbrt(m);
    s = cs_signedCbrt(s);
    precise vec3 lab = vec3(
        SC_STAGECLEAR_LMS_TO_LAB_0 * l + SC_STAGECLEAR_LMS_TO_LAB_1 * m + SC_STAGECLEAR_LMS_TO_LAB_2 * s,
        SC_STAGECLEAR_LMS_TO_LAB_3 * l + SC_STAGECLEAR_LMS_TO_LAB_4 * m + SC_STAGECLEAR_LMS_TO_LAB_5 * s,
        SC_STAGECLEAR_LMS_TO_LAB_6 * l + SC_STAGECLEAR_LMS_TO_LAB_7 * m + SC_STAGECLEAR_LMS_TO_LAB_8 * s);
    return lab;
}

vec3 cs_labToLinear(vec3 lab) {
    precise float l = SC_STAGECLEAR_LAB_TO_LMS_0 * lab.x +
        SC_STAGECLEAR_LAB_TO_LMS_1 * lab.y + SC_STAGECLEAR_LAB_TO_LMS_2 * lab.z;
    precise float m = SC_STAGECLEAR_LAB_TO_LMS_3 * lab.x +
        SC_STAGECLEAR_LAB_TO_LMS_4 * lab.y + SC_STAGECLEAR_LAB_TO_LMS_5 * lab.z;
    precise float s = SC_STAGECLEAR_LAB_TO_LMS_6 * lab.x +
        SC_STAGECLEAR_LAB_TO_LMS_7 * lab.y + SC_STAGECLEAR_LAB_TO_LMS_8 * lab.z;
    l = l * l * l;
    m = m * m * m;
    s = s * s * s;
    precise vec3 rgb = vec3(
        SC_STAGECLEAR_LMS_TO_RGB_0 * l + SC_STAGECLEAR_LMS_TO_RGB_1 * m + SC_STAGECLEAR_LMS_TO_RGB_2 * s,
        SC_STAGECLEAR_LMS_TO_RGB_3 * l + SC_STAGECLEAR_LMS_TO_RGB_4 * m + SC_STAGECLEAR_LMS_TO_RGB_5 * s,
        SC_STAGECLEAR_LMS_TO_RGB_6 * l + SC_STAGECLEAR_LMS_TO_RGB_7 * m + SC_STAGECLEAR_LMS_TO_RGB_8 * s);
    return rgb;
}

float cs_linearToUnit(float v) {
    v = clamp(v, 0.0, 1.0);
    return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1.0 / 2.4) - 0.055;
}

float cs_unitToLinear(float v) {
    v = clamp(v, 0.0, 1.0);
    return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4);
}

float cs_encode(float linear) {
    float y = max(linear, 0.0);
    return y <= 0.0031308 ? 12.92 * y : 1.055 * pow(y, 1.0 / 2.4) - 0.055;
}

float cs_decode(float display) {
    float u = max(display, 0.0);
    return u <= 0.04045 ? u / 12.92 : pow((u + 0.055) / 1.055, 2.4);
}

float cs_hueDeg(vec3 lab) {
    // GLSL atan(0, 0) は未定義。Kotlin atan2 の符号付きゼロも保持する。
    if (lab.y == 0.0 && lab.z == 0.0) {
        return (floatBitsToUint(lab.y) & 0x80000000u) == 0u ? 0.0 : 180.0;
    }
    // B2: refine the atan seed once. Normalize only the residual's direction
    // to avoid subnormal products and overflow at finite Float extremes.
    precise float scale = max(abs(lab.y), abs(lab.z));
    precise float a = lab.y / scale;
    precise float b = lab.z / scale;
    precise float theta = atan(lab.z, lab.y);
    precise float cosine = cos(theta);
    precise float sine = sin(theta);
    precise float numerator = b * cosine - a * sine;
    precise float denominator = a * cosine + b * sine;
    theta = theta + numerator / denominator;
    precise float h = degrees(theta);
    if (h < 0.0) h = h + 360.0;
    return h == 360.0 ? 0.0 : h;
}

float cs_band(float hue, float centre, float halfWidth) {
    float t = clamp(1.0 - abs(mod(hue - centre + 540.0, 360.0) - 180.0) / halfWidth, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float cs_skinWeight(float l, float c, float hueDeg) {
    float distance = mod(hueDeg - 40.0 + 540.0, 360.0) - 180.0;
    float t = clamp(1.0 - abs(distance) / 45.0, 0.0, 1.0);
    float band = t * t * (3.0 - 2.0 * t);
    return band * clamp((c - 0.015) / 0.025, 0.0, 1.0) *
        clamp(1.0 - (c - 0.16) / 0.06, 0.0, 1.0) * clamp((l - 0.25) / 0.15, 0.0, 1.0);
}
