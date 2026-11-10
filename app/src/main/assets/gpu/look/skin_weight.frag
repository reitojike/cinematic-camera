#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_base;
layout(location = 0) out vec4 o_color;

// StageClearColor.skinWeight. The caller blurs this R32F plane with SKIN_RADIUS,
// then runs glow_skin_clip.frag before retaining the original base's skin field.
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 lab = cs_linearToLab(texelFetch(s_base, pixel, 0).rgb);
    float weight = cs_skinWeight(lab.x, length(lab.yz), cs_hueDeg(lab));
    o_color = vec4(weight, 0.0, 0.0, 1.0);
}
