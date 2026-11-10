// TrialDisplayPixels.byte / Original24Attempt.channel: shared final display transfer.
// Include only after common/color.glsl. Inputs are linear RGB; output is normalized 8bit sRGB.
vec3 cs_display8(vec3 linearRgb) {
    vec3 value = clamp(linearRgb, 0.0, 1.0);
    vec3 encoded = vec3(cs_encode(value.r), cs_encode(value.g), cs_encode(value.b));
    // Kotlin roundToInt on non-negative values: ties go up, unlike GLSL round's unspecified ties.
    vec3 bytes = clamp(floor(encoded * 255.0 + 0.5), 0.0, 255.0);
    return bytes / 255.0;
}
