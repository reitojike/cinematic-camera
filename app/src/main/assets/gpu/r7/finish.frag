#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_input;
layout(location = 0) out vec4 o_color;

// R7Base.vibrance → crisp（tone → Lab）→ skin。
// 融合しても、各 labLayer 間の RGB float と Lab 往復を残す。
void main() {
    precise vec3 rgb = texelFetch(s_input, ivec2(gl_FragCoord.xy), 0).rgb;

    // vibrance
    vec3 lab = cs_linearToLab(rgb);
    float l = lab.x;
    float c = length(lab.yz);
    float h = cs_hueDeg(lab);
    float skinBand = cs_band(h, 50.0, 40.0) * clamp((c - 0.015) / 0.03, 0.0, 1.0) *
        clamp(1.0 - (c - 0.13) / 0.05, 0.0, 1.0);
    float neutral = clamp((c - 0.012) / 0.03, 0.0, 1.0) * clamp(1.0 - (l - 0.72) / 0.2, 0.0, 1.0);
    float gain = 1.0 + SC_R7_VIBRANCE * neutral * clamp(1.0 - c / 0.22, 0.0, 1.0) *
        (1.0 + 0.6 * clamp(1.0 - l / 0.6, 0.0, 1.0)) * (1.0 - 0.85 * skinBand);
    lab.y *= gain;
    lab.z *= gain;
    rgb = cs_labToLinear(lab);

    // crisp: tone 部分。RGB を保存する境界を次の Lab 変換と入れ替えない。
    precise float luma = rgb.r * 0.2126 + rgb.g * 0.7152 + rgb.b * 0.0722;
    float y = max(luma, 1e-8);
    float target = y * y / (y + SC_R7_BLACK);
    float t = clamp(target, 0.0, 1.0);
    float power = SC_R7_CRISP_CONTRAST;
    float raised = pow(t, power);
    float lower = pow(1.0 - t, power) * pow(0.10 / 0.90, power - 1.0);
    if (target < 1.0) target = raised / max(raised + lower, 1e-8);
    rgb = rgb * target / y;

    // crisp: Lab 部分
    lab = cs_linearToLab(rgb);
    c = length(lab.yz);
    // Both chroma adjustments scale a/b equally. Reuse this pixel's hue;
    // retain every Float RGB/Lab boundary and recompute lightness/chroma.
    float skinWeight = cs_skinWeight(lab.x, c, h);
    gain = 1.0 + SC_R7_CRISP_CHROMA * (1.0 - skinWeight);
    lab.y *= gain;
    lab.z *= gain;
    rgb = cs_labToLinear(lab);

    // skin
    lab = cs_linearToLab(rgb);
    c = length(lab.yz);
    float weight = cs_skinWeight(lab.x, c, h);
    float delta = mod(18.0 - h + 540.0, 360.0) - 180.0;
    float angle = radians(h + 0.35 * weight * delta);
    float chroma = c * (1.0 - 0.35 * weight);
    lab.x = lab.x + 0.12 * weight * (1.0 - lab.x);
    lab.y = chroma * cos(angle);
    lab.z = chroma * sin(angle);
    o_color = vec4(cs_labToLinear(lab), 1.0);
}
