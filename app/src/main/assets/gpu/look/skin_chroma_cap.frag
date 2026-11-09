#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_character;
// R32F aligned face mask. Phase 0 supplies zero; this is NOT colour skinWeight.
uniform sampler2D s_faceMask;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 lab = cs_linearToLab(texelFetch(s_character, pixel, 0).rgb);
    float lightness = lab.x;
    float chroma = length(lab.yz);
    float hue = lab.y == 0.0 && lab.z == 0.0 ? 0.0 : degrees(atan(lab.z, lab.y));
    float wrappedHue = mod(hue + 360.0, 360.0);
    float skin = cs_skinWeight(lightness, chroma, wrappedHue);
    precise float red = cs_band(wrappedHue, 10.0, 30.0) * clamp((chroma - 0.015) / 0.025, 0.0, 1.0) *
        clamp(1.0 - (chroma - SC_STAGECLEAR_SKIN_VIVID) / 0.06, 0.0, 1.0) *
        clamp((lightness - 0.3) / 0.15, 0.0, 1.0);
    float weight = max(skin, red) * texelFetch(s_faceMask, pixel, 0).r;
    precise float capped = chroma > SC_STAGECLEAR_SKIN_KNEE ?
        SC_STAGECLEAR_SKIN_KNEE + (chroma - SC_STAGECLEAR_SKIN_KNEE) * SC_STAGECLEAR_SKIN_SLOPE : chroma;
    precise float newChroma = chroma * (1.0 + weight * (capped / max(chroma, 1e-6) - 1.0));
    precise float newHue = hue < SC_STAGECLEAR_RED_TARGET && hue > -40.0 ?
        hue + SC_STAGECLEAR_SKIN_HUE_LIFT * weight * (SC_STAGECLEAR_RED_TARGET - hue) : hue;
    float angle = radians(newHue);
    lab.y = newChroma * cos(angle);
    lab.z = newChroma * sin(angle);
    // Do not bypass this Lab/polar/RGB round trip when the face mask is zero.
    o_color = vec4(cs_labToLinear(lab), 1.0);
}
