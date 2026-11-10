#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
#include "common/color.glsl"
#include "common/display.glsl"
uniform sampler2D s_input;
uniform vec2 u_surfaceSize;
uniform int u_rotation;
uniform bool u_linearFiltering;
layout(location = 0) out vec4 o_color;
vec3 cs_presentSample(vec2 uv) {
    if (u_linearFiltering) return texture(s_input, uv).rgb;
    // RGBA32F filtering is optional in core ES. Four taps preserve linear display resampling
    // without requiring an additional extension or allocating another full-resolution texture.
    ivec2 size = textureSize(s_input, 0);
    vec2 position = uv * vec2(size) - 0.5;
    ivec2 first = ivec2(floor(position));
    vec2 weight = fract(position);
    vec3 a = texelFetch(s_input, clamp(first, ivec2(0), size - 1), 0).rgb;
    vec3 b = texelFetch(s_input, clamp(first + ivec2(1, 0), ivec2(0), size - 1), 0).rgb;
    vec3 c = texelFetch(s_input, clamp(first + ivec2(0, 1), ivec2(0), size - 1), 0).rgb;
    vec3 d = texelFetch(s_input, clamp(first + ivec2(1, 1), ivec2(0), size - 1), 0).rgb;
    return mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y);
}
void main() {
    vec2 imageSize = vec2(textureSize(s_input, 0));
    vec2 rotatedSize = (u_rotation == 90 || u_rotation == 270) ? imageSize.yx : imageSize;
    vec2 fittedSize = rotatedSize * min(u_surfaceSize.x / rotatedSize.x, u_surfaceSize.y / rotatedSize.y);
    // GL surface origin is bottom-left, input row zero is top-left.
    vec2 topLeft = vec2(gl_FragCoord.x, u_surfaceSize.y - gl_FragCoord.y);
    vec2 uv = (topLeft - (u_surfaceSize - fittedSize) * 0.5) / fittedSize;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        o_color = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    if (u_rotation == 90) uv = vec2(uv.y, 1.0 - uv.x);
    else if (u_rotation == 180) uv = vec2(1.0 - uv.x, 1.0 - uv.y);
    else if (u_rotation == 270) uv = vec2(1.0 - uv.y, uv.x);
    vec3 rgb = cs_presentSample(uv);
    o_color = vec4(cs_display8(rgb), 1.0);
}
