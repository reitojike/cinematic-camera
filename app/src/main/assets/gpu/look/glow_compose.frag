#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_base;
uniform sampler2D s_skinWeight;
// The completed ping-pong sum, not three simultaneously retained full-size blurs.
uniform sampler2D s_bloom;
uniform sampler2D s_halation;
// Calculate (StageClearGlowV1 coefficient * strength).toFloat() on the CPU,
// preserving Kotlin's Double multiply before its explicit Float conversion.
uniform float u_bloomAmount;
uniform float u_halationAmount;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 base = texelFetch(s_base, pixel, 0).rgb;
    float skin = texelFetch(s_skinWeight, pixel, 0).r;
    vec3 bloom = texelFetch(s_bloom, pixel, 0).rgb;
    precise vec3 warm = texelFetch(s_halation, pixel, 0).r *
        vec3(SC_GLOW_HALATION_TINT_0, SC_GLOW_HALATION_TINT_1, SC_GLOW_HALATION_TINT_2);
    precise float protect = 1.0 - SC_GLOW_SKIN_PROTECT * skin;
    precise vec3 glowed = base + (u_bloomAmount * bloom + u_halationAmount * warm) * protect;
    o_color = vec4(glowed, 1.0);
}
