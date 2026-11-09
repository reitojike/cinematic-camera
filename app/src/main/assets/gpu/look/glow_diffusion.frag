#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_glowed;
uniform sampler2D s_soft;
uniform sampler2D s_skinWeight;
// (StageClearGlowV1.DIFFUSION * strength).toFloat(), calculated on the CPU.
uniform float u_diffusionAmount;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 glowed = texelFetch(s_glowed, pixel, 0).rgb;
    vec3 blurred = clamp(texelFetch(s_soft, pixel, 0).rgb, 0.0, 1.0);
    vec3 clipped = clamp(glowed, 0.0, 1.0);
    precise float protect = 1.0 - SC_GLOW_SKIN_PROTECT * texelFetch(s_skinWeight, pixel, 0).r;
    // Preserve the subtraction and multiplication order in renderNonzero.
    precise vec3 diffused = glowed + u_diffusionAmount * (blurred - clipped * blurred) * protect;
    o_color = vec4(diffused, 1.0);
}
