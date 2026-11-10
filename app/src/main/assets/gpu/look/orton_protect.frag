#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_mist;
uniform sampler2D s_mixed;
uniform sampler2D s_skinWeight;
// MistGpuResult.lightAddingStrength.toFloat(); never recompute auto-strength.
uniform float u_amount;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 mist = texelFetch(s_mist, pixel, 0).rgb;
    // The pass owner skips ALL Orton passes for Double amount == 0.0.
    // Keep direct use of this shader an exact identity as well.
    if (u_amount == 0.0) {
        o_color = vec4(mist, 1.0);
        return;
    }
    vec3 before = cs_linearToLab(mist);
    vec3 after = cs_linearToLab(texelFetch(s_mixed, pixel, 0).rgb);
    float c0 = length(before.yz);
    float c1 = length(after.yz);
    float keep = texelFetch(s_skinWeight, pixel, 0).r;
    precise float scale = (1.0 - keep) + keep * min(1.0, c0 / max(c1, SC_ORTON_CHROMA_FLOOR));
    after.y *= scale;
    after.z *= scale;
    vec3 orton = cs_labToLinear(after);
    precise vec3 result = mist + u_amount * (orton - mist);
    o_color = vec4(result, 1.0);
}
