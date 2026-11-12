#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_input;
// R32F。Phase 0 の製品経路は全 0。検出顔マスクではない。
uniform sampler2D s_roi;
// Kotlin: 2.0.pow(ev + R7BaseV1.BRIGHTNESS_EV + R7BaseV1.FOUNDATION_EV)。
uniform float u_gain;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 rgb = texelFetch(s_input, pixel, 0).rgb;
    precise float y = rgb.r * 0.2126 + rgb.g * 0.7152 + rgb.b * 0.0722;
    float local = texelFetch(s_roi, pixel, 0).r * clamp(y / 0.025, 0.0, 1.0);
    // Same endpoint extension as R7Base: never evaluate the HDR pole (#355).
    float exposed;
    if (y <= 0.0) exposed = 0.0;
    else if (y >= 1.0) exposed = 1.0;
    else exposed = y * u_gain / (1.0 + (u_gain - 1.0) * y);
    float power = 1.0 + (SC_R7_CONTRAST - 1.0) * (1.0 - 0.75 * local);
    float raised = pow(clamp(exposed, 0.0, 1.0), power);
    float lower = pow(clamp(1.0 - exposed, 0.0, 1.0), power) * pow(0.18 / 0.82, power - 1.0);
    float target = raised / max(raised + lower, 1e-8);
    precise vec3 values = rgb * target / max(y, 1e-8);
    // R7Base.foundation は global tint が unity でも再正規化を省かない。
    precise float luma = values.r * 0.2126 + values.g * 0.7152 + values.b * 0.0722;
    float norm = target / max(luma, 1e-8);
    values *= norm;
    float yellow = clamp(3.0 * (min(values.r, values.g) - values.b) / max(target, 1e-8), 0.0, 1.0);
    vec3 tint = vec3(SC_R7_YELLOW_RED, SC_R7_YELLOW_GREEN, SC_R7_YELLOW_BLUE);
    values *= 1.0 + (tint - 1.0) * local * yellow;
    precise float yellowLuma = values.r * 0.2126 + values.g * 0.7152 + values.b * 0.0722;
    float yellowNorm = target / max(yellowLuma, 1e-8);
    float saturation = SC_R7_SATURATION * (1.0 - local) + SC_R7_SKIN_SATURATION * local;
    o_color = vec4(target + (values * yellowNorm - target) * saturation, 1.0);
}
