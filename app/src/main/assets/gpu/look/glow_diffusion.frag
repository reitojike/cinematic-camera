#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_base;
uniform sampler2D s_bloom;
uniform sampler2D s_halation;
uniform sampler2D s_soft;
uniform sampler2D s_skinWeight;
uniform float u_bloomAmount;
uniform float u_halationAmount;
uniform float u_diffusionAmount;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 base = texelFetch(s_base, pixel, 0).rgb;
    float skin = texelFetch(s_skinWeight, pixel, 0).r;
    vec3 bloom = texelFetch(s_bloom, pixel, 0).rgb;
    precise vec3 warm = texelFetch(s_halation, pixel, 0).r *
        vec3(SC_GLOW_HALATION_TINT_0, SC_GLOW_HALATION_TINT_1, SC_GLOW_HALATION_TINT_2);
    precise float protect = 1.0 - SC_GLOW_SKIN_PROTECT * skin;
    // Identical Float order to glow_compose. Recompute after releasing that
    // full-size intermediate, without changing the input or the retained cache.
    precise vec3 glowed = base + (u_bloomAmount * bloom + u_halationAmount * warm) * protect;
    vec3 blurred = clamp(texelFetch(s_soft, pixel, 0).rgb, 0.0, 1.0);
    vec3 clipped = clamp(glowed, 0.0, 1.0);
    precise vec3 diffused = glowed + u_diffusionAmount * (blurred - clipped * blurred) * protect;
    o_color = vec4(diffused, 1.0);
}
