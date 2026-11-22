#version 320 es
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D s_rgb16;
uniform usampler2D s_lut;
layout(location = 0) out vec4 o_color;

float decode(uint sampleValue) {
    ivec2 index = ivec2(int(sampleValue & 255u), int(sampleValue >> 8u));
    return uintBitsToFloat(texelFetch(s_lut, index, 0).r);
}

void main() {
    uvec3 rgb = texelFetch(s_rgb16, ivec2(gl_FragCoord.xy), 0).rgb;
    o_color = vec4(decode(rgb.r), decode(rgb.g), decode(rgb.b), 1.0);
}
