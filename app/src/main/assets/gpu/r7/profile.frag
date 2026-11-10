#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_input;
// PhotoAdjustments.applyWarmth のゲインを Kotlin の Double で計算して渡す。
// warmth == 0 の場合は vec3(1.0)。乗算後の Float 境界を維持する。
uniform vec3 u_warmthGain;
// WARMTH tap 専用。通常の profile パスでは false。
uniform bool u_warmthOnly;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    precise vec3 rgb = texelFetch(s_input, pixel, 0).rgb * u_warmthGain;
    if (u_warmthOnly) {
        o_color = vec4(rgb, 1.0);
        return;
    }

    // R7Base.profile。Lab の往復や色相の式を短縮しない。
    vec3 lab = cs_linearToLab(rgb);
    float c = length(lab.yz);
    float h = cs_hueDeg(lab);
    float angle = radians(h - SC_R7_PROFILE_SHIFT * cs_band(h, 300.0, 50.0) *
        clamp((c - 0.03) / 0.07, 0.0, 1.0));
    lab.y = c * cos(angle);
    lab.z = c * sin(angle);
    o_color = vec4(cs_labToLinear(lab), 1.0);
}
