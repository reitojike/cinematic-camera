#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#if SC_MIST_KERNELS_COUNT != 2
#error Mist composition must follow every Kotlin halo kernel in order.
#endif

uniform sampler2D s_glowed;
uniform sampler2D s_skinWeight;
uniform sampler2D s_halo0;
uniform sampler2D s_halo1;
// (autoStrength * strength).toFloat(). Keep the Double value for MistGpuResult.
uniform float u_amount;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 glowed = texelFetch(s_glowed, pixel, 0).rgb;
    precise vec3 halo = vec3(0.0);
    halo += SC_MIST_KERNELS_0_WEIGHT * texelFetch(s_halo0, pixel, 0).rgb;
    halo += SC_MIST_KERNELS_1_WEIGHT * texelFetch(s_halo1, pixel, 0).rgb;
    precise float protect = 1.0 - SC_MIST_SKIN_PROTECT * texelFetch(s_skinWeight, pixel, 0).r;
    // Keep applyMist's rounded intermediate and render's subtraction. Do not
    // simplify this into glowed + amount * MIST_AMOUNT * halo * protect.
    precise vec3 mist = glowed + SC_MIST_MIST_AMOUNT * halo * protect;
    precise vec3 mixed = glowed + u_amount * (mist - glowed);
    o_color = vec4(mixed, 1.0);
}
